package com.linkedin.openhouse.tables.api.handler.impl;

import static com.linkedin.openhouse.internal.catalog.mapper.HouseTableSerdeUtils.getCanonicalFieldName;

import com.linkedin.openhouse.cluster.configs.ClusterProperties;
import com.linkedin.openhouse.common.exception.NoSuchUserTableException;
import com.linkedin.openhouse.internal.catalog.CatalogConstants;
import com.linkedin.openhouse.internal.catalog.OpenHouseInternalCatalog;
import com.linkedin.openhouse.internal.catalog.commit.MetadataUpdateApplier;
import com.linkedin.openhouse.tables.api.handler.TablesApiHandler;
import com.linkedin.openhouse.tables.api.spec.v0.request.CreateUpdateTableRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.request.IcebergSnapshotsRequestBody;
import com.linkedin.openhouse.tables.common.TableType;
import com.linkedin.openhouse.tables.dto.mapper.iceberg.PartitionSpecMapper;
import com.linkedin.openhouse.tables.model.TableDto;
import com.linkedin.openhouse.tables.services.IcebergSnapshotsService;
import com.linkedin.openhouse.tables.services.NamespacesService;
import com.linkedin.openhouse.tables.services.TablesService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.iceberg.BaseTable;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.SchemaParser;
import org.apache.iceberg.SnapshotParser;
import org.apache.iceberg.SnapshotRefParser;
import org.apache.iceberg.SortOrder;
import org.apache.iceberg.SortOrderParser;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.UpdateRequirement;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.exceptions.NoSuchNamespaceException;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.rest.CatalogHandlers;
import org.apache.iceberg.rest.requests.CreateTableRequest;
import org.apache.iceberg.rest.requests.UpdateTableRequest;
import org.apache.iceberg.rest.responses.LoadTableResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Turns the Iceberg REST write operations into OpenHouse table writes.
 *
 * <p><b>The shape of the translation.</b> An Iceberg REST commit is a change list: preconditions
 * ({@link UpdateRequirement}) plus changes ({@code MetadataUpdate}). OpenHouse's write path takes a
 * whole document. The shared commit engine -- {@link MetadataUpdateApplier} and, through it, {@code
 * UpdateRequirementValidator} -- is what converts one into the other: requirements are checked
 * against the base this adapter just loaded, the updates are applied to that base, and the
 * resulting {@link TableMetadata} <em>is</em> the document that {@link TablesService} and {@link
 * IcebergSnapshotsService} already know how to write. That is deliberately the same engine the
 * whole-document {@code /v1} path runs through, so there is one implementation of the update
 * semantics rather than two.
 *
 * <p><b>Why the service layer and not the catalog.</b> Everything that makes an OpenHouse table
 * more than an Iceberg table lives above the catalog: authorization, policies, preserved property
 * keys, lock state, table UUID allocation, storage selection, and namespace registration. Going
 * straight to {@link OpenHouseInternalCatalog} would mean reimplementing all of it for REST
 * clients, and getting one of them wrong would mean REST callers quietly bypassing a control that
 * {@code /v1} callers cannot. So the REST routes are another caller of the same services, and the
 * split between metadata-only and snapshot-bearing commits mirrors the split the OpenHouse Java
 * client already makes in {@code OpenHouseTableOperations#doCommit}.
 *
 * <p><b>Conflict detection.</b> The commits this adapter issues declare no whole-document base
 * version of their own; they pass {@code icebergRestCommit = true} instead, which is what makes the
 * two client-declared-base checks in {@code OpenHouseInternalTableOperations} dormant on this path.
 * See {@link CatalogConstants#IS_REST_COMMIT_KEY}. The durable linearization point is unchanged:
 * House Tables still compares the metadata location and still holds the JPA version.
 */
@Component
@ConditionalOnProperty(value = "cluster.tables.iceberg-rest.enabled", havingValue = "true")
public class IcebergRestTableWriteAdapter {

  private final TablesService tablesService;
  private final TablesApiHandler tablesApiHandler;
  private final IcebergSnapshotsService icebergSnapshotsService;
  private final NamespacesService namespacesService;
  private final OpenHouseInternalCatalog openHouseInternalCatalog;
  private final PartitionSpecMapper partitionSpecMapper;
  private final ClusterProperties clusterProperties;

  public IcebergRestTableWriteAdapter(
      TablesService tablesService,
      TablesApiHandler tablesApiHandler,
      IcebergSnapshotsService icebergSnapshotsService,
      NamespacesService namespacesService,
      OpenHouseInternalCatalog openHouseInternalCatalog,
      PartitionSpecMapper partitionSpecMapper,
      ClusterProperties clusterProperties) {
    this.tablesService = tablesService;
    this.tablesApiHandler = tablesApiHandler;
    this.icebergSnapshotsService = icebergSnapshotsService;
    this.namespacesService = namespacesService;
    this.openHouseInternalCatalog = openHouseInternalCatalog;
    this.partitionSpecMapper = partitionSpecMapper;
    this.clusterProperties = clusterProperties;
  }

  /**
   * Serves {@code POST /v1/{prefix}/namespaces/{namespace}/tables}.
   *
   * <p>A stock Iceberg client sends none of the three fields OpenHouse's own API demands -- {@code
   * clusterId}, {@code baseTableVersion}, {@code tableType} -- because the REST specification has
   * no place to put them. All three are defaulted here: the serving cluster, the "this table does
   * not exist yet" sentinel, and {@code PRIMARY_TABLE}.
   *
   * <p>The namespace is checked for existence first. {@code putTable} would otherwise create the
   * database as a side effect of the write (that is how a database comes into being on the {@code
   * /v1} path), but the REST specification requires 404 for a create into a namespace that does not
   * exist, and answering 200 would leave a stock client unable to tell a typo from a success.
   * Registration of the namespace on a successful create is still {@code putTable}'s job and is not
   * duplicated here.
   */
  public LoadTableResponse createTable(
      Namespace namespace, CreateTableRequest request, String principal) {
    request.validate();
    String databaseId = namespace.level(0);
    // The namespace's own name has already been judged by the route (IcebergRestIdentifiers), so
    // what remains is whether it exists.
    if (!namespacesService.namespaceExists(namespace, principal)) {
      throw new NoSuchNamespaceException("Namespace does not exist: %s", namespace);
    }

    CreateUpdateTableRequestBody body =
        requestBody(
            databaseId,
            request.name(),
            request.schema(),
            request.spec() == null ? PartitionSpec.unpartitioned() : request.spec(),
            request.writeOrder(),
            request.properties() == null ? new LinkedHashMap<>() : request.properties(),
            /*baseTableVersion*/ CatalogConstants.INITIAL_VERSION,
            /*stageCreate*/ request.stageCreate());

    TableDto saved;
    try {
      saved =
          tablesService
              .putTable(body, principal, /*failOnExist*/ true, /*icebergRestCommit*/ true)
              .getFirst();
    } catch (com.linkedin.openhouse.common.exception.AlreadyExistsException e) {
      // Same condition, the specification's wording. A stock client reads this message, and
      // OpenHouse's own phrasing ("Table db.tbl already exists") is not the one the REST contract
      // and its conformance suite name.
      throw new AlreadyExistsException("Table already exists: %s.%s", databaseId, request.name());
    }

    TableIdentifier identifier = TableIdentifier.of(namespace, request.name());
    if (request.stageCreate()) {
      // A staged create writes a metadata.json and deliberately does not register the table, so the
      // catalog cannot load it -- the metadata has to be read back from where the commit put it.
      // The client already knows this table is not committed, because it asked for a transaction;
      // it will send the whole change list to the commit route to finish the create.
      return LoadTableResponse.builder()
          .withTableMetadata(
              openHouseInternalCatalog.loadMetadataAt(identifier, saved.getTableLocation()))
          .build();
    }
    return CatalogHandlers.loadTable(openHouseInternalCatalog, identifier);
  }

  /**
   * Serves {@code POST /v1/{prefix}/namespaces/{namespace}/tables/{table}} -- the specification's
   * {@code commitTable}.
   *
   * <p>Requirements are checked and updates applied by the shared engine; the metadata that comes
   * out is written through the service layer. A commit against a table that does not exist is a 404
   * unless the request says it is completing a staged create, which the specification expresses as
   * the {@code assert-create} requirement.
   */
  public LoadTableResponse updateTable(
      Namespace namespace, String tableId, UpdateTableRequest request, String principal) {
    String databaseId = namespace.level(0);
    TableIdentifier identifier = TableIdentifier.of(namespace, tableId);

    TableMetadata base = loadBase(databaseId, tableId, principal);
    boolean completesStagedCreate = assertsTableDoesNotExist(request.requirements());
    if (base == null && !completesStagedCreate) {
      throw new NoSuchTableException("Table does not exist: %s.%s", databaseId, tableId);
    }
    if (base != null && completesStagedCreate) {
      // The specification's assert-create failing means someone else created the table while this
      // transaction was open. That is "already exists", not a generic precondition failure, and the
      // difference is what tells a client to stop rather than refresh and retry.
      throw new AlreadyExistsException("Table already exists: %s.%s", databaseId, tableId);
    }

    TableMetadata updated =
        MetadataUpdateApplier.validateAndApply(base, request.requirements(), request.updates());

    CreateUpdateTableRequestBody body =
        requestBody(
            databaseId,
            tableId,
            updated.schema(),
            updated.spec(),
            updated.sortOrder(),
            updated.properties(),
            base == null ? CatalogConstants.INITIAL_VERSION : base.metadataFileLocation(),
            /*stageCreate*/ false);

    if (snapshotsChanged(base, updated)) {
      icebergSnapshotsService.putIcebergSnapshots(
          databaseId,
          tableId,
          snapshotsRequestBody(body, updated),
          principal,
          /*icebergRestCommit*/ true);
    } else {
      tablesService.putTable(body, principal, /*failOnExist*/ false, /*icebergRestCommit*/ true);
    }
    return CatalogHandlers.loadTable(openHouseInternalCatalog, identifier);
  }

  /**
   * Serves {@code POST /v1/{prefix}/tables/rename}.
   *
   * <p><b>Why this one route goes through the API handler.</b> Create and commit assemble a request
   * body the {@code /v1} API has no way to express, so they call the services directly. Rename does
   * not: it is the same four identifiers on both APIs, and its rules -- including the one that
   * matters most here -- live in {@link
   * com.linkedin.openhouse.tables.api.validator.TablesApiValidator#validateRenameTable}. Calling
   * {@link TablesApiHandler#renameTable} runs that validator, so a REST caller is held to exactly
   * the rules an OpenHouse caller is, and the rules stay written down once. It also puts the rename
   * back under {@code TableAuditAspect}, whose pointcut is on {@code TablesApiHandler.renameTable}:
   * a REST rename emits the same RENAME_FROM/RENAME_TO audit pair a {@code /v1} rename does, which
   * a call straight to the service would have skipped silently.
   *
   * <p><b>Renames across databases.</b> OpenHouse declines them: the validator refuses a rename
   * whose target database differs from its source, and this route does not widen that. The REST
   * specification explicitly permits a server to refuse ("it's valid to move a table across
   * namespaces, but the server implementation is not required to support it"), so the refusal is
   * conforming. It arrives as a 400 carrying the validator's own sentence rather than a 406, which
   * is the status the specification reserves for the refusal, because the refusal is not this
   * facade's to reclassify -- it is the service's answer, reported faithfully.
   *
   * <p><b>What this route does add.</b> The destination namespace is checked for existence first.
   * The specification requires 404 for a rename into a namespace that does not exist, and without
   * that check a cross-database rename into a namespace that was never created would be reported as
   * the cross-database refusal -- telling a client its request was unsupported when in truth it had
   * a typo. Ordering the checks this way is also what makes the two answers distinguishable at all:
   * "there is no such namespace" is a fact about the catalog, "renames do not cross databases" is a
   * fact about OpenHouse.
   */
  public void renameTable(TableIdentifier source, TableIdentifier destination, String principal) {
    if (!namespacesService.namespaceExists(destination.namespace(), principal)) {
      throw new NoSuchNamespaceException("Namespace does not exist: %s", destination.namespace());
    }
    try {
      tablesApiHandler.renameTable(
          source.namespace().level(0),
          source.name(),
          destination.namespace().level(0),
          destination.name(),
          principal);
    } catch (NoSuchUserTableException e) {
      // Same condition, the specification's wording -- see createTable above for why the phrasing
      // has to be the contract's rather than OpenHouse's. The table named is taken from the
      // exception rather than assumed to be the source, so that if a rename ever fails on some
      // other table the message says which one; the cause is carried so the descent survives.
      throw new NoSuchTableException(
          e, "Table does not exist: %s.%s", e.getDatabaseId(), e.getTableId());
    } catch (com.linkedin.openhouse.common.exception.AlreadyExistsException e) {
      throw new AlreadyExistsException(
          e, "Table already exists: %s.%s", destination.namespace().level(0), destination.name());
    }
  }

  /**
   * Serves {@code DELETE /v1/{prefix}/namespaces/{namespace}/tables/{table}}.
   *
   * <p>OpenHouse has exactly one drop, and it purges. {@code TablesService.deleteTable} removes the
   * House Tables row outright -- not into the soft-deleted table, which only a caller asking for a
   * soft delete reaches, and which nothing on this path does -- and then deletes every file under
   * the table's own location, which is also why {@code restoreTable} cannot bring a REST-dropped
   * table back.
   *
   * <p>So {@code purgeRequested=true} is honoured exactly: data and metadata go. {@code
   * purgeRequested=false} is <em>not</em>: the specification's unpurged drop deregisters the table
   * and leaves its files alone, and this facade cannot offer that because the underlying drop takes
   * no such option. The parameter is accepted and has no effect. The conformance suite's {@code
   * testDropTableWithoutPurge} passes regardless, because the data file it checks for lives outside
   * the table's location and so is not what OpenHouse's purge deletes -- it does not exercise the
   * divergence, and should not be read as evidence the divergence is absent.
   *
   * @param purgeRequested carried this far so the decision to ignore it lives with the drop it
   *     would have modified, rather than being dropped silently at the route
   */
  public void dropTable(
      Namespace namespace, String tableId, Boolean purgeRequested, String principal) {
    String databaseId = namespace.level(0);
    try {
      tablesService.deleteTable(databaseId, tableId, principal);
    } catch (NoSuchUserTableException e) {
      throw new NoSuchTableException("Table does not exist: %s.%s", databaseId, tableId);
    }
  }

  /**
   * The table's current metadata, or {@code null} when it does not exist.
   *
   * <p>Existence is asked of {@link TablesService}, not of the catalog, so that the read is
   * authorized before any of the table's state -- including whether a precondition on it holds --
   * can be inferred from the answer.
   */
  private TableMetadata loadBase(String databaseId, String tableId, String principal) {
    try {
      tablesService.getTable(databaseId, tableId, principal);
    } catch (NoSuchUserTableException e) {
      return null;
    }
    return ((BaseTable) openHouseInternalCatalog.loadTable(TableIdentifier.of(databaseId, tableId)))
        .operations()
        .current();
  }

  /** {@code assert-create}: the requirement a staged-create transaction commits with. */
  private static boolean assertsTableDoesNotExist(List<UpdateRequirement> requirements) {
    return requirements != null
        && requirements.stream()
            .anyMatch(r -> r instanceof UpdateRequirement.AssertTableDoesNotExist);
  }

  /**
   * Mirrors {@code OpenHouseTableOperations#areSnapshotsUpdated}: OpenHouse routes a
   * snapshot-bearing commit through a different service than a metadata-only one, and the REST
   * facade has to make the same call from the same evidence.
   */
  private static boolean snapshotsChanged(TableMetadata base, TableMetadata updated) {
    if (base == null) {
      return !updated.snapshots().isEmpty();
    }
    return !base.snapshots().equals(updated.snapshots()) || !base.refs().equals(updated.refs());
  }

  private CreateUpdateTableRequestBody requestBody(
      String databaseId,
      String tableId,
      Schema schema,
      PartitionSpec spec,
      SortOrder sortOrder,
      Map<String, String> properties,
      String baseTableVersion,
      boolean stageCreate) {
    return CreateUpdateTableRequestBody.builder()
        .tableId(tableId)
        .databaseId(databaseId)
        .clusterId(clusterProperties.getClusterName())
        .schema(SchemaParser.toJson(schema))
        .timePartitioning(partitionSpecMapper.toTimePartitionSpec(schema, spec))
        .clustering(partitionSpecMapper.toClusteringSpec(schema, spec))
        .tableProperties(IcebergRestPolicyMerger.withoutPatchKey(properties))
        .policies(IcebergRestPolicyMerger.merge(properties))
        .sortOrder(sortOrder == null ? null : SortOrderParser.toJson(sortOrder))
        .baseTableVersion(baseTableVersion)
        .tableType(tableType(properties))
        .stageCreate(stageCreate)
        .build();
  }

  /**
   * The REST specification has no field for a table type, so the only place one can come from is
   * the property OpenHouse itself stamps on the table. A table whose properties do not name one is
   * a primary table, which is also what a stock client creating a table means.
   */
  private static TableType tableType(Map<String, String> properties) {
    String declared =
        properties == null ? null : properties.get(getCanonicalFieldName("tableType"));
    return declared == null ? TableType.PRIMARY_TABLE : TableType.valueOf(declared);
  }

  private static IcebergSnapshotsRequestBody snapshotsRequestBody(
      CreateUpdateTableRequestBody body, TableMetadata updated) {
    return IcebergSnapshotsRequestBody.builder()
        .baseTableVersion(body.getBaseTableVersion())
        .jsonSnapshots(
            updated.snapshots().stream().map(SnapshotParser::toJson).collect(Collectors.toList()))
        .snapshotRefs(
            updated.refs().entrySet().stream()
                .collect(
                    Collectors.toMap(
                        Map.Entry::getKey, e -> SnapshotRefParser.toJson(e.getValue()))))
        .createUpdateTableRequestBody(body)
        .build();
  }
}
