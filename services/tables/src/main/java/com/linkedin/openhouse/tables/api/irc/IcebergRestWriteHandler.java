package com.linkedin.openhouse.tables.api.irc;

import static com.linkedin.openhouse.common.api.validator.ValidatorConstants.INITIAL_TABLE_VERSION;

import com.linkedin.openhouse.cluster.configs.ClusterProperties;
import com.linkedin.openhouse.cluster.storage.selector.StorageSelector;
import com.linkedin.openhouse.common.exception.RequestValidationFailureException;
import com.linkedin.openhouse.internal.catalog.CatalogConstants;
import com.linkedin.openhouse.tables.api.handler.IcebergSnapshotsApiHandler;
import com.linkedin.openhouse.tables.api.handler.TablesApiHandler;
import com.linkedin.openhouse.tables.api.spec.v0.request.CreateUpdateTableRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.request.IcebergSnapshotsRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.Policies;
import com.linkedin.openhouse.tables.api.validator.TablesApiValidator;
import com.linkedin.openhouse.tables.commit.CommitContext;
import com.linkedin.openhouse.tables.commit.CommitKind;
import com.linkedin.openhouse.tables.common.TableType;
import com.linkedin.openhouse.tables.dto.mapper.iceberg.PartitionSpecMapper;
import com.linkedin.openhouse.tables.model.TableDto;
import com.linkedin.openhouse.tables.model.TableDtoWithMetadata;
import com.linkedin.openhouse.tables.repository.PreservedKeyChecker;
import com.linkedin.openhouse.tables.services.IcebergCreates;
import com.linkedin.openhouse.tables.services.IcebergUpdates;
import com.linkedin.openhouse.tables.services.PolicyPatch;
import com.linkedin.openhouse.tables.services.TableCommitService;
import com.linkedin.openhouse.tables.services.TablesService;
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
import org.apache.iceberg.SnapshotParser;
import org.apache.iceberg.SnapshotRefParser;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.requests.CreateTableRequest;
import org.apache.iceberg.rest.requests.RenameTableRequest;
import org.apache.iceberg.rest.requests.UpdateTableRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;

/**
 * The write half of the Iceberg REST Catalog (IRC) protocol over the existing service layer. Every
 * write reaches the same service-layer rules as the native write it corresponds to:
 *
 * <ul>
 *   <li>A create is spelled as the native create request ({@link IcebergCreates}) and sent through
 *       the native create handler (validation, create rules, audit). The server owns the location
 *       (D30): a location the request names is ignored, and the table is created, and returned,
 *       where the server puts it, as for a request that names none.
 *   <li>A staged create runs the same validation and create checks and returns the metadata the
 *       create would store, writing nothing ({@link TablesService#stageCreateTable}).
 *   <li>A commit on an existing table checks its IRC requirements against the stored table, applies
 *       its updates to it with Iceberg's own update classes, runs the native update validation on
 *       the request the OpenHouse client would send for it ({@link IcebergUpdates}), as the native
 *       table PUT and snapshots PUT do, and hands the result to {@link TableCommitService} with the
 *       stored table's version as base, so a commit that landed in between is refused by the core's
 *       guards. A replace commit (see {@link IcebergRestRequirements#isReplace}) is handed over as
 *       a replace; a set-location among its changes, which is how a client's replace that names a
 *       location arrives, is ignored (D30). An ordinary commit that changes the location is
 *       refused.
 *   <li>A commit that completes a staged create ({@code assert-create}) is spelled as the native
 *       create the OpenHouse Java client sends for a create transaction: the native create when it
 *       carries no snapshot, the native create-with-data (snapshots PUT on the initial version)
 *       when it does.
 *   <li>Drop and rename are the native drop and rename.
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
@Component
@ConditionalOnClass(name = IcebergRestWriteController.WRITE_API_CLASS)
public class IcebergRestWriteHandler {

  /** The reserved property the native create-with-data reads the table's storage from. */
  private static final String TABLE_LOCATION_KEY = "openhouse.tableLocation";

  /** The stored policies, as a property; the OpenHouse client parses it into request policies. */
  private static final String POLICIES_KEY = "policies";

  /** The table type, as a property; the OpenHouse client reads it into the request's type. */
  private static final String TABLE_TYPE_KEY = "openhouse.tableType";

  @Autowired private TablesApiValidator tablesApiValidator;

  @Autowired private TablesApiHandler tablesApiHandler;

  @Autowired private IcebergSnapshotsApiHandler icebergSnapshotsApiHandler;

  @Autowired private TablesService tablesService;

  @Autowired private TableCommitService tableCommitService;

  @Autowired private PartitionSpecMapper partitionSpecMapper;

  @Autowired private PreservedKeyChecker preservedKeyChecker;

  @Autowired private StorageSelector storageSelector;

  @Autowired private ClusterProperties clusterProperties;

  // ---------------------------------------------------------------- create

  /** A create ({@code stage-create} false). */
  public IcebergRestLoadedTable createTable(
      String databaseId, CreateTableRequest request, String actingPrincipal) {
    TableIdentifier identifier = TableIdentifier.of(databaseId, request.name());
    CreateUpdateTableRequestBody body = createBody(identifier, request);
    tablesApiHandler.createTable(
        databaseId, withPatchApplied(identifier, body, null), actingPrincipal);
    return loaded(identifier);
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
    return tablesService.stageCreateTable(body, actingPrincipal);
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
    // request.location() is not read: the server owns the location (D30), and the native create
    // allocates it as for a request that names none.
    if (request.properties() != null) {
      refuseRewrittenCreateProperties(identifier, request.properties());
    }
    return IcebergCreates.toCreateBody(
        identifier,
        clusterProperties.getClusterName(),
        request.schema(),
        request.spec(),
        request.writeOrder(),
        request.properties(),
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
    TableMetadata baseMetadata = base.getMetadata();
    IcebergRestRequirements.validate(request, baseMetadata);
    if (request.updates().isEmpty()) {
      return new IcebergRestLoadedTable(
          base.getTableDto().getTableLocation(), baseMetadata, Collections.emptyMap(), false);
    }
    boolean replace = IcebergRestRequirements.isReplace(request);
    // A replace that names a location sends it as a set-location (RESTSessionCatalog builds the
    // replacement at the requested location); the server owns the location (D30), so a replace
    // keeps the table's. An ordinary commit's set-location stays, and the commit refuses it.
    List<MetadataUpdate> updates =
        replace
            ? request.updates().stream()
                .filter(update -> !(update instanceof MetadataUpdate.SetLocation))
                .collect(Collectors.toList())
            : request.updates();
    TableMetadata.Builder builder = TableMetadata.buildFrom(baseMetadata);
    Optional<String> patch = applyTakingOutThePatch(builder, baseMetadata, updates);
    TableMetadata candidate = builder.build();
    if (candidate.changes().isEmpty() && !patch.isPresent()) {
      return new IcebergRestLoadedTable(
          base.getTableDto().getTableLocation(), baseMetadata, Collections.emptyMap(), false);
    }
    // The policies the OpenHouse client would send: the stored ones, or a patch merged onto them.
    Policies requestedPolicies =
        patch
            .map(p -> PolicyPatch.apply(base.getTableDto().getPolicies(), p, identifier.toString()))
            .orElse(null);
    CommitKind kind =
        replace || TableCommitService.changesSnapshots(baseMetadata, candidate)
            ? CommitKind.SNAPSHOTS_UPDATE
            : CommitKind.TABLE_UPDATE;
    tableCommitService.commit(
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
            // The native update validation, which the native table PUT and snapshots PUT (replace
            // included) run before the service layer, on the request the OpenHouse client would
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
            .build());
    return loaded(identifier);
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
        update.applyTo(builder);
      }
    }
    if (base.properties().containsKey(PolicyPatch.KEY)) {
      builder.removeProperties(Collections.singleton(PolicyPatch.KEY));
    }
    return Optional.ofNullable(patch);
  }

  /**
   * The commit that completes a staged create. Its changes are applied to empty metadata, then
   * spelled as the native create: properties the server itself sets ({@code openhouse.*}, {@code
   * policies}, which the staged response carried and the client sends back) are dropped, as the
   * native create drops them; the location must be one the server allocates, and its table UUID is
   * the created table's OpenHouse UUID, so data the client wrote under the staged location is where
   * the table is.
   */
  private IcebergRestLoadedTable commitCreate(
      TableIdentifier identifier, UpdateTableRequest request, String actingPrincipal) {
    String databaseId = identifier.namespace().toString();
    String tableId = identifier.name();
    Optional<TableMetadata> existing =
        tablesService.tableExists(databaseId, tableId)
            ? Optional.of(tableCommitService.loadBase(identifier).getMetadata())
            : Optional.empty();
    IcebergRestRequirements.validate(request, existing.orElse(null));
    TableMetadata.Builder builder = TableMetadata.buildFromEmpty();
    for (MetadataUpdate update : request.updates()) {
      update.applyTo(builder);
    }
    TableMetadata candidate = builder.build();
    Optional<String> patch = Optional.ofNullable(candidate.properties().get(PolicyPatch.KEY));
    Policies policies =
        patch.map(p -> PolicyPatch.apply(null, p, identifier.toString())).orElse(null);
    refuseRewrittenCreateProperties(identifier, candidate.properties());
    if (candidate.formatVersion() != clusterProperties.getClusterIcebergFormatVersion()) {
      throw new RequestValidationFailureException(
          String.format(
              "Create of table %s asks for format version %d: OpenHouse creates tables with "
                  + "format version %d",
              identifier,
              candidate.formatVersion(),
              clusterProperties.getClusterIcebergFormatVersion()));
    }
    TableDto ids = TableDto.builder().databaseId(databaseId).tableId(tableId).build();
    Map<String, String> properties = new HashMap<>();
    candidate.properties().entrySet().stream()
        .filter(e -> !preservedKeyChecker.isKeyPreservedForTable(e.getKey(), ids))
        .filter(e -> !PolicyPatch.KEY.equals(e.getKey()))
        .forEach(e -> properties.put(e.getKey(), e.getValue()));
    UUID tableUUID = tableUUID(identifier, candidate.location(), properties, actingPrincipal);
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
      tablesService.createTable(body, actingPrincipal, tableUUID);
      return loaded(identifier);
    }
    // A create with data: what the OpenHouse Java client sends for a CTAS. The table's UUID comes
    // from where the snapshot's files are, as natively; the three properties below are the ones
    // the native create-with-data reads to find that place.
    Map<String, String> withIds = new HashMap<>(properties);
    withIds.put(CatalogConstants.OPENHOUSE_DATABASEID_KEY, databaseId);
    withIds.put(CatalogConstants.OPENHOUSE_TABLEID_KEY, tableId);
    withIds.put(TABLE_LOCATION_KEY, candidate.location());
    icebergSnapshotsApiHandler.putIcebergSnapshots(
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
                            Map.Entry::getKey, e -> SnapshotRefParser.toJson(e.getValue()))))
            .createUpdateTableRequestBody(body.toBuilder().tableProperties(withIds).build())
            .build(),
        actingPrincipal);
    return loaded(identifier);
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

  // ---------------------------------------------------------------- drop and rename

  /** The native drop. {@code purgeRequested} changes nothing: the native drop always purges. */
  public void dropTable(String databaseId, String tableId, String actingPrincipal) {
    tablesApiHandler.deleteTable(databaseId, tableId, actingPrincipal);
  }

  /** The native rename, with its rules (same database, not a case-only change, target absent). */
  public void renameTable(RenameTableRequest request, String actingPrincipal) {
    request.validate();
    // The source is a table (none exists in a multi-level namespace: 404), the destination's
    // namespace is where it goes (a multi-level one is malformed: 400), as on the other routes.
    String fromDatabaseId =
        IcebergRestApiHandler.tableDatabaseId(namespace(request.source()), request.source().name());
    String toDatabaseId = IcebergRestApiHandler.databaseId(namespace(request.destination()));
    tablesApiHandler.renameTable(
        fromDatabaseId,
        request.source().name(),
        toDatabaseId,
        request.destination().name(),
        actingPrincipal);
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

  /** The table as stored now, at the location the native API reports. */
  private IcebergRestLoadedTable loaded(TableIdentifier identifier) {
    TableDtoWithMetadata stored = tableCommitService.loadBase(identifier);
    return new IcebergRestLoadedTable(
        stored.getTableDto().getTableLocation(),
        stored.getMetadata(),
        Collections.emptyMap(),
        false);
  }
}
