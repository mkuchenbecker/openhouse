package com.linkedin.openhouse.tables.api.irc;

import com.linkedin.openhouse.tables.api.validator.TablesApiValidator;
import com.linkedin.openhouse.tables.model.DatabaseDto;
import com.linkedin.openhouse.tables.model.TableDto;
import com.linkedin.openhouse.tables.model.TableDtoWithMetadata;
import com.linkedin.openhouse.tables.readbridge.ReadBridgeConfigResolver;
import com.linkedin.openhouse.tables.services.DatabasesService;
import com.linkedin.openhouse.tables.services.TablesService;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.BadRequestException;
import org.apache.iceberg.exceptions.NoSuchNamespaceException;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.rest.responses.ConfigResponse;
import org.apache.iceberg.rest.responses.GetNamespaceResponse;
import org.apache.iceberg.rest.responses.ListNamespacesResponse;
import org.apache.iceberg.rest.responses.ListTablesResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The read half of the Iceberg REST Catalog (IRC) protocol over the existing service layer. Each
 * route answers with what the native API answers for the same question, through the same service
 * calls and checks:
 *
 * <ul>
 *   <li>A namespace is an OpenHouse database, one level only. The native API has no empty
 *       databases: a database exists while the native database list shows it, that is while it
 *       holds a table (or a view). So a namespace exists exactly then.
 *   <li>Listing namespaces is the native database list; listing tables is the native table search.
 *       Neither makes an authorization decision, as natively.
 *   <li>Table existence is the native existence check: a house-table lookup without loading the
 *       metadata, with no authorization decision and no capability check.
 *   <li>Loading a table is {@link TablesService#loadTable}: the native load with the same checks in
 *       the same order (lock, privilege, lock access, client capability), from one load of the
 *       table. The response carries the stored metadata unchanged, the metadata location the native
 *       API reports, and the per-table config the native API gives the same client.
 * </ul>
 */
@Component
public class IcebergRestApiHandler {

  @Autowired private TablesApiValidator tablesApiValidator;

  @Autowired private TablesService tablesService;

  @Autowired private DatabasesService databasesService;

  @Autowired private ReadBridgeConfigResolver readBridgeConfigResolver;

  /** Values of the {@code snapshots} query parameter of a table load. */
  static final String SNAPSHOTS_ALL = "all";

  static final String SNAPSHOTS_REFS = "refs";

  /**
   * Defaults a client applies under its own configuration; no overrides.
   *
   * <ul>
   *   <li>{@code rest-metrics-reporting-enabled=false}: the metrics route is not served yet, and a
   *       client that reports would post after every scan and commit and log each refusal.
   *   <li>{@code io-impl=HadoopFileIO}: the existing OpenHouse client's FileIO. RESTCatalog's own
   *       default (ResolvingFileIO) cannot write delete files on this Iceberg line, which opens
   *       them with write properties ResolvingFileIO rejects.
   * </ul>
   */
  public ConfigResponse config() {
    return ConfigResponse.builder()
        .withDefault(REST_METRICS_REPORTING_ENABLED, "false")
        .withDefault(CatalogProperties.FILE_IO_IMPL, HADOOP_FILE_IO)
        .build();
  }

  /** RESTCatalog's switch for posting metrics reports (private in RESTSessionCatalog). */
  static final String REST_METRICS_REPORTING_ENABLED = "rest-metrics-reporting-enabled";

  static final String HADOOP_FILE_IO = "org.apache.iceberg.hadoop.HadoopFileIO";

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

  public GetNamespaceResponse loadNamespace(String namespace) {
    String databaseId = databaseId(namespace);
    requireNamespace(databaseId);
    return GetNamespaceResponse.builder()
        .withNamespace(Namespace.of(databaseId))
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
    if (tables.isEmpty()) {
      requireNamespace(databaseId);
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
        readBridgeConfigResolver.resolve(tableDto),
        refsOnly);
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
   * Fails unless the native API reports {@code databaseId}: it holds a table, or the database list
   * shows it (which also counts databases holding only views).
   */
  private void requireNamespace(String databaseId) {
    tablesApiValidator.validateSearchTables(databaseId);
    if (tablesService.searchTables(databaseId).isEmpty() && !databaseIds().contains(databaseId)) {
      throw new NoSuchNamespaceException("Namespace does not exist: %s", databaseId);
    }
  }
}
