package com.linkedin.openhouse.tables.api.irc;

import static com.linkedin.openhouse.common.security.AuthenticationUtils.extractAuthenticatedUserPrincipal;

import com.linkedin.openhouse.capabilities.CapabilityNames;
import com.linkedin.openhouse.cluster.storage.StorageManager;
import com.linkedin.openhouse.cluster.storage.StorageType;
import com.linkedin.openhouse.common.exception.NoSuchUserTableException;
import com.linkedin.openhouse.common.exception.checked.NotFoundException;
import com.linkedin.openhouse.internal.catalog.model.NamespaceMetadata;
import com.linkedin.openhouse.tables.api.validator.TablesApiValidator;
import com.linkedin.openhouse.tables.authorization.Privileges;
import com.linkedin.openhouse.tables.model.DatabaseDto;
import com.linkedin.openhouse.tables.model.TableDto;
import com.linkedin.openhouse.tables.model.TableDtoPrimaryKey;
import com.linkedin.openhouse.tables.model.TableDtoWithMetadata;
import com.linkedin.openhouse.tables.readbridge.ReadBridgeConfigResolver;
import com.linkedin.openhouse.tables.repository.OpenHouseInternalRepository;
import com.linkedin.openhouse.tables.services.DatabasesService;
import com.linkedin.openhouse.tables.services.NamespacesService;
import com.linkedin.openhouse.tables.services.TablesService;
import com.linkedin.openhouse.tables.utils.AuthorizationUtils;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.BadRequestException;
import org.apache.iceberg.exceptions.NoSuchNamespaceException;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.metrics.CommitReport;
import org.apache.iceberg.metrics.CommitReportParser;
import org.apache.iceberg.metrics.MetricsReport;
import org.apache.iceberg.metrics.ScanReport;
import org.apache.iceberg.metrics.ScanReportParser;
import org.apache.iceberg.rest.requests.ReportMetricsRequest;
import org.apache.iceberg.rest.responses.ConfigResponse;
import org.apache.iceberg.rest.responses.GetNamespaceResponse;
import org.apache.iceberg.rest.responses.ListNamespacesResponse;
import org.apache.iceberg.rest.responses.ListTablesResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.AuthorizationServiceException;
import org.springframework.stereotype.Component;

/**
 * The read half of the Iceberg REST Catalog (IRC) protocol over the existing service layer. Each
 * route answers with what the native API answers for the same question, through the same service
 * calls and checks:
 *
 * <ul>
 *   <li>A namespace is an OpenHouse database, one level only. It exists while it holds a table or a
 *       view (the native database list shows it then), or while it has a stored namespace record
 *       ({@link NamespacesService}), which may exist with no table. Loading a namespace answers the
 *       record's spelling and properties where there is a record the caller may read, and an empty
 *       property map otherwise.
 *   <li>Listing namespaces is the native database list; listing tables is the native table search.
 *       Neither makes an authorization decision of its own. Reading a namespace's record (on load,
 *       and on a miss of the existence checks) makes the namespace service's read decision for it,
 *       whose refusal does not change the answer, and a failure to read the record or decide on it
 *       does not change the answer for a namespace that holds a table or a view.
 *   <li>Table existence is the native existence check: a house-table lookup without loading the
 *       metadata, with no authorization decision and no capability check.
 *   <li>Loading a table is {@link TablesService#loadTable}: the native load with the same checks in
 *       the same order (lock, privilege, lock access, client capability), from one load of the
 *       table. The response carries the stored metadata unchanged, the metadata location the native
 *       API reports, and the per-table config the native API gives the same client.
 *   <li>A metrics report is counted and logged ({@link #reportMetrics}); the native API has no
 *       route for one.
 * </ul>
 */
@Slf4j
@Component
public class IcebergRestApiHandler {

  @Autowired private TablesApiValidator tablesApiValidator;

  @Autowired private TablesService tablesService;

  @Autowired private DatabasesService databasesService;

  @Autowired private ReadBridgeConfigResolver readBridgeConfigResolver;

  @Autowired private IcebergRestServedRoutes servedRoutes;

  @Autowired private OpenHouseInternalRepository openHouseInternalRepository;

  @Autowired private AuthorizationUtils authorizationUtils;

  @Autowired private MeterRegistry meterRegistry;

  @Autowired private StorageManager storageManager;

  @Autowired private IcebergRestTableFileIo tableFileIo;

  @Autowired private NamespacesService namespacesService;

  /** Values of the {@code snapshots} query parameter of a table load. */
  static final String SNAPSHOTS_ALL = "all";

  static final String SNAPSHOTS_REFS = "refs";

  /**
   * Defaults a client applies under its own configuration, and the server's capabilities as an
   * override.
   *
   * <ul>
   *   <li>{@code rest-metrics-reporting-enabled=false}: the metrics route is served ({@link
   *       #reportMetrics}) and listed, but a client reports only if its own configuration says so.
   *       Left to itself {@code RESTCatalog} posts a report after every scan and every commit, each
   *       costing this service a house-table lookup and an authorization decision to increment a
   *       counter; an Iceberg 1.5.2 client posts them inline, on the planning and commit path; and
   *       a create with data (CTAS) posts its commit report before the table exists, which is
   *       answered 404 and logged by the client as a warning with a stack trace.
   *   <li>{@code io-impl}: the FileIO for the cluster's default storage ({@link #clientFileIo}).
   *       RESTCatalog's own default (ResolvingFileIO) cannot write delete files on this Iceberg
   *       line, which opens them with write properties ResolvingFileIO rejects.
   *   <li>{@value #CAPABILITIES}: the OpenHouse features the routes registered here serve,
   *       comma-separated ({@link IcebergRestCapability}): {@code policies}, a policy patch ({@code
   *       updated.openhouse.policy}, as OpenHouse's policy statements write it) in a create or
   *       commit is applied as the OpenHouse client applies it (with the write routes); {@code
   *       grants} and {@code unlock}, the OpenHouse routes under the base path. An override, so
   *       that the client's merged catalog properties ({@code RESTCatalog.properties()}) say what
   *       this server serves whatever the client was configured with. Not {@link #endpoints()}
   *       entries: those name the spec's routes.
   * </ul>
   */
  public ConfigResponse config() {
    ConfigResponse.Builder config =
        ConfigResponse.builder().withDefault(REST_METRICS_REPORTING_ENABLED, "false");
    clientFileIo(storageManager.getDefaultStorage().getType())
        .ifPresent(fileIo -> config.withDefault(CatalogProperties.FILE_IO_IMPL, fileIo));
    return config.withOverride(CAPABILITIES, String.join(",", servedRoutes.capabilities())).build();
  }

  /**
   * The FileIO an unmodified Iceberg client should read and write a cluster's tables with, by the
   * cluster's default storage type: the class this service itself uses for that storage ({@code
   * FileIOConfig}), each of which is Iceberg's own and needs no OpenHouse class at the client.
   *
   * <ul>
   *   <li>hdfs, local: {@code HadoopFileIO}, which is also what the OpenHouse Java client uses when
   *       its catalog is given no {@code io-impl}.
   *   <li>s3: {@code S3FileIO} (iceberg-aws).
   *   <li>adls: {@code ADLSFileIO} (iceberg-azure), the class OpenHouse's set-up guide has an
   *       engine configure for the OpenHouse catalog on ADLS.
   * </ul>
   *
   * <p>Only the class is advertised. Where the store is and how to authenticate to it stay the
   * client's own configuration, as on the native path: nothing from the cluster's storage
   * parameters is sent. A storage type not listed here gets no {@code io-impl}, and the client
   * keeps its own default, rather than a class that cannot read the cluster's tables. A table the
   * storage selector places on another storage type than the default is given its own storage's
   * class in its responses ({@link IcebergRestTableFileIo}).
   */
  static Optional<String> clientFileIo(StorageType.Type defaultStorageType) {
    if (StorageType.HDFS.equals(defaultStorageType)
        || StorageType.LOCAL.equals(defaultStorageType)) {
      return Optional.of(HADOOP_FILE_IO);
    }
    if (StorageType.S3.equals(defaultStorageType)) {
      return Optional.of(S3_FILE_IO);
    }
    if (StorageType.ADLS.equals(defaultStorageType)) {
      return Optional.of(ADLS_FILE_IO);
    }
    return Optional.empty();
  }

  /**
   * The config response's {@code endpoints}: the spec routes registered here ({@link
   * IcebergRestServedRoutes}). An Iceberg 1.12 client uses only these once a list is sent (with
   * none it assumes a default set this server does not match); a 1.5.2 client ignores the field.
   */
  public List<String> endpoints() {
    return servedRoutes.endpoints();
  }

  /** The config key that names the OpenHouse features a server serves (server capabilities). */
  public static final String CAPABILITIES = CapabilityNames.ADVERTISED_PROPERTY;

  /** RESTCatalog's switch for posting metrics reports (private in RESTSessionCatalog). */
  static final String REST_METRICS_REPORTING_ENABLED = "rest-metrics-reporting-enabled";

  static final String HADOOP_FILE_IO = "org.apache.iceberg.hadoop.HadoopFileIO";

  static final String S3_FILE_IO = "org.apache.iceberg.aws.s3.S3FileIO";

  static final String ADLS_FILE_IO = "org.apache.iceberg.azure.adlsv2.ADLSFileIO";

  /**
   * Top-level namespaces, or the namespaces under {@code parent}: none, since databases have one
   * level, if {@code parent} exists. Pagination parameters are accepted and the full list returned.
   */
  public ListNamespacesResponse listNamespaces(String parent) {
    if (parent != null && !parent.isEmpty()) {
      String databaseId = databaseId(parent);
      requireNamespace(databaseId);
      return ListNamespacesResponse.builder().build();
    }
    return ListNamespacesResponse.builder()
        .addAll(databaseIds().stream().map(Namespace::of).collect(Collectors.toList()))
        .build();
  }

  /**
   * The namespace's stored record, if it has one the caller may read: its spelling and properties.
   * Otherwise (no record, or a caller without the read privilege on it) what the route answered
   * before records existed: the database if it holds a table or a view, with no properties.
   *
   * <p>If the record cannot be read or decided on (the namespace store or the authorization service
   * fails), a namespace that holds a table or a view is answered as before records existed, with no
   * properties, and the failure is logged; one that holds none has no answer but the failure. The
   * holdings are probed only then, so a load still makes one keyed read when the store works.
   *
   * <p>The record is read once: when there is none, existence is what the namespace holds ({@link
   * #holdsSomething}), the rest of the one existence rule.
   */
  public GetNamespaceResponse loadNamespace(String namespace) {
    String databaseId = databaseId(namespace);
    tablesApiValidator.validateSearchTables(databaseId);
    Optional<NamespaceMetadata> record;
    // What the namespace holds, under which spelling: asked at most once per load.
    Optional<String> held = null;
    try {
      record = readRecord(databaseId);
    } catch (RuntimeException failure) {
      held = heldAs(databaseId);
      if (!held.isPresent()) {
        throw failure;
      }
      log.warn(
          "IRC load of namespace {}: its record could not be read ({}); answered without"
              + " properties, as it holds a table or a view",
          databaseId,
          failure.toString());
      record = Optional.empty();
    }
    if (record.isPresent()) {
      return GetNamespaceResponse.builder()
          .withNamespace(Namespace.of(record.get().getNamespaceName()))
          // A copy: Iceberg's builder asks the map for a null value, which an immutable map
          // (the record's) refuses to be asked.
          .setProperties(new HashMap<>(record.get().getProperties()))
          .build();
    }
    if (held == null) {
      held = heldAs(databaseId);
    }
    if (!held.isPresent()) {
      throw missing(databaseId);
    }
    return GetNamespaceResponse.builder()
        .withNamespace(Namespace.of(held.get()))
        .setProperties(Collections.emptyMap())
        .build();
  }

  public void namespaceExists(String namespace) {
    requireNamespace(databaseId(namespace));
  }

  public ListTablesResponse listTables(String namespace) {
    String databaseId = databaseId(namespace);
    tablesApiValidator.validateSearchTables(databaseId);
    List<TableDto> tables = tablesService.searchTables(databaseId);
    if (tables.isEmpty() && !hasRecordOrIsListed(databaseId)) {
      // The listing just read is the table probe: no table and no record, so only the database
      // list is left.
      throw missing(databaseId);
    }
    return ListTablesResponse.builder()
        .addAll(
            tables.stream()
                .map(table -> TableIdentifier.of(table.getDatabaseId(), table.getTableId()))
                .collect(Collectors.toList()))
        .build();
  }

  public void tableExists(String namespace, String tableId) {
    String databaseId = tableDatabaseId(namespace, tableId);
    tablesApiValidator.validateGetTable(databaseId, tableId);
    if (!tablesService.tableExists(databaseId, tableId)) {
      throw new NoSuchTableException("Table does not exist: %s.%s", databaseId, tableId);
    }
  }

  /**
   * Loads {@code databaseId.tableId} for {@code actingPrincipal}.
   *
   * @param snapshots {@code all} (or absent) for every snapshot, {@code refs} for the snapshots
   *     that branches and tags reference
   */
  public IcebergRestLoadedTable loadTable(
      String databaseId, String tableId, String snapshots, String actingPrincipal) {
    boolean refsOnly = refsOnly(snapshots);
    tablesApiValidator.validateGetTable(databaseId, tableId);
    TableDtoWithMetadata loaded = tablesService.loadTable(databaseId, tableId, actingPrincipal);
    TableDto tableDto = loaded.getTableDto();
    return new IcebergRestLoadedTable(
        tableDto.getTableLocation(),
        loaded.getMetadata(),
        tableFileIo.withConfig(readBridgeConfigResolver.resolve(tableDto), loaded.getTable()),
        refsOnly);
  }

  /**
   * The table {@code databaseId.tableId} as {@link #loadTable} loads it for {@code
   * actingPrincipal}: the same validation and the same {@link TablesService#loadTable} call, so the
   * same checks in the same order with the same failures, and the same read audit event ({@code
   * TableAuditAspect}). For the routes that act on a table a caller could load, such as scan
   * planning and credentials, which use its Iceberg {@link Table}.
   */
  public TableDtoWithMetadata loadIcebergTable(
      String databaseId, String tableId, String actingPrincipal) {
    tablesApiValidator.validateGetTable(databaseId, tableId);
    return tablesService.loadTable(databaseId, tableId, actingPrincipal);
  }

  /** The counter of metrics reports received, tagged {@value #REPORT_TYPE_TAG}. */
  static final String METRICS_REPORT_COUNTER = "irc_metrics_report";

  /** The report's type, as Iceberg names it in lower case: {@code scan_report}, ... */
  static final String REPORT_TYPE_TAG = "report_type";

  /**
   * Takes a metrics report a client posts after a scan or a commit of {@code databaseId.tableId}.
   *
   * <p>On the native path a report never reaches the service: the OpenHouse catalog hands it to the
   * client's own reporter ({@code metrics-reporter-impl}), which logs it, and the service keeps no
   * store for one. So a report is recorded where the service records everything else about its
   * requests: a counter by report type in the meter registry, and the report itself in the log at
   * debug level.
   *
   * <p>Authorized as a read of the table, with the read privilege, on the table's house-table row
   * alone, as the native drop is: a reporting client posts after every scan and every commit, and
   * loading the table's metadata for each would double the loads it causes. The lock and capability
   * checks of a load, which need that metadata, are not run: a report reads nothing, and a client
   * those checks turn away never loads the table it would report on.
   *
   * @throws NoSuchUserTableException if there is no such table
   */
  public void reportMetrics(
      String databaseId, String tableId, ReportMetricsRequest request, String actingPrincipal) {
    tablesApiValidator.validateGetTable(databaseId, tableId);
    TableDto table =
        openHouseInternalRepository
            .findTableRefById(
                TableDtoPrimaryKey.builder().databaseId(databaseId).tableId(tableId).build())
            .orElseThrow(() -> new NoSuchUserTableException(databaseId, tableId));
    authorizationUtils.checkTablePrivilege(table, actingPrincipal, Privileges.GET_TABLE_METADATA);
    String type = String.valueOf(request.reportType()).toLowerCase(Locale.ROOT);
    meterRegistry.counter(METRICS_REPORT_COUNTER, REPORT_TYPE_TAG, type).increment();
    if (log.isDebugEnabled()) {
      log.debug(
          "IRC metrics report for {}.{} from {}: {}",
          databaseId,
          tableId,
          actingPrincipal,
          describe(request.report()));
    }
  }

  private static String describe(MetricsReport report) {
    if (report instanceof ScanReport) {
      return ScanReportParser.toJson((ScanReport) report);
    }
    if (report instanceof CommitReport) {
      return CommitReportParser.toJson((CommitReport) report);
    }
    return String.valueOf(report);
  }

  /**
   * The REST namespace level separator, as it arrives in a decoded path parameter. Split here
   * because Iceberg 1.5.2 and 1.12 share no helper for it: 1.12 dropped {@code
   * RESTUtil.NAMESPACE_SPLITTER}, and its {@code decodeNamespace} takes the separator as an
   * argument that 1.5.2's lacks.
   */
  private static final String NAMESPACE_SEPARATOR = Pattern.quote("\u001f");

  /** The database a single-level namespace path parameter names. */
  public static String databaseId(String namespace) {
    List<String> levels = levels(namespace);
    if (levels.size() != 1 || levels.get(0).isEmpty()) {
      throw new BadRequestException(
          "OpenHouse namespaces are databases and have exactly one level: %s",
          String.join(".", levels));
    }
    return levels.get(0);
  }

  /**
   * The database of a table route's namespace path parameter. No table exists in a namespace of
   * more than one level, since none can exist, so that is a missing table (404) rather than a
   * malformed request: {@code RESTCatalog} loads a metadata table such as {@code db.t.snapshots} as
   * the table {@code snapshots} in the namespace {@code db.t} first, and falls back to the metadata
   * table of {@code db.t} only on that 404. A namespace with an empty level is still malformed.
   */
  public static String tableDatabaseId(String namespace, String tableId) {
    List<String> levels = levels(namespace);
    if (levels.size() > 1 && levels.stream().noneMatch(String::isEmpty)) {
      throw new NoSuchTableException(
          "Table does not exist: %s.%s", String.join(".", levels), tableId);
    }
    return databaseId(namespace);
  }

  private static List<String> levels(String namespace) {
    return Arrays.asList(namespace.split(NAMESPACE_SEPARATOR, -1));
  }

  private static boolean refsOnly(String snapshots) {
    if (snapshots == null || SNAPSHOTS_ALL.equals(snapshots.toLowerCase(Locale.ROOT))) {
      return false;
    }
    if (SNAPSHOTS_REFS.equals(snapshots.toLowerCase(Locale.ROOT))) {
      return true;
    }
    throw new BadRequestException(
        "Invalid snapshots mode: %s; expected %s or %s", snapshots, SNAPSHOTS_ALL, SNAPSHOTS_REFS);
  }

  private List<String> databaseIds() {
    return databasesService.getAllDatabases().stream()
        .map(DatabaseDto::getDatabaseId)
        .distinct()
        .collect(Collectors.toList());
  }

  /**
   * Fails unless {@code databaseId} exists as a namespace, by the one existence rule of these
   * routes: it holds a table, it has a stored record the caller may read ({@link
   * #hasRecordOrIsListed}), or the database list shows it (it holds a view, whether or not its
   * views are enabled: the list is every House Tables row's database, of any type). Every part
   * ignores letter case, as House Tables' lookups do. {@link #holdsSomething} is the same rule
   * without the record, in the same order.
   *
   * <p>Whether it holds a table is one row of the native paged table search, which reads the same
   * house-table rows as the unpaged search (live tables of the database, by the same filter), not a
   * listing of every table of the database (V17). The database list is read only when it holds
   * none, and the record (one keyed read) before it; that list has no lookup by name in House
   * Tables, so a miss still reads it.
   */
  void requireNamespace(String databaseId) {
    tablesApiValidator.validateSearchTables(databaseId);
    if (!holdsATable(databaseId) && !hasRecordOrIsListed(databaseId)) {
      throw missing(databaseId);
    }
  }

  private boolean holdsATable(String databaseId) {
    return tablesService.searchTables(databaseId, 0, 1, null, null, null).hasContent();
  }

  /**
   * Whether {@code databaseId} holds something, a table or a view (whether or not its views are
   * enabled): the one-row table probe, then the database list, as {@link #requireNamespace} asks
   * them. A namespace exists exactly when it holds something or has a record; the namespace writes
   * decide "exists" (create) and "not empty" (drop) with this.
   */
  boolean holdsSomething(String databaseId) {
    return heldAs(databaseId).isPresent();
  }

  /**
   * The spelling under which {@code databaseId} holds something, if it does: the requested one when
   * it holds a table (the table probe ignores letter case and keeps the request's spelling, as
   * before), otherwise the database list's stored spelling of it, compared ignoring letter case.
   */
  private Optional<String> heldAs(String databaseId) {
    if (holdsATable(databaseId)) {
      return Optional.of(databaseId);
    }
    return listedAs(databaseId);
  }

  /**
   * The native database list's spelling of {@code databaseId}, compared ignoring letter case, as
   * House Tables' own lookups of a database (its table listing, the namespace records) compare
   * names: a database is one namespace under any spelling of its name.
   */
  private Optional<String> listedAs(String databaseId) {
    return databaseIds().stream().filter(id -> id.equalsIgnoreCase(databaseId)).findFirst();
  }

  /** Whether the native database list shows {@code databaseId}, ignoring letter case. */
  private boolean isListed(String databaseId) {
    return listedAs(databaseId).isPresent();
  }

  private static NoSuchNamespaceException missing(String databaseId) {
    return new NoSuchNamespaceException("Namespace does not exist: %s", databaseId);
  }

  /**
   * Whether {@code databaseId}, which holds no table, exists by the rest of the existence rule: it
   * has a stored record the caller may read ({@link #readRecord}), or the database list shows it.
   * The rule {@link #loadNamespace} applies when the record cannot be read applies here too:
   *
   * <ul>
   *   <li>When the authorization service fails, whether there is a record cannot be known, so a
   *       namespace the database list does not show has no answer but that failure (503), never
   *       "does not exist"; one the list shows exists, as before records existed.
   *   <li>When the namespace store fails, the failure counts as no record (logged), so a namespace
   *       that holds a view is answered as before records existed.
   * </ul>
   *
   * A caller refused the read privilege on the record is answered as if there were none, as {@link
   * #readRecord} answers it.
   */
  private boolean hasRecordOrIsListed(String databaseId) {
    try {
      if (readRecord(databaseId).isPresent()) {
        return true;
      }
    } catch (AuthorizationServiceException outage) {
      if (isListed(databaseId)) {
        log.warn(
            "IRC existence check of namespace {}: its record could not be decided on ({});"
                + " it exists, as the database list shows it",
            databaseId,
            outage.toString());
        return true;
      }
      throw outage;
    } catch (RuntimeException failure) {
      log.warn(
          "IRC existence check of namespace {}: its record could not be read ({}); counted as"
              + " no record",
          databaseId,
          failure.toString());
    }
    return isListed(databaseId);
  }

  /**
   * The stored namespace record of {@code databaseId}, read through the namespace service with its
   * read authorization for the request's principal: one keyed read. A caller the service refuses
   * the read privilege is answered as if there were no record, so that neither the record nor its
   * properties are shown to it, and the route answers it what it did before records existed. A
   * failure of the authorization service (itself an access-denied type) or of the store is thrown.
   */
  private Optional<NamespaceMetadata> readRecord(String databaseId) {
    try {
      return Optional.of(
          namespacesService.loadNamespaceMetadata(databaseId, extractAuthenticatedUserPrincipal()));
    } catch (NotFoundException e) {
      return Optional.empty();
    } catch (AuthorizationServiceException e) {
      throw e;
    } catch (AccessDeniedException e) {
      return Optional.empty();
    }
  }
}
