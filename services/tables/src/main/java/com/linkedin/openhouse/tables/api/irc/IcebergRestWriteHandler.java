package com.linkedin.openhouse.tables.api.irc;

import static com.linkedin.openhouse.common.api.validator.ValidatorConstants.INITIAL_TABLE_VERSION;

import com.linkedin.openhouse.cluster.configs.ClusterProperties;
import com.linkedin.openhouse.cluster.storage.Storage;
import com.linkedin.openhouse.cluster.storage.selector.StorageSelector;
import com.linkedin.openhouse.common.exception.RequestValidationFailureException;
import com.linkedin.openhouse.internal.catalog.CatalogConstants;
import com.linkedin.openhouse.internal.catalog.CreateHistory;
import com.linkedin.openhouse.internal.catalog.fileio.FileIOManager;
import com.linkedin.openhouse.internal.catalog.model.HouseTable;
import com.linkedin.openhouse.internal.catalog.model.HouseTablePrimaryKey;
import com.linkedin.openhouse.internal.catalog.repository.HouseTableRepository;
import com.linkedin.openhouse.internal.catalog.repository.exception.HouseTableConcurrentUpdateException;
import com.linkedin.openhouse.tables.api.handler.IcebergSnapshotsApiHandler;
import com.linkedin.openhouse.tables.api.handler.TablesApiHandler;
import com.linkedin.openhouse.tables.api.spec.v0.request.CreateUpdateTableRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.request.IcebergSnapshotsRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.Policies;
import com.linkedin.openhouse.tables.api.spec.v0.response.GetTableResponseBody;
import com.linkedin.openhouse.tables.api.validator.TablesApiValidator;
import com.linkedin.openhouse.tables.audit.TableAuditAspect;
import com.linkedin.openhouse.tables.audit.model.OperationType;
import com.linkedin.openhouse.tables.authorization.Privileges;
import com.linkedin.openhouse.tables.commit.CommitContext;
import com.linkedin.openhouse.tables.commit.CommitKind;
import com.linkedin.openhouse.tables.common.TableType;
import com.linkedin.openhouse.tables.dto.mapper.iceberg.PartitionSpecMapper;
import com.linkedin.openhouse.tables.dto.mapper.iceberg.PoliciesSpecMapper;
import com.linkedin.openhouse.tables.formatversion.FormatVersionRule;
import com.linkedin.openhouse.tables.formatversion.IcebergV3;
import com.linkedin.openhouse.tables.model.TableDto;
import com.linkedin.openhouse.tables.model.TableDtoWithMetadata;
import com.linkedin.openhouse.tables.repository.CreateHistoryScope;
import com.linkedin.openhouse.tables.repository.PreservedKeyChecker;
import com.linkedin.openhouse.tables.services.IcebergCreates;
import com.linkedin.openhouse.tables.services.IcebergUpdates;
import com.linkedin.openhouse.tables.services.PolicyPatch;
import com.linkedin.openhouse.tables.services.TableCommitService;
import com.linkedin.openhouse.tables.services.TablesService;
import com.linkedin.openhouse.tables.utils.AllocatedDirectory;
import com.linkedin.openhouse.tables.utils.AuthorizationUtils;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.iceberg.MetadataUpdate;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.SnapshotParser;
import org.apache.iceberg.SnapshotRefParser;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableMetadataParser;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.exceptions.BadRequestException;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.exceptions.CommitStateUnknownException;
import org.apache.iceberg.exceptions.NotFoundException;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.rest.requests.CommitTransactionRequest;
import org.apache.iceberg.rest.requests.CreateTableRequest;
import org.apache.iceberg.rest.requests.RegisterTableRequest;
import org.apache.iceberg.rest.requests.RenameTableRequest;
import org.apache.iceberg.rest.requests.UpdateTableRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * The write half of the Iceberg REST Catalog (IRC) protocol over the existing service layer. Every
 * write reaches the same service-layer rules as the native write it corresponds to:
 *
 * <ul>
 *   <li>A create is spelled as the native create request ({@link IcebergCreates}) and sent through
 *       the native create handler (validation, create rules, audit). The server owns the location:
 *       a location the request names is ignored, and so is a table UUID its properties name
 *       ({@value #TABLE_UUID_KEY}, which the native create would read the table's directory from),
 *       and the table is created, and returned, where the server puts it, as for a request that
 *       names neither.
 *   <li>A staged create runs the same validation and create checks and returns the metadata the
 *       create would store, writing nothing ({@link TablesService#stageCreateTable}).
 *   <li>A commit on an existing table is first authorized on the stored table ({@link #authorize}:
 *       as a load when it has no updates, as the core's write otherwise), then checks its IRC
 *       requirements against the stored table, applies its updates to it with Iceberg's own update
 *       classes, runs the native update validation on the request the OpenHouse client would send
 *       for it ({@link IcebergUpdates}), as the native table PUT and snapshots PUT do, and hands
 *       the result to {@link TableCommitService} with the stored table's version as base, so a
 *       commit that landed in between is refused by the core's guards. A replace commit (see {@link
 *       IcebergRestRequirements#isReplace}) is handed over as a replace; a set-location among its
 *       changes, which is how a client's replace that names a location arrives, is ignored. An
 *       ordinary commit that changes the location is refused.
 *   <li>A transaction ({@code POST /v1/transactions/commit}) with one table change is that commit,
 *       and never a create; with more than one it is refused whole and nothing is written, since
 *       two tables cannot be committed atomically ({@link #commitTransaction}).
 *   <li>A commit that completes a staged create ({@code assert-create}) is spelled as the native
 *       create the OpenHouse Java client sends for a create transaction: the native create when it
 *       carries no snapshot, the native create-with-data (snapshots PUT on the initial version)
 *       when it does. The directory it names must be one the server allocates to that table, must
 *       hold its snapshots, and must not be one a table uses ({@link #refuseADirectoryInUse}): the
 *       server stores nothing for a staged create, so the location is the client's word.
 *   <li>A registration is a native create read from the metadata file the request names, in the
 *       directory the file lies in, which must be one the server allocated to that table and no
 *       table uses ({@link #registerTable}).
 *   <li>Drop and rename are the native drop and rename. A rename across databases, which the native
 *       rename refuses (400), answers what the spec lists for it instead: 404 {@code
 *       NoSuchNamespaceException} when the target namespace does not exist, otherwise 406 (not
 *       supported) with the native message. A rename onto a view's name, which the native route
 *       answers 500 after it has written the renamed metadata, is 409 {@code
 *       AlreadyExistsException} before anything is written.
 *   <li>A policy patch ({@value PolicyPatch#KEY} in the properties of a create, a staged create's
 *       completion or a commit, as OpenHouse's policy statements write it) is what the OpenHouse
 *       Java client makes of it before it sends the native request: the key is taken out of the
 *       properties, the patch merged onto the stored policies ({@link PolicyPatch}), and the result
 *       is the request's policies, which the native validation checks and the commit stores. The
 *       key itself is never stored. A staged create validates the patch and returns the key in the
 *       staged properties, which its completion sends back and applies.
 *   <li>A create that asks, through the other properties the OpenHouse client rewrites into request
 *       fields, for raw policies or a non-primary table type, which the create would otherwise
 *       drop, is refused (400).
 * </ul>
 */
@ConditionalOnIcebergRest
@Component
public class IcebergRestWriteHandler {

  /** The reserved property the native create-with-data reads the table's storage from. */
  private static final String TABLE_LOCATION_KEY = "openhouse.tableLocation";

  /** The stored policies, as a property; the OpenHouse client parses it into request policies. */
  private static final String POLICIES_KEY = "policies";

  /** The format version a create asks for, as a property (Iceberg's {@code format-version}). */
  private static final String FORMAT_VERSION_KEY = "format-version";

  /** The table type, as a property; the OpenHouse client reads it into the request's type. */
  private static final String TABLE_TYPE_KEY = "openhouse.tableType";

  /**
   * The reserved property the native create reads a new table's OpenHouse UUID from, which names
   * the table's directory ({@code <table>-<uuid>}).
   */
  private static final String TABLE_UUID_KEY = CatalogConstants.OPENHOUSE_UUID_KEY;

  /** The two writes that put a table in a directory the request names, as the refusals say them. */
  private static final String CREATE = "create";

  private static final String REGISTER = "register";

  /** How many of a refused transaction's tables its refusal names. */
  private static final int REFUSAL_NAMES = 5;

  @Autowired private TablesApiValidator tablesApiValidator;

  @Autowired private TablesApiHandler tablesApiHandler;

  @Autowired private IcebergSnapshotsApiHandler icebergSnapshotsApiHandler;

  @Autowired private TablesService tablesService;

  @Autowired private TableCommitService tableCommitService;

  @Autowired private PartitionSpecMapper partitionSpecMapper;

  @Autowired private PreservedKeyChecker preservedKeyChecker;

  @Autowired private StorageSelector storageSelector;

  @Autowired private ClusterProperties clusterProperties;

  @Autowired private IcebergRestApiHandler readHandler;

  @Autowired private HouseTableRepository houseTableRepository;

  @Autowired private AuthorizationUtils authorizationUtils;

  @Autowired private FileIOManager fileIOManager;

  @Autowired private IcebergRestTableFileIo tableFileIo;

  @Autowired private PoliciesSpecMapper policiesSpecMapper;

  @Autowired private TableAuditAspect tableAudit;

  // ---------------------------------------------------------------- create

  /** A create ({@code stage-create} false). */
  public IcebergRestLoadedTable createTable(
      String databaseId, CreateTableRequest request, String actingPrincipal) {
    TableIdentifier identifier = TableIdentifier.of(databaseId, request.name());
    CreateUpdateTableRequestBody body = createBody(identifier, request);
    GetTableResponseBody created;
    try {
      created =
          tablesApiHandler
              .createTable(databaseId, withPatchApplied(identifier, body, null), actingPrincipal)
              .getResponseBody();
    } catch (com.linkedin.openhouse.common.exception.AlreadyExistsException e) {
      throw nameTaken(identifier, e);
    }
    return answered(identifier, created.getTableLocation(), created.getTableUUID(), null);
  }

  /**
   * A staged create ({@code stage-create} true): the native create's validation and checks, and the
   * metadata the create would store, with nothing written.
   */
  public TableMetadata stageCreateTable(
      String databaseId, CreateTableRequest request, String actingPrincipal) {
    TableIdentifier identifier = TableIdentifier.of(databaseId, request.name());
    CreateUpdateTableRequestBody body = createBody(identifier, request);
    // The create's validation, policies included. The staged metadata keeps a policy patch as a
    // property (it stores nothing), so the completion, which sends the staged properties back,
    // carries it and applies it.
    tablesApiValidator.validateCreateTable(
        clusterProperties.getClusterName(), databaseId, withPatchApplied(identifier, body, null));
    try {
      return tablesService.stageCreateTable(body, actingPrincipal);
    } catch (com.linkedin.openhouse.common.exception.AlreadyExistsException e) {
      throw nameTaken(identifier, e);
    }
  }

  /**
   * A create, or a staged create, refused by the native create because {@code identifier} is taken:
   * the native 409. When a table holds the name it is worded as Iceberg's catalogs word it ("Table
   * already exists: db.t", {@code BaseMetastoreCatalog}), which is also what the OpenHouse catalog
   * tells an engine; otherwise (a view holds it, or what holds it cannot be read) the native
   * refusal is answered as it is.
   */
  private RuntimeException nameTaken(
      TableIdentifier identifier,
      com.linkedin.openhouse.common.exception.AlreadyExistsException e) {
    boolean table;
    try {
      table =
          !houseTableRepository
              .findViewById(
                  HouseTablePrimaryKey.builder()
                      .databaseId(identifier.namespace().level(0))
                      .tableId(identifier.name())
                      .build())
              .isPresent();
    } catch (RuntimeException lookup) {
      table = false;
    }
    // No cause: the refusal is audited as the native one is, whose cause is none.
    return table ? new AlreadyExistsException("Table already exists: %s", identifier) : e;
  }

  /**
   * {@code body} as the OpenHouse client would send it: a policy patch in its properties taken out
   * and merged onto {@code stored} as the request's policies. Unchanged when there is none.
   */
  private static CreateUpdateTableRequestBody withPatchApplied(
      TableIdentifier identifier, CreateUpdateTableRequestBody body, Policies stored) {
    if (body.getTableProperties() == null
        || !body.getTableProperties().containsKey(PolicyPatch.KEY)) {
      return body;
    }
    Map<String, String> properties = new HashMap<>(body.getTableProperties());
    String patch = properties.remove(PolicyPatch.KEY);
    return body.toBuilder()
        .tableProperties(properties)
        .policies(PolicyPatch.apply(stored, patch, identifier.toString()))
        .build();
  }

  private CreateUpdateTableRequestBody createBody(
      TableIdentifier identifier, CreateTableRequest request) {
    request.validate();
    // request.location() is not read: the server owns the location, and the native create
    // allocates it as for a request that names none.
    Map<String, String> properties = request.properties();
    if (properties != null) {
      refuseRewrittenCreateProperties(identifier, properties);
      refuseOtherFormatVersion(identifier, properties);
      if (properties.containsKey(TABLE_UUID_KEY)) {
        // Nor is a table UUID: the native create takes a new table's UUID, and with it its
        // directory, from this property, and asks only that a file exists there (not even that
        // for a replicated table's create). A table that was renamed keeps the directory of its
        // old name, so a create of the old name with that UUID would be a second table in a
        // live table's directory, whose drop deletes the directory.
        properties = new HashMap<>(properties);
        properties.remove(TABLE_UUID_KEY);
      }
    }
    return IcebergCreates.toCreateBody(
        identifier,
        clusterProperties.getClusterName(),
        request.schema(),
        request.spec(),
        request.writeOrder(),
        properties,
        partitionSpecMapper);
  }

  // ---------------------------------------------------------------- commit

  /** A commit: one that completes a staged create, a replace, or an update of the table. */
  public IcebergRestLoadedTable commitTable(
      String databaseId, String tableId, UpdateTableRequest request, String actingPrincipal) {
    tablesApiValidator.validateGetTable(databaseId, tableId);
    TableIdentifier identifier = TableIdentifier.of(databaseId, tableId);
    if (IcebergRestRequirements.isCreate(request)) {
      return commitCreate(identifier, request, actingPrincipal);
    }
    TableDtoWithMetadata base = tableCommitService.loadBase(identifier);
    authorize(identifier, base, request, actingPrincipal);
    Optional<TableDto> committed = commitOn(identifier, base, request, actingPrincipal);
    if (committed.isPresent()) {
      return answered(
          identifier,
          committed.get().getTableLocation(),
          committed.get().getTableUUID(),
          base.getTable());
    }
    answerAsALoad(identifier, base, request, actingPrincipal);
    return new IcebergRestLoadedTable(
        base.getTableDto().getTableLocation(),
        base.getMetadata(),
        tableFileIo.config(base.getTable()),
        false);
  }

  /**
   * A transaction ({@code POST /v1/transactions/commit}), which the spec has commit its table
   * changes atomically: all of them or none.
   *
   * <p>OpenHouse commits one table per transaction. A table's commit is a compare-and-set of its
   * own House Tables row, and House Tables has no write that changes several rows together, so no
   * two tables can be committed atomically: a failure, or the end of this process, after the first
   * row is set would leave one table changed and the others not, and setting the first row back is
   * a second compare-and-set that a commit landing in between defeats. A transaction with more than
   * one table change is therefore refused whole (400), on its shape alone: before any table is
   * read, any privilege is asked for and anything is written, so the answer is the same whatever
   * the tables are and nothing can be half done. Two changes to one table are refused alike (the
   * spec does not say which of them the second's requirements would be checked against).
   *
   * <p>A transaction with one table change is the ordinary commit of that change ({@link
   * #commitTable}): the same requirements checked against the stored table, the same rules, the
   * same commit core and guards, the same audit. One difference, which is the spec's: the route
   * changes tables that exist and does not create one, so a table that does not exist is a missing
   * table (404) even for a change that asserts it does not exist, which on the table's own commit
   * route completes a staged create. On a table that exists that assertion fails, as it does there.
   * A table in a namespace of more than one level cannot exist (404), as on the other table routes.
   */
  public void commitTransaction(CommitTransactionRequest request, String actingPrincipal) {
    List<UpdateTableRequest> changes = request.tableChanges();
    if (changes.size() != 1) {
      throw new BadRequestException(
          "Cannot commit a transaction with %s table changes (%s): OpenHouse commits one table per"
              + " transaction, because it cannot commit several tables atomically. Nothing was"
              + " committed. Commit each table in a transaction, or a commit, of its own.",
          changes.size(), named(changes));
    }
    UpdateTableRequest change = changes.get(0);
    String tableId = change.identifier().name();
    String databaseId =
        IcebergRestApiHandler.tableDatabaseId(namespace(change.identifier()), tableId);
    tablesApiValidator.validateGetTable(databaseId, tableId);
    TableIdentifier identifier = TableIdentifier.of(databaseId, tableId);
    TableDtoWithMetadata base = tableCommitService.loadBase(identifier);
    authorize(identifier, base, change, actingPrincipal);
    if (!commitOn(identifier, base, change, actingPrincipal).isPresent()) {
      answerAsALoad(identifier, base, change, actingPrincipal);
    }
  }

  /**
   * A commit that changed nothing (no updates, or updates Iceberg's metadata builder records as no
   * change, and no policy patch) wrote nothing and answers the stored table: it is a load, whatever
   * its updates list held. So it passes every check the table load makes, in the load's order
   * ({@link TablesService#checkLoad}: LOCK_ADMIN on a legacy-locked table, the read privilege, the
   * SYSTEM_ONLY access decision, the client-capability gate), and is audited as the load is: a
   * refusal as a FAILED READ, an answer as a READ. A request with no updates passed those checks
   * already, before its requirements were checked ({@link #authorize}).
   */
  private void answerAsALoad(
      TableIdentifier identifier,
      TableDtoWithMetadata base,
      UpdateTableRequest request,
      String principal) {
    String databaseId = identifier.namespace().toString();
    String tableId = identifier.name();
    if (!request.updates().isEmpty()) {
      tableAudit.auditRefusal(
          databaseId,
          tableId,
          OperationType.READ,
          () -> tablesService.checkLoad(base.getTableDto(), principal));
    }
    tableAudit.auditRead(databaseId, tableId, base.getTableDto().getTableLocation());
  }

  /**
   * Decides whether {@code principal} may make {@code request} on the stored table {@code base},
   * before anything of it is checked against {@code base}: its requirements are answered with
   * stored values (the UUID, ref snapshot ids, schema and spec ids), its updates fail on what the
   * table holds, and a request with no updates is answered with the table's metadata. So the only
   * thing a caller learns before this decision is what the native routes tell anyone: whether the
   * table exists (404 first, as the native load and update do).
   *
   * <ul>
   *   <li>A request with no updates writes nothing and answers the stored table: it is a load, and
   *       passes the table load's checks ({@link TablesService#checkLoad}).
   *   <li>Any other request passes the checks the commit core runs on the stored table before it
   *       uses the request, by the core's own method and in the core's order ({@link
   *       TableCommitService#authorizeWrite}): a table with a legacy lock is refused for every
   *       caller, as the native update refuses it before anything else; then the write privilege;
   *       then the SYSTEM_ONLY access decision. The core runs them again, so an allowed caller on
   *       an unlocked table is answered exactly as before. If its updates turn out to change
   *       nothing, it is a load after all, and passes the load's checks before it is answered
   *       ({@link #answerAsALoad}).
   *   <li>Such a request also needs the read privilege the load needs, decided with the others: the
   *       commit answers the table's whole Iceberg metadata (every snapshot with its manifest list,
   *       the logs, the refs, every schema), where the native update answers a summary of the
   *       table; and an Iceberg client loads a table before it commits to it, so a caller that may
   *       not read the table could not commit through one either. The one-table transaction, which
   *       answers nothing, is decided as the commit it is.
   * </ul>
   *
   * A refusal is audited as the refusal of the operation the request would be: a READ for a load,
   * otherwise the commit's kind as the core's audit names it ({@link #auditedKind}).
   */
  private void authorize(
      TableIdentifier identifier,
      TableDtoWithMetadata base,
      UpdateTableRequest request,
      String principal) {
    String databaseId = identifier.namespace().toString();
    String tableId = identifier.name();
    TableDto stored = base.getTableDto();
    if (request.updates().isEmpty()) {
      tableAudit.auditRefusal(
          databaseId,
          tableId,
          OperationType.READ,
          () -> tablesService.checkLoad(stored, principal));
    } else {
      tableAudit.auditRefusal(
          databaseId,
          tableId,
          auditedKind(request),
          () -> {
            tableCommitService.authorizeWrite(identifier, stored, principal, kind(request));
            authorizationUtils.checkTablePrivilege(
                stored, principal, Privileges.GET_TABLE_METADATA);
          });
    }
  }

  /**
   * The kind of the commit {@code request} would be, read from the request, since its updates are
   * not applied before it is authorized: the snapshots route's for a replace or a commit that adds,
   * removes or moves a snapshot, the table PUT's otherwise ({@link #auditedKind}). It picks the
   * wording of a legacy-locked table's refusal.
   */
  private static CommitKind kind(UpdateTableRequest request) {
    return auditedKind(request) == OperationType.UPDATE
        ? CommitKind.TABLE_UPDATE
        : CommitKind.SNAPSHOTS_UPDATE;
  }

  /**
   * The audited kind of a refused commit, read from the request, since its updates are not applied
   * for a caller who may not make it: a replace is a REPLACE_COMMIT, a commit that adds, removes or
   * moves a snapshot is a COMMIT (the native snapshots route's), any other an UPDATE. An allowed
   * commit is audited with the kind its applied changes have, as before.
   */
  private static OperationType auditedKind(UpdateTableRequest request) {
    if (IcebergRestRequirements.isReplace(request)) {
      return OperationType.REPLACE_COMMIT;
    }
    return request.updates().stream().anyMatch(IcebergRestWriteHandler::touchesSnapshots)
        ? OperationType.COMMIT
        : OperationType.UPDATE;
  }

  private static boolean touchesSnapshots(MetadataUpdate update) {
    // Snapshot removal is RemoveSnapshot on one Iceberg line and RemoveSnapshots on the other.
    return update instanceof MetadataUpdate.AddSnapshot
        || update instanceof MetadataUpdate.SetSnapshotRef
        || update instanceof MetadataUpdate.RemoveSnapshotRef
        || update.getClass().getSimpleName().startsWith("RemoveSnapshot");
  }

  /** The tables {@code changes} name, for a refusal: the first few, and how many more. */
  private static String named(List<UpdateTableRequest> changes) {
    String first =
        changes.stream()
            .limit(REFUSAL_NAMES)
            .map(change -> String.valueOf(change.identifier()))
            .collect(Collectors.joining(", "));
    return changes.size() > REFUSAL_NAMES
        ? first + " and " + (changes.size() - REFUSAL_NAMES) + " more"
        : first;
  }

  /**
   * Commits {@code request}, a replace or an update, on the stored table {@code base}: its
   * requirements are checked against {@code base}, its updates applied to it, and the result handed
   * to the commit core with {@code base}'s version, so a commit that landed since {@code base} was
   * loaded is refused by the core's guards.
   *
   * @return the table as the commit stored it; empty for a request that changes nothing, which
   *     writes nothing
   */
  private Optional<TableDto> commitOn(
      TableIdentifier identifier,
      TableDtoWithMetadata base,
      UpdateTableRequest request,
      String actingPrincipal) {
    String databaseId = identifier.namespace().toString();
    String tableId = identifier.name();
    TableMetadata baseMetadata = base.getMetadata();
    IcebergRestRequirements.validate(request, baseMetadata);
    if (request.updates().isEmpty()) {
      return Optional.empty();
    }
    boolean replace = IcebergRestRequirements.isReplace(request);
    // A replace that names a location sends it as a set-location (RESTSessionCatalog builds the
    // replacement at the requested location); the server owns the location, so a replace
    // keeps the table's. An ordinary commit's set-location stays, and the commit refuses it.
    List<MetadataUpdate> updates =
        replace
            ? request.updates().stream()
                .filter(update -> !(update instanceof MetadataUpdate.SetLocation))
                .collect(Collectors.toList())
            : request.updates();
    TableMetadata.Builder builder = TableMetadata.buildFrom(baseMetadata);
    Optional<String> patch;
    try {
      patch = applyTakingOutThePatch(builder, baseMetadata, updates);
    } catch (NotATableUpdate refused) {
      // Audited as the commit's refusal, as the refusals of the early decision are.
      tableAudit.auditRefusal(
          databaseId,
          tableId,
          auditedKind(request),
          () -> {
            throw refused;
          });
      throw refused;
    }
    TableMetadata candidate = builder.build();
    if (candidate.changes().isEmpty() && !patch.isPresent()) {
      return Optional.empty();
    }
    // The policies the OpenHouse client would send: the stored ones, or a patch merged onto them.
    Policies patched =
        patch
            .map(p -> PolicyPatch.apply(base.getTableDto().getPolicies(), p, identifier.toString()))
            .orElse(null);
    CommitKind kind =
        replace || TableCommitService.changesSnapshots(baseMetadata, candidate)
            ? CommitKind.SNAPSHOTS_UPDATE
            : CommitKind.TABLE_UPDATE;
    Policies[] judged = {patched};
    if (kind == CommitKind.TABLE_UPDATE) {
      // The lock rule's refusal is audited as the native PUT's refusal of it is (a FAILED UPDATE).
      tableAudit.auditRefusal(
          databaseId,
          tableId,
          OperationType.UPDATE,
          () -> judged[0] = withTheLockRule(identifier, base.getTableDto(), patched));
    }
    Policies requestedPolicies = judged[0];
    TableDto committed =
        tableCommitService
            .commit(
                identifier,
                base.getTableDto().getTableLocation(),
                base,
                candidate,
                CommitContext.builder()
                    .kind(kind)
                    .principal(actingPrincipal)
                    .clusterId(clusterProperties.getClusterName())
                    .replace(replace)
                    .requestedPolicies(requestedPolicies)
                    // The native update validation, which the native table PUT and snapshots PUT
                    // (replace
                    // included) run before the service layer, on the request the OpenHouse client
                    // would
                    // send for this commit.
                    .validation(
                        () ->
                            tablesApiValidator.validateUpdateTable(
                                clusterProperties.getClusterName(),
                                databaseId,
                                tableId,
                                IcebergUpdates.toUpdateBody(
                                    identifier,
                                    clusterProperties.getClusterName(),
                                    base.getTableDto(),
                                    base.getTableDto().getTableLocation(),
                                    candidate,
                                    replace,
                                    requestedPolicies != null
                                        ? requestedPolicies
                                        : base.getTableDto().getPolicies(),
                                    partitionSpecMapper)))
                    .build())
            .getFirst();
    return Optional.of(committed);
  }

  /**
   * {@code requested}, the policies of a commit of the table PUT's kind, as the table PUT's lock
   * rule (a request may not change the stored lock) judges them on {@code stored}, a table whose
   * stored policies have no lock state: that rule fails on such a table with a NullPointerException
   * (the native PUT's 500, which stays its answer), so here the absent lock state is read as what
   * it means, unlocked. A lock state that locks the table is the rule's refusal; one that leaves it
   * unlocked changes nothing and is dropped, as the rule then has nothing to judge. Anything else
   * is returned as it is, for the rule to judge in the commit core.
   */
  private static Policies withTheLockRule(
      TableIdentifier identifier, TableDto stored, Policies requested) {
    boolean storedHasNoLockState =
        stored.getPolicies() == null || stored.getPolicies().getLockState() == null;
    if (requested == null || requested.getLockState() == null || !storedHasNoLockState) {
      return requested;
    }
    if (requested.getLockState().isLocked()) {
      throw new IllegalArgumentException(
          String.format(
              "Lock state cannot be updated for Table %s.%s",
              identifier.namespace(), identifier.name()));
    }
    return requested.toBuilder().lockState(null).build();
  }

  /**
   * Applies {@code updates} to {@code builder} as the OpenHouse client's commit treats them: the
   * policy patch key is read from the properties the updates leave (a stored key included, as the
   * client reads it from the table's properties) and taken out of them, so it is never stored.
   *
   * @return the patch those properties hold, if any
   */
  private static Optional<String> applyTakingOutThePatch(
      TableMetadata.Builder builder, TableMetadata base, List<MetadataUpdate> updates) {
    String patch = base.properties().get(PolicyPatch.KEY);
    for (MetadataUpdate update : updates) {
      if (update instanceof MetadataUpdate.SetProperties
          && ((MetadataUpdate.SetProperties) update).updated().containsKey(PolicyPatch.KEY)) {
        Map<String, String> set = new HashMap<>(((MetadataUpdate.SetProperties) update).updated());
        patch = set.remove(PolicyPatch.KEY);
        if (!set.isEmpty()) {
          builder.setProperties(set);
        }
      } else if (update instanceof MetadataUpdate.RemoveProperties
          && ((MetadataUpdate.RemoveProperties) update).removed().contains(PolicyPatch.KEY)) {
        patch = null;
        Set<String> removed = new HashSet<>(((MetadataUpdate.RemoveProperties) update).removed());
        removed.remove(PolicyPatch.KEY);
        if (!removed.isEmpty()) {
          builder.removeProperties(removed);
        }
      } else {
        applyTo(builder, update);
      }
    }
    if (base.properties().containsKey(PolicyPatch.KEY)) {
      builder.removeProperties(Collections.singleton(PolicyPatch.KEY));
    }
    return Optional.ofNullable(patch);
  }

  /**
   * Applies {@code update} to table metadata. An update only a view takes (a view version, the
   * current view version) has no meaning for a table, and Iceberg refuses it with {@code
   * UnsupportedOperationException}: that is the request's fault, answered 400 with Iceberg's
   * message, not a server failure.
   */
  private static void applyTo(TableMetadata.Builder builder, MetadataUpdate update) {
    try {
      update.applyTo(builder);
    } catch (UnsupportedOperationException e) {
      throw new NotATableUpdate(e);
    }
  }

  /** An update only a view takes, sent to a table: the request's fault (400). */
  private static final class NotATableUpdate extends BadRequestException {
    NotATableUpdate(UnsupportedOperationException cause) {
      super(cause, "%s", cause.getMessage());
    }
  }

  /**
   * The commit that completes a staged create. Its changes are applied to empty metadata, then
   * spelled as the native create: properties the server itself sets ({@code openhouse.*}, {@code
   * policies}, which the staged response carried and the client sends back) are dropped, as the
   * native create drops them; the location must be one the server allocates, and its table UUID is
   * the created table's OpenHouse UUID, so data the client wrote under the staged location is where
   * the table is.
   *
   * <p>The server keeps nothing of a staged create, so the location is whatever the client sends,
   * and the client need not have staged anything. Two checks keep the create out of a directory
   * that is another table's, as register is kept out of one: every snapshot's manifest list must
   * lie in the directory the completion names (the native create-with-data puts the table where its
   * first snapshot's manifest list is, whatever location is named), and no house-table row, live or
   * soft-deleted, may use that directory ({@link #refuseADirectoryInUse}; a table renamed away
   * keeps the directory of its old name). The rows are looked at only for a principal who may
   * create tables in the database, who is refused first otherwise, as natively.
   *
   * <p>The native create is spelled from the final schema, spec and order, and every rule judges
   * those. When the transaction changed them before it committed, the table keeps the schemas,
   * specs and orders it went through, with the client's ids, which its snapshots and manifests name
   * ({@link CreateHistory}); a create without such history is stored as it always was.
   */
  private IcebergRestLoadedTable commitCreate(
      TableIdentifier identifier, UpdateTableRequest request, String actingPrincipal) {
    String databaseId = identifier.namespace().toString();
    String tableId = identifier.name();
    // On a table that exists the completion's own assertion fails, and is answered first: it says
    // only that the table exists, as the native create's refusal does to anyone. Another
    // requirement, checked against the stored table, would answer its stored values. The assertion
    // needs only that the table exists, so nothing of the table is read for it.
    if (tablesService.tableExists(databaseId, tableId)) {
      IcebergRestRequirements.refuseCreateOfExisting(request);
    }
    IcebergRestRequirements.validate(request, null);
    TableMetadata.Builder builder = TableMetadata.buildFromEmpty();
    for (MetadataUpdate update : request.updates()) {
      applyTo(builder, update);
    }
    TableMetadata candidate = builder.build();
    Optional<String> patch = Optional.ofNullable(candidate.properties().get(PolicyPatch.KEY));
    Policies policies =
        patch.map(p -> PolicyPatch.apply(null, p, identifier.toString())).orElse(null);
    refuseRewrittenCreateProperties(identifier, candidate.properties());
    if (!createsAtFormatV3(candidate.formatVersion())) {
      requireTheClustersFormatVersion("Create", identifier, candidate);
    }
    TableDto ids = TableDto.builder().databaseId(databaseId).tableId(tableId).build();
    UUID tableUUID =
        tableUUID(
            identifier,
            candidate.location(),
            creatableProperties(candidate.properties(), ids),
            actingPrincipal);
    if (candidate.location() != null) {
      Storage storage = storageSelector.selectStorage(databaseId, tableId);
      String directory = withoutEndpoint(storage, candidate.location());
      requireSnapshotsIn(CREATE, identifier, storage, directory, candidate);
      authorizationUtils.checkDatabasePrivilege(
          databaseId, actingPrincipal, Privileges.CREATE_TABLE);
      refuseADirectoryInUse(CREATE, identifier, storage, directory, tableUUID);
    }
    // The schemas, specs and sort orders the transaction went through, with the client's ids,
    // which its snapshots name: the create keeps them. Null when the create stores them
    // anyway.
    String history = CreateHistory.of(candidate);
    return CreateHistoryScope.during(
        history,
        () ->
            create(
                identifier, candidate, candidate.location(), tableUUID, policies, actingPrincipal));
  }

  private void requireTheClustersFormatVersion(
      String operation, TableIdentifier identifier, TableMetadata candidate) {
    if (candidate.formatVersion() != clusterProperties.getClusterIcebergFormatVersion()) {
      throw new RequestValidationFailureException(
          String.format(
              "%s of table %s asks for format version %d: OpenHouse creates tables with "
                  + "format version %d",
              operation,
              identifier,
              candidate.formatVersion(),
              clusterProperties.getClusterIcebergFormatVersion()));
    }
  }

  /** Whether a create that asks for {@code formatVersion} asks for v3 where v3 is enabled. */
  private boolean createsAtFormatV3(int formatVersion) {
    return formatVersion == IcebergV3.VERSION
        && new FormatVersionRule(clusterProperties).v3Enabled();
  }

  /** {@code properties} without the keys the server sets itself, and without a policy patch. */
  private Map<String, String> creatableProperties(Map<String, String> properties, TableDto ids) {
    Map<String, String> creatable = new HashMap<>();
    properties.entrySet().stream()
        .filter(e -> !preservedKeyChecker.isKeyPreservedForTable(e.getKey(), ids))
        .filter(e -> !PolicyPatch.KEY.equals(e.getKey()))
        .forEach(e -> creatable.put(e.getKey(), e.getValue()));
    return creatable;
  }

  /**
   * Creates {@code identifier} as the native create the OpenHouse Java client sends for {@code
   * candidate}: the native create when it carries no snapshot, the native create-with-data
   * (snapshots PUT on the initial version) when it does. Shared by the commit that completes a
   * staged create and by register.
   *
   * @param location the table's location, one the server allocates, {@code null} for a new one
   * @param tableUUID the OpenHouse UUID that location carries
   * @param policies the request's policies, {@code null} for none
   */
  private IcebergRestLoadedTable create(
      TableIdentifier identifier,
      TableMetadata candidate,
      String location,
      UUID tableUUID,
      Policies policies,
      String actingPrincipal) {
    String databaseId = identifier.namespace().toString();
    String tableId = identifier.name();
    TableDto ids = TableDto.builder().databaseId(databaseId).tableId(tableId).build();
    Map<String, String> properties = creatableProperties(candidate.properties(), ids);
    if (createsAtFormatV3(candidate.formatVersion())) {
      // A format version is not among metadata's properties; the native create reads the one a
      // create asks for from this property, as the OpenHouse Java client sends it.
      properties.put(FORMAT_VERSION_KEY, Integer.toString(candidate.formatVersion()));
    }
    CreateUpdateTableRequestBody body =
        IcebergCreates.toCreateBody(
            identifier,
            clusterProperties.getClusterName(),
            candidate.schema(),
            candidate.spec(),
            candidate.sortOrder(),
            properties,
            partitionSpecMapper);
    if (policies != null) {
      body = body.toBuilder().policies(policies).build();
    }
    if (candidate.snapshots().isEmpty()) {
      tablesApiValidator.validateCreateTable(clusterProperties.getClusterName(), databaseId, body);
      TableDto created = tablesService.createTable(body, actingPrincipal, tableUUID).getFirst();
      return answered(identifier, created.getTableLocation(), created.getTableUUID(), null);
    }
    // A create with data: what the OpenHouse Java client sends for a CTAS. The table's UUID comes
    // from where the snapshot's files are, as natively; the three properties below are the ones
    // the native create-with-data reads to find that place.
    Map<String, String> withIds = new HashMap<>(properties);
    withIds.put(CatalogConstants.OPENHOUSE_DATABASEID_KEY, databaseId);
    withIds.put(CatalogConstants.OPENHOUSE_TABLEID_KEY, tableId);
    withIds.put(TABLE_LOCATION_KEY, location);
    GetTableResponseBody created =
        icebergSnapshotsApiHandler
            .putIcebergSnapshots(
                databaseId,
                tableId,
                IcebergSnapshotsRequestBody.builder()
                    .baseTableVersion(INITIAL_TABLE_VERSION)
                    .jsonSnapshots(
                        candidate.snapshots().stream()
                            .map(SnapshotParser::toJson)
                            .collect(Collectors.toList()))
                    .snapshotRefs(
                        candidate.refs().entrySet().stream()
                            .collect(
                                Collectors.toMap(
                                    Map.Entry::getKey,
                                    e -> SnapshotRefParser.toJson(e.getValue()))))
                    .createUpdateTableRequestBody(body.toBuilder().tableProperties(withIds).build())
                    .build(),
                actingPrincipal)
            .getResponseBody();
    return answered(identifier, created.getTableLocation(), created.getTableUUID(), null);
  }

  /**
   * The OpenHouse UUID of a table created at {@code location}: the one the server's allocation for
   * this table put in it, as a staged create returned it. No location: a new UUID. Any other
   * location is refused, since the server owns table locations.
   */
  private UUID tableUUID(
      TableIdentifier identifier,
      String location,
      Map<String, String> properties,
      String actingPrincipal) {
    if (location == null) {
      return UUID.randomUUID();
    }
    String databaseId = identifier.namespace().toString();
    String tableId = identifier.name();
    String directory = location.substring(location.lastIndexOf('/') + 1);
    String prefix = tableId + "-";
    if (directory.startsWith(prefix)) {
      try {
        UUID uuid = UUID.fromString(directory.substring(prefix.length()));
        String allocated =
            storageSelector
                .selectStorage(databaseId, tableId)
                .allocateTableLocation(
                    databaseId, tableId, uuid.toString(), actingPrincipal, properties);
        if (allocated.equals(location)) {
          return uuid;
        }
      } catch (IllegalArgumentException e) {
        // not a UUID: refused below
      }
    }
    throw new RequestValidationFailureException(
        String.format(
            "Create of table %s sets location %s, which is not a location OpenHouse assigned to "
                + "it: OpenHouse assigns the location of a table",
            identifier, location));
  }

  // ---------------------------------------------------------------- register

  /** The suffix of the metadata files OpenHouse writes, which the native drop insists on too. */
  private static final String METADATA_FILE_SUFFIX = ".metadata.json";

  private static final int SOFT_DELETED_PAGE_SIZE = 100;

  /**
   * Registers {@code databaseId.<name>} from the metadata file the request names.
   *
   * <p>The native API has no register, and a house-table row cannot simply be pointed at a file:
   * the file's reserved properties (ids, UUID, creator, type, policies) are what the service
   * authorizes and governs the table by, and nothing has validated them. So a registration is a
   * native create, read from the file instead of sent: the file's current schema, partition spec,
   * sort order, properties, policies, snapshots and refs are handed to the native create (the
   * native create-with-data when there are snapshots), in the directory the file lies in, exactly
   * as the commit that completes a staged create hands over what the client built. Every create
   * rule and validation applies, the server writes the table's metadata and its house-table row as
   * for any created table, and the caller becomes its creator.
   *
   * <p>What makes that safe is that register reaches no further than the native create already
   * does. A principal who may create tables in a database can natively create a table named {@code
   * t} in a directory OpenHouse allocated to a table {@code t} of that database, with snapshots
   * that lie there (the completion of a staged create; the server cannot tell who wrote the files).
   * Register accepts the same directories and no others, and is stricter in three ways:
   *
   * <ol>
   *   <li>The file must lie directly in the directory OpenHouse allocates to a table of that name
   *       in that database (the server owns locations), so the storage is only read at a place the
   *       caller could have been handed, and after the create privilege was checked.
   *   <li>No house-table row, live or soft-deleted, may point into that directory or carry its
   *       UUID. A table renamed away keeps its directory, and a soft-deleted table can be restored
   *       into its own. Two registrations of one directory are for one name, so the house-table key
   *       lets one win.
   *   <li>The metadata's own {@code location} must be that directory, and every snapshot's manifest
   *       list must lie in it: a file planted there cannot attach another table.
   * </ol>
   *
   * <p>Metadata a create cannot store as it is refused rather than stored as something else (as a
   * commit refuses a change the update path never stores): another format version, a schema or
   * partition spec history, field ids a create would renumber, statistics files, a replica table.
   * Not kept, as for any create: the Iceberg table UUID, the metadata log and the snapshot log.
   *
   * <p>A name that is taken is 409, whatever the request's {@code overwrite} flag (Iceberg 1.12):
   * OpenHouse does not replace a table's metadata by registration.
   */
  public IcebergRestLoadedTable registerTable(
      String databaseId, RegisterTableRequest request, String actingPrincipal) {
    String tableId = request.name();
    tablesApiValidator.validateGetTable(databaseId, tableId);
    TableIdentifier identifier = TableIdentifier.of(databaseId, tableId);
    HouseTablePrimaryKey key =
        HouseTablePrimaryKey.builder().databaseId(databaseId).tableId(tableId).build();
    // The native create's order: the name (409), then the privilege (403).
    Optional<HouseTable> occupant = houseTableRepository.findEntityById(key);
    if (occupant.isPresent()) {
      throw new AlreadyExistsException(
          "%s already exists: %s",
          CatalogConstants.ENTITY_TYPE_VIEW.equalsIgnoreCase(occupant.get().getEntityType())
              ? "View"
              : "Table",
          identifier);
    }
    authorizationUtils.checkDatabasePrivilege(databaseId, actingPrincipal, Privileges.CREATE_TABLE);

    Storage storage = storageSelector.selectStorage(databaseId, tableId);
    String metadataLocation = request.metadataLocation();
    UUID tableUUID = allocatedUUID(identifier, storage, metadataLocation, actingPrincipal);
    String location =
        storage.allocateTableLocation(
            databaseId, tableId, tableUUID.toString(), actingPrincipal, new HashMap<>());
    String directory = withoutEndpoint(storage, location);
    refuseADirectoryInUse(REGISTER, identifier, storage, directory, tableUUID);

    TableMetadata metadata = readRegistered(identifier, storage, metadataLocation);
    if (!directory.equals(withoutEndpoint(storage, metadata.location()))) {
      // The file's own location is not told: it may be a table the caller cannot see.
      throw refusedRegistration(
          identifier,
          "the location in the metadata at %s is not the directory it lies in",
          metadataLocation);
    }
    refuseWhatACreateCannotStore(identifier, storage, directory, metadata);

    String stored = metadata.properties().get(POLICIES_KEY);
    Policies policies;
    try {
      policies =
          stored == null || stored.trim().isEmpty()
              ? null
              : policiesSpecMapper.toPoliciesObject(stored);
    } catch (RuntimeException e) {
      throw refusedRegistration(identifier, "its policies cannot be read");
    }
    String patch = metadata.properties().get(PolicyPatch.KEY);
    if (patch != null) {
      policies = PolicyPatch.apply(policies, patch, identifier.toString());
    }
    return create(identifier, metadata, location, tableUUID, policies, actingPrincipal);
  }

  /**
   * The OpenHouse UUID in {@code metadataLocation}, if that is a metadata file directly in the
   * directory the server allocates to this table with that UUID; refused otherwise, before anything
   * is read.
   */
  private UUID allocatedUUID(
      TableIdentifier identifier,
      Storage storage,
      String metadataLocation,
      String actingPrincipal) {
    String path = withoutEndpoint(storage, metadataLocation);
    int fileAt = path.lastIndexOf('/');
    if (fileAt > 0 && path.endsWith(METADATA_FILE_SUFFIX)) {
      String directory = path.substring(0, fileAt);
      String directoryName = directory.substring(directory.lastIndexOf('/') + 1);
      String prefix = identifier.name() + "-";
      if (directoryName.startsWith(prefix)) {
        try {
          UUID uuid = UUID.fromString(directoryName.substring(prefix.length()));
          String allocated =
              storage.allocateTableLocation(
                  identifier.namespace().toString(),
                  identifier.name(),
                  uuid.toString(),
                  actingPrincipal,
                  new HashMap<>());
          if (directory.equals(withoutEndpoint(storage, allocated))) {
            return uuid;
          }
        } catch (IllegalArgumentException e) {
          // not a UUID: refused below
        }
      }
    }
    throw refusedRegistration(
        identifier,
        "%s is not a metadata file in a directory OpenHouse assigned to a table of that name in "
            + "that database: OpenHouse assigns the location of a table",
        metadataLocation);
  }

  /** {@code location} as the house-table store spells it: without the storage's endpoint. */
  private static String withoutEndpoint(Storage storage, String location) {
    String endpoint = storage.getClient().getEndpoint();
    return endpoint != null && !endpoint.isEmpty() && location.startsWith(endpoint)
        ? location.substring(endpoint.length())
        : location;
  }

  /**
   * Refuses, for a register or the commit that completes a staged create ({@code operation}), a
   * directory a house-table row, live or soft-deleted, points into, or whose UUID is the one the
   * directory of a row's location is named for ({@link #namesUuid}). A table's drop deletes its
   * directory, so a second table there would take the first one's files with it when it is dropped;
   * and a directory is not free because no table has its name: a table renamed away keeps the
   * directory of its old name, and a soft-deleted table can be restored into its own.
   */
  private void refuseADirectoryInUse(
      String operation,
      TableIdentifier identifier,
      Storage storage,
      String directory,
      UUID tableUUID) {
    String databaseId = identifier.namespace().toString();
    for (HouseTable row : houseTableRepository.findAllByDatabaseId(databaseId)) {
      if (uses(row, storage, directory, tableUUID)) {
        throw refused(
            operation,
            identifier,
            "that directory belongs to %s.%s",
            row.getDatabaseId(),
            row.getTableId());
      }
    }
    for (int page = 0; ; page++) {
      List<HouseTable> rows =
          houseTableRepository
              .searchSoftDeletedTables(
                  databaseId, null, PageRequest.of(page, SOFT_DELETED_PAGE_SIZE))
              .getContent();
      for (HouseTable row : rows) {
        if (uses(row, storage, directory, tableUUID)) {
          throw refused(
              operation,
              identifier,
              "that directory belongs to the soft-deleted table %s.%s, which can be restored",
              row.getDatabaseId(),
              row.getTableId());
        }
      }
      if (rows.size() < SOFT_DELETED_PAGE_SIZE) {
        return;
      }
    }
  }

  private static boolean uses(HouseTable row, Storage storage, String directory, UUID tableUUID) {
    String location = row.getTableLocation();
    if (location == null) {
      return false;
    }
    String stored = withoutEndpoint(storage, location);
    return stored.startsWith(directory + "/") || namesUuid(stored, tableUUID);
  }

  /**
   * Whether the directory a metadata file lies in is named for {@code uuid} ({@link
   * AllocatedDirectory}): the UUID a house-table row's entity carries, which House Tables does not
   * keep. A name that ends in a UUID other than this one, or in none, is not this UUID's: the check
   * can miss a directory but never refuses one wrongly.
   */
  static boolean namesUuid(String metadataLocation, UUID uuid) {
    return AllocatedDirectory.uuidOf(metadataLocation).map(uuid::equals).orElse(false);
  }

  /** Reads the metadata file; a failure is told without the file's content. */
  private TableMetadata readRegistered(
      TableIdentifier identifier, Storage storage, String metadataLocation) {
    FileIO io = fileIOManager.getFileIO(storage.getType());
    try {
      return TableMetadataParser.read(io, metadataLocation);
    } catch (NotFoundException e) {
      throw refusedRegistration(identifier, "there is no file at %s", metadataLocation);
    } catch (RuntimeException e) {
      throw refusedRegistration(
          identifier, "the file at %s is not table metadata this server reads", metadataLocation);
    }
  }

  /** Refuses metadata whose current state a native create would store as something else. */
  private void refuseWhatACreateCannotStore(
      TableIdentifier identifier, Storage storage, String directory, TableMetadata metadata) {
    requireTheClustersFormatVersion("Registration", identifier, metadata);
    if (metadata.schemas().size() != 1 || metadata.currentSchemaId() != 0) {
      throw refusedRegistration(
          identifier,
          "its metadata holds a schema history (%d schemas, current schema id %d), which a created "
              + "table does not have",
          metadata.schemas().size(),
          metadata.currentSchemaId());
    }
    if (metadata.specs().size() != 1 || metadata.defaultSpecId() != 0) {
      throw refusedRegistration(
          identifier,
          "its metadata holds a partition spec history (%d specs, default spec id %d), which a "
              + "created table does not have",
          metadata.specs().size(),
          metadata.defaultSpecId());
    }
    TableMetadata created =
        TableMetadata.newTableMetadata(
            metadata.schema(),
            metadata.spec(),
            metadata.sortOrder(),
            "candidate",
            Collections.emptyMap());
    if (!created.schema().sameSchema(metadata.schema())
        || !created.spec().fields().equals(metadata.spec().fields())
        || !created.sortOrder().sameOrder(metadata.sortOrder())) {
      throw refusedRegistration(
          identifier,
          "its schema, partition spec or sort order uses field ids a create would assign "
              + "differently, and its data files are written with them");
    }
    if (!metadata.statisticsFiles().isEmpty() || !metadata.partitionStatisticsFiles().isEmpty()) {
      throw refusedRegistration(
          identifier, "its metadata holds statistics files, which a create does not store");
    }
    String tableType = metadata.properties().get(TABLE_TYPE_KEY);
    if (tableType != null && !TableType.PRIMARY_TABLE.name().equals(tableType)) {
      throw refusedRegistration(
          identifier,
          "its metadata is a %s's: the routes create %s tables",
          tableType,
          TableType.PRIMARY_TABLE.name());
    }
    requireSnapshotsIn(REGISTER, identifier, storage, directory, metadata);
  }

  /**
   * Refuses, for a register or the commit that completes a staged create ({@code operation}),
   * metadata with a snapshot whose manifest list does not lie in {@code directory}, the table's
   * own: such a snapshot attaches another table's data, and the native create-with-data takes the
   * table's UUID, and so its directory, from where its first snapshot's manifest list is.
   */
  private static void requireSnapshotsIn(
      String operation,
      TableIdentifier identifier,
      Storage storage,
      String directory,
      TableMetadata metadata) {
    for (Snapshot snapshot : metadata.snapshots()) {
      String manifestList = snapshot.manifestListLocation();
      if (manifestList == null
          || !withoutEndpoint(storage, manifestList).startsWith(directory + "/")
          || manifestList.contains("/../")) {
        throw refused(
            operation,
            identifier,
            "the manifest list of snapshot %d does not lie in the table's directory",
            snapshot.snapshotId());
      }
    }
  }

  private static RequestValidationFailureException refusedRegistration(
      TableIdentifier identifier, String reason, Object... args) {
    return refused(REGISTER, identifier, reason, args);
  }

  /** The refusal of a register or of a staged create's completion, in one wording for both. */
  private static RequestValidationFailureException refused(
      String operation, TableIdentifier identifier, String reason, Object... args) {
    return new RequestValidationFailureException(
        String.format(
            "Cannot %s table %s: %s", operation, identifier, String.format(reason, args)));
  }

  // ---------------------------------------------------------------- drop and rename

  /** The native drop. {@code purgeRequested} changes nothing: the native drop always purges. */
  public void dropTable(String databaseId, String tableId, String actingPrincipal) {
    tablesApiHandler.deleteTable(databaseId, tableId, actingPrincipal);
  }

  /**
   * The native rename, with its rules (same database, not a case-only change, target absent). A
   * rename across databases, which the native rename refuses with 400 before it looks at the
   * target, is answered as the spec lists for renameTable: 404 {@code NoSuchNamespaceException}
   * when the target namespace does not exist, and otherwise 406, the spec's "the server does not
   * support this operation", with the native refusal's message. The ids are checked first, as
   * natively.
   *
   * <p>Tables and views share House Tables' key, and the native rename looks only for a table of
   * the target's name, so onto a view's name it writes the renamed metadata, retries, and fails on
   * the House Tables write (answered 500). Here the target's key is looked up first, where the
   * native rename looks for a table there (after the request's validation and the source's
   * existence), and a view there is the spec's 409 {@code AlreadyExistsException}, with nothing
   * written. A House Tables refusal during the rename itself is 409 too: "already exists" when the
   * target's key is now taken, otherwise {@code CommitFailedException}, a conflict a client may
   * retry.
   */
  public void renameTable(RenameTableRequest request, String actingPrincipal) {
    request.validate();
    // The source is a table (none exists in a multi-level namespace: 404), the destination's
    // namespace is where it goes (a multi-level one is malformed: 400), as on the other routes.
    String fromDatabaseId =
        IcebergRestApiHandler.tableDatabaseId(namespace(request.source()), request.source().name());
    String toDatabaseId = IcebergRestApiHandler.databaseId(namespace(request.destination()));
    if (!fromDatabaseId.equalsIgnoreCase(toDatabaseId)) {
      tablesApiValidator.validateGetTable(fromDatabaseId, request.source().name());
      tablesApiValidator.validateGetTable(toDatabaseId, request.destination().name());
      readHandler.requireNamespace(toDatabaseId);
      try {
        tablesApiValidator.validateRenameTable(
            fromDatabaseId, request.source().name(), toDatabaseId, request.destination().name());
      } catch (RequestValidationFailureException e) {
        throw new IcebergRestRouteException(e.getMessage(), true);
      }
    }
    HouseTablePrimaryKey target =
        HouseTablePrimaryKey.builder()
            .databaseId(toDatabaseId)
            .tableId(request.destination().name())
            .build();
    // Audited as the native rename audits its refusal (a FAILED RENAME_FROM of the source).
    tableAudit.auditRefusal(
        fromDatabaseId,
        request.source().name(),
        OperationType.RENAME_FROM,
        () -> {
          tablesApiValidator.validateRenameTable(
              fromDatabaseId, request.source().name(), toDatabaseId, request.destination().name());
          if (houseTableRepository.findViewById(target).isPresent()
              && tablesService.tableExists(fromDatabaseId, request.source().name())) {
            throw new AlreadyExistsException(
                "Cannot rename %s to %s. View already exists",
                request.source(), request.destination());
          }
        });
    try {
      tablesApiHandler.renameTable(
          fromDatabaseId,
          request.source().name(),
          toDatabaseId,
          request.destination().name(),
          actingPrincipal);
    } catch (com.linkedin.openhouse.common.exception.AlreadyExistsException e) {
      // The native rename found a table of the target's name (a view there was refused above):
      // its 409, worded as Iceberg's catalogs word it, as the refusals here are. No cause, as the
      // native refusal has none (the audit records it).
      throw new AlreadyExistsException(
          "Cannot rename %s to %s. Table already exists", request.source(), request.destination());
    } catch (CommitFailedException e) {
      if (!(e.getCause() instanceof HouseTableConcurrentUpdateException)) {
        throw e;
      }
      // House Tables refused the rename's write. The native API answers the unmapped commit
      // failure 500; the spec's answer is a 409: the target's key taken meanwhile (a view, or a
      // table created since the native rename looked), or a concurrent change of the table.
      boolean view = houseTableRepository.findViewById(target).isPresent();
      if (view || tablesService.tableExists(toDatabaseId, request.destination().name())) {
        throw new AlreadyExistsException(
            e,
            "Cannot rename %s to %s. %s already exists",
            request.source(),
            request.destination(),
            view ? "View" : "Table");
      }
      throw new CommitFailedException(
          e,
          "Cannot rename %s to %s: the table was changed concurrently",
          request.source(),
          request.destination());
    }
  }

  private static String namespace(TableIdentifier identifier) {
    return String.join("\u001f", identifier.namespace().levels());
  }

  // ---------------------------------------------------------------- rewritten create properties

  /**
   * Refuses a create's properties that the OpenHouse Java client would have turned into request
   * fields and that the create would otherwise drop: a non-empty raw {@code policies} (the client
   * parses it into the request's policies) and a table type other than the primary one ({@code
   * openhouse.tableType}, which the client reads into the request's table type). The values the
   * server sets itself, which a staged create returns and its completion sends back, are accepted.
   * (A policy patch, {@value PolicyPatch#KEY}, is applied, not refused.)
   */
  private static void refuseRewrittenCreateProperties(
      TableIdentifier identifier, Map<String, String> properties) {
    String policies = properties.get(POLICIES_KEY);
    if (policies != null && !policies.trim().isEmpty()) {
      throw new RequestValidationFailureException(
          String.format(
              "Create of table %s sets the table property %s: policies cannot be set as a raw "
                  + "property through the Iceberg REST routes; set them with a policy patch in %s, "
                  + "as OpenHouse's policy statements do",
              identifier, POLICIES_KEY, PolicyPatch.KEY));
    }
    String tableType = properties.get(TABLE_TYPE_KEY);
    if (tableType != null && !TableType.PRIMARY_TABLE.name().equals(tableType)) {
      throw new RequestValidationFailureException(
          String.format(
              "Create of table %s sets the table property %s to %s: the table type cannot be set "
                  + "through the Iceberg REST routes; they create %s tables",
              identifier, TABLE_TYPE_KEY, tableType, TableType.PRIMARY_TABLE.name()));
    }
  }

  /**
   * Refuses a create or staged create whose {@code format-version} property asks for a version the
   * cluster does not create, as the commit that completes a staged create refuses one (the server
   * owns the format version). The native create overwrites the property with the cluster's version,
   * which here would answer, or stage, other metadata than was asked for.
   */
  private void refuseOtherFormatVersion(
      TableIdentifier identifier, Map<String, String> properties) {
    String asked = properties.get(FORMAT_VERSION_KEY);
    int clusterVersion = clusterProperties.getClusterIcebergFormatVersion();
    if (asked != null
        && asked.trim().equals(Integer.toString(IcebergV3.VERSION))
        && new FormatVersionRule(clusterProperties).v3Enabled()) {
      // Format v3 where the cluster enables it: the create's own rule stores it.
      return;
    }
    if (asked != null && !asked.trim().equals(Integer.toString(clusterVersion))) {
      throw new RequestValidationFailureException(
          String.format(
              "Create of table %s sets the table property %s to %s: OpenHouse creates tables "
                  + "with format version %d",
              identifier, FORMAT_VERSION_KEY, asked, clusterVersion));
    }
  }

  /**
   * The answer to a write that stored the table at {@code committed} (its metadata file, as the
   * native API reports it): that version of the table, and never a later one, or another table of
   * the same name, that the write's decisions were not made on. The table is read back as stored
   * now, which is that version unless another write landed after this one; then the version this
   * write stored is read from its own metadata file.
   *
   * <p>That file can be gone: a later commit's metadata cleanup deletes old files, and a drop
   * deletes the table's directory. The write landed all the same, so it is not answered as a
   * failure. When the table stored now is the same table (the same UUID), it is answered: the later
   * version, which already holds this write. When it is another table of the name, nothing that
   * this write's decisions were made on can be answered, and the answer is the commit's state
   * unknown ({@link CommitStateUnknownException}, the spec's 500 for an unknown commit state),
   * which a client reads as "the commit may have landed" and does not clean up after.
   *
   * @param committedUuid the OpenHouse UUID of the table the write stored
   * @param written the table the write was made on, whose FileIO reads that file; {@code null} for
   *     a create, which reads it with the table's own
   */
  private IcebergRestLoadedTable answered(
      TableIdentifier identifier, String committed, String committedUuid, Table written) {
    TableDtoWithMetadata stored = tableCommitService.loadBase(identifier);
    if (stored.getTableDto().getTableLocation().equals(committed)) {
      return new IcebergRestLoadedTable(
          committed, stored.getMetadata(), tableFileIo.config(stored.getTable()), false);
    }
    Table table = written != null ? written : stored.getTable();
    TableMetadata metadata;
    try {
      metadata = TableMetadataParser.read(table.io(), committed);
    } catch (RuntimeException unreadable) {
      if (committedUuid != null && committedUuid.equals(stored.getTableDto().getTableUUID())) {
        return new IcebergRestLoadedTable(
            stored.getTableDto().getTableLocation(),
            stored.getMetadata(),
            tableFileIo.config(stored.getTable()),
            false);
      }
      throw new CommitStateUnknownException(
          String.format(
              "The write to %s landed, but the version it stored (%s) can no longer be read, and"
                  + " the table of that name is now another table",
              identifier, committed),
          unreadable);
    }
    return new IcebergRestLoadedTable(committed, metadata, tableFileIo.config(table), false);
  }

  /**
   * The per-table config of a staged create's response: the FileIO class of the storage the create
   * would place the table on ({@link IcebergRestTableFileIo}), which the client writes the table's
   * data with before the commit that completes the create.
   */
  public Map<String, String> stagedTableConfig(String databaseId, String tableId) {
    return tableFileIo.config(storageSelector.selectStorage(databaseId, tableId).getType());
  }
}
