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
import com.linkedin.openhouse.tables.api.validator.TablesApiValidator;
import com.linkedin.openhouse.tables.commit.CommitContext;
import com.linkedin.openhouse.tables.commit.CommitKind;
import com.linkedin.openhouse.tables.dto.mapper.iceberg.PartitionSpecMapper;
import com.linkedin.openhouse.tables.model.TableDto;
import com.linkedin.openhouse.tables.model.TableDtoWithMetadata;
import com.linkedin.openhouse.tables.repository.PreservedKeyChecker;
import com.linkedin.openhouse.tables.services.IcebergCreates;
import com.linkedin.openhouse.tables.services.TableCommitService;
import com.linkedin.openhouse.tables.services.TablesService;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.iceberg.MetadataUpdate;
import org.apache.iceberg.SnapshotParser;
import org.apache.iceberg.SnapshotRefParser;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.UpdateRequirement;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.requests.CreateTableRequest;
import org.apache.iceberg.rest.requests.RenameTableRequest;
import org.apache.iceberg.rest.requests.UpdateTableRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The write half of the Iceberg REST Catalog (IRC) protocol over the existing service layer. Every
 * write reaches the same service-layer rules as the native write it corresponds to:
 *
 * <ul>
 *   <li>A create is spelled as the native create request ({@link IcebergCreates}) and sent through
 *       the native create handler (validation, create rules, audit). The server owns the location:
 *       a request that names one is refused.
 *   <li>A staged create runs the same validation and create checks and returns the metadata the
 *       create would store, writing nothing ({@link TablesService#stageCreateTable}).
 *   <li>A commit on an existing table checks its IRC requirements against the stored table, applies
 *       its updates to it with Iceberg's own update classes, and hands the result to {@link
 *       TableCommitService} with the stored table's version as base, so a commit that landed in
 *       between is refused by the core's guards. A replace commit (see {@link #isReplace}) is
 *       handed over as a replace.
 *   <li>A commit that completes a staged create ({@code assert-create}) is spelled as the native
 *       create the OpenHouse Java client sends for a create transaction: the native create when it
 *       carries no snapshot, the native create-with-data (snapshots PUT on the initial version)
 *       when it does.
 *   <li>Drop and rename are the native drop and rename.
 * </ul>
 */
@Component
public class IcebergRestWriteHandler {

  /** The reserved property the native create-with-data reads the table's storage from. */
  private static final String TABLE_LOCATION_KEY = "openhouse.tableLocation";

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
    tablesApiHandler.createTable(databaseId, createBody(identifier, request), actingPrincipal);
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
    tablesApiValidator.validateCreateTable(clusterProperties.getClusterName(), databaseId, body);
    return tablesService.stageCreateTable(body, actingPrincipal);
  }

  private CreateUpdateTableRequestBody createBody(
      TableIdentifier identifier, CreateTableRequest request) {
    request.validate();
    if (request.location() != null) {
      throw new RequestValidationFailureException(
          String.format(
              "Create of table %s names a location (%s): OpenHouse assigns the location of a "
                  + "table; leave location unset",
              identifier, request.location()));
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
    if (isCreate(request)) {
      return commitCreate(identifier, request, actingPrincipal);
    }
    TableDtoWithMetadata base = tableCommitService.loadBase(identifier);
    TableMetadata baseMetadata = base.getMetadata();
    for (UpdateRequirement requirement : request.requirements()) {
      requirement.validate(baseMetadata);
    }
    TableMetadata.Builder builder = TableMetadata.buildFrom(baseMetadata);
    for (MetadataUpdate update : request.updates()) {
      update.applyTo(builder);
    }
    TableMetadata candidate = builder.build();
    if (candidate.changes().isEmpty()) {
      return new IcebergRestLoadedTable(
          base.getTableDto().getTableLocation(), baseMetadata, Collections.emptyMap(), false);
    }
    boolean replace = isReplace(request);
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
            .build());
    return loaded(identifier);
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
    for (UpdateRequirement requirement : request.requirements()) {
      requirement.validate(existing.orElse(null));
    }
    TableMetadata.Builder builder = TableMetadata.buildFromEmpty();
    for (MetadataUpdate update : request.updates()) {
      update.applyTo(builder);
    }
    TableMetadata candidate = builder.build();
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

  // ---------------------------------------------------------------- the commit's shape

  /**
   * A commit that completes a staged create: it requires that the table does not exist ({@code
   * assert-create}), which only a create transaction asserts.
   */
  static boolean isCreate(UpdateTableRequest request) {
    return request.requirements().stream()
        .anyMatch(UpdateRequirement.AssertTableDoesNotExist.class::isInstance);
  }

  /**
   * A replace commit, as Iceberg's {@code RESTTableOperations} sends one (1.5 and 1.12 alike). The
   * client sends no replace intent; the shape reveals it. A replace always sets the current schema,
   * the default spec and the default sort order ({@code RESTSessionCatalog.replaceTransaction} adds
   * any of the three the replacement lacks), and {@code UpdateRequirements.forReplaceTable} never
   * asserts the current schema, default spec or default sort order. An ordinary commit that sets
   * any of the three always asserts the matching id ({@code UpdateRequirements.forUpdateTable}), so
   * Iceberg's own clients cannot send an ordinary commit of this shape. The same rule as the
   * in-process seam's adapter ({@code OpenHouseRestAdapter.isReplace}).
   */
  static boolean isReplace(UpdateTableRequest request) {
    if (isCreate(request)) {
      return false;
    }
    List<MetadataUpdate> updates = request.updates();
    List<UpdateRequirement> requirements = request.requirements();
    return updates.stream().anyMatch(MetadataUpdate.SetCurrentSchema.class::isInstance)
        && updates.stream().anyMatch(MetadataUpdate.SetDefaultPartitionSpec.class::isInstance)
        && updates.stream().anyMatch(MetadataUpdate.SetDefaultSortOrder.class::isInstance)
        && requirements.stream()
            .noneMatch(
                requirement ->
                    requirement instanceof UpdateRequirement.AssertCurrentSchemaID
                        || requirement instanceof UpdateRequirement.AssertDefaultSpecID
                        || requirement instanceof UpdateRequirement.AssertDefaultSortOrderID);
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
