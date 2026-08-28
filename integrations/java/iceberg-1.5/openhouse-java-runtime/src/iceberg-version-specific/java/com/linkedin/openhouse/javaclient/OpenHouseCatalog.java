package com.linkedin.openhouse.javaclient;

import static com.linkedin.openhouse.javaclient.OpenHouseTableOperations.*;

import com.linkedin.openhouse.client.ssl.HttpConnectionStrategy;
import com.linkedin.openhouse.client.ssl.TablesApiClientFactory;
import com.linkedin.openhouse.client.ssl.WebClientFactory;
import com.linkedin.openhouse.javaclient.api.SupportsGrantRevoke;
import com.linkedin.openhouse.javaclient.builder.ClusteringSpecBuilder;
import com.linkedin.openhouse.javaclient.builder.TimePartitionSpecBuilder;
import com.linkedin.openhouse.javaclient.exception.WebClientRequestWithMessageException;
import com.linkedin.openhouse.javaclient.exception.WebClientResponseWithMessageException;
import com.linkedin.openhouse.javaclient.mapper.Privileges;
import com.linkedin.openhouse.javaclient.mapper.SparkMapper;
import com.linkedin.openhouse.tables.client.api.DatabaseApi;
import com.linkedin.openhouse.tables.client.api.SnapshotApi;
import com.linkedin.openhouse.tables.client.api.TableApi;
import com.linkedin.openhouse.tables.client.invoker.ApiClient;
import com.linkedin.openhouse.tables.client.model.CreateUpdateTableRequestBody;
import com.linkedin.openhouse.tables.client.model.GetAclPoliciesResponseBody;
import com.linkedin.openhouse.tables.client.model.GetAllDatabasesResponseBody;
import com.linkedin.openhouse.tables.client.model.GetAllTablesResponseBody;
import com.linkedin.openhouse.tables.client.model.GetTableResponseBody;
import com.linkedin.openhouse.tables.client.model.UpdateAclPoliciesRequestBody;
import java.io.Closeable;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.net.ssl.SSLException;
import lombok.extern.slf4j.Slf4j;
import org.apache.hadoop.conf.Configurable;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.BaseMetastoreCatalog;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.SchemaParser;
import org.apache.iceberg.SortOrder;
import org.apache.iceberg.SortOrderParser;
import org.apache.iceberg.StaticTableOperations;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableOperations;
import org.apache.iceberg.Transaction;
import org.apache.iceberg.Transactions;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.SupportsNamespaces;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.exceptions.NamespaceNotEmptyException;
import org.apache.iceberg.exceptions.NoSuchNamespaceException;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.exceptions.NoSuchViewException;
import org.apache.iceberg.exceptions.ValidationException;
import org.apache.iceberg.hadoop.HadoopFileIO;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.view.BaseMetastoreViewCatalog;
import org.apache.iceberg.view.View;
import org.apache.iceberg.view.ViewBuilder;
import org.apache.iceberg.view.ViewOperations;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Catalog implementation to create, read, update and delete tables in OpenHouse. This class
 * leverages Openhouse tableclient to perform CRUD operations on Tables resource in the Catalog
 * service. This implementation provides client side catalog implementation for Iceberg tables in
 * Java.
 *
 * <p>This is the iceberg-1.5 / Spark-3.5 copy of {@code OpenHouseCatalog}. It extends {@link
 * BaseMetastoreViewCatalog} (instead of {@link BaseMetastoreCatalog}) so a single catalog object
 * serves both tables (inherited, unchanged) and views. View support is production code, gated and
 * off by default: view operations are active only when {@code
 * spark.sql.catalog.<name>.iceberg-views-enabled=true}, and are thin REST glue — they delegate to
 * an embedded Iceberg {@link RESTCatalog} speaking the Iceberg REST catalog protocol against the
 * same OpenHouse service URI ({@code /v1/config}, {@code /v1/namespaces/{ns}/views...}). The
 * embedded catalog is constructed lazily on the FIRST view operation (never in {@link
 * #initialize}), so with the gate off — and, with the gate on, before any view operation — no view
 * REST call, including the {@code /v1/config} bootstrap, is ever made and a views-endpoint
 * bootstrap failure can only fail a view operation, not table operations. Error translation comes
 * from iceberg-core's {@code ErrorHandlers} parsing the spec's {@code IcebergErrorResponse}
 * envelope, so the server's views-disabled {@code 404 NoSuchViewException} arrives as {@link
 * NoSuchViewException} with no OpenHouse-specific mapping code. The iceberg-1.2 / Spark-3.1 copy
 * stays table-only ({@code extends BaseMetastoreCatalog}).
 *
 * <p>Because extending {@link BaseMetastoreViewCatalog} makes this an Iceberg {@code ViewCatalog},
 * Spark's {@code SparkCatalog} routes view probes to this instance instead of short-circuiting them
 * (it only calls a catalog's view methods when the catalog is {@code instanceof ViewCatalog};
 * otherwise it answers view ops itself). Notably {@code SparkCatalog.loadView} is invoked while
 * resolving every unqualified identifier. So when views are disabled we mirror, method-for-method,
 * how {@code SparkCatalog} behaves for a non-{@code ViewCatalog} (table-only) catalog, making the
 * default state indistinguishable from {@code extends BaseMetastoreCatalog}: {@code loadView}
 * throws {@link NoSuchViewException} (so Spark falls back to table resolution rather than
 * hard-failing), {@code listViews} returns empty, and {@code dropView} returns {@code false}, while
 * the create/modify operations {@code buildView} and {@code renameView} throw {@link
 * UnsupportedOperationException}.
 */
@Slf4j
public class OpenHouseCatalog extends BaseMetastoreViewCatalog
    implements Configurable, SupportsNamespaces, SupportsGrantRevoke, Closeable {

  private TableApi tableApi;

  private ApiClient apiClient;

  private SnapshotApi snapshotApi;

  private DatabaseApi databaseApi;

  private FileIO fileIO;

  private Configuration conf;

  private String cluster;

  private String name;

  protected Map<String, String> properties;

  private static final String DEFAULT_CLUSTER = "local";

  private static final String CLUSTER_PROPERTY = "cluster";

  private static final String AUTH_TOKEN = "auth-token";

  private static final String TRUST_STORE = "trust-store";

  private static final String HTTP_CONNECTION_STRATEGY = "http-connection-strategy";

  public static final String CLIENT_NAME = "client-name";

  public static final String CLIENT_VERSION = "client-version";

  /** Catalog property that gates view support. Off by default. */
  private static final String VIEWS_ENABLED_PROPERTY = "iceberg-views-enabled";

  /** Whether view operations are enabled for this catalog instance (set in {@link #initialize}). */
  private boolean viewsEnabled = false;

  /** Prefix for {@link RESTCatalog} properties passed through verbatim as HTTP request headers. */
  private static final String REST_HEADER_PREFIX = "header.";

  /** Version advertised in {@code User-Agent} when no explicit or manifest version is available. */
  private static final String CLIENT_VERSION_UNKNOWN = "unknown";

  /**
   * Embedded Iceberg REST catalog backing the enabled view operations. Constructed and initialized
   * lazily by {@link #getOrCreateViewsRestCatalog()} on the first view operation — never in {@link
   * #initialize} — so its {@code GET /v1/config} bootstrap cannot run, or fail, unless a view
   * operation actually happens. Guarded by {@link #viewsRestCatalogLock}; volatile for the
   * double-checked read.
   */
  private volatile RESTCatalog viewsRestCatalog;

  private final Object viewsRestCatalogLock = new Object();

  /**
   * Displaced embedded catalogs awaiting {@link #close()}. A token refresh swaps {@link
   * #viewsRestCatalog} to a fresh instance but must NOT close the displaced one: an in-flight view
   * operation may already hold the old reference from the fast-path read, and closing it under the
   * operation would surface an {@code IllegalStateException} from the closed HTTP client into
   * Spark's table resolution (which probes {@code loadView}). Instead the displaced instance is
   * parked here and closed at the next displacement or by {@link #close()}, whichever comes first,
   * so at most one idle HTTP client is retained. Guarded by {@link #viewsRestCatalogLock}.
   */
  private final List<RESTCatalog> displacedViewsRestCatalogs = new ArrayList<>();

  @Override
  public void initialize(String name, Map<String, String> properties) {
    this.name = name;
    this.properties = properties;
    String uri = properties.get(CatalogProperties.URI);
    Preconditions.checkNotNull(uri, "OpenHouse Table Service URI is required");
    log.info("Establishing connection with OpenHouse service at " + uri);
    String truststore = properties.getOrDefault(TRUST_STORE, "");
    String token = properties.getOrDefault(AUTH_TOKEN, null);
    String httpConnectionStrategy = properties.getOrDefault(HTTP_CONNECTION_STRATEGY, null);
    String clientName = properties.getOrDefault(CLIENT_NAME, null);
    String clientVersion = properties.getOrDefault(CLIENT_VERSION, null);
    try {
      TablesApiClientFactory tablesApiClientFactory = TablesApiClientFactory.getInstance();
      tablesApiClientFactory.setStrategy(HttpConnectionStrategy.fromString(httpConnectionStrategy));
      tablesApiClientFactory.setClientName(clientName);
      tablesApiClientFactory.setClientVersion(clientVersion);
      if (properties.containsKey(CatalogProperties.APP_ID)) {
        tablesApiClientFactory.setSessionId(properties.get(CatalogProperties.APP_ID));
      }
      this.apiClient = tablesApiClientFactory.createApiClient(uri, token, truststore);
    } catch (MalformedURLException | SSLException e) {
      throw new RuntimeException(
          "OpenHouse Catalog initialization failed: Failure while initializing ApiClient", e);
    }
    this.tableApi = new TableApi(apiClient);
    this.snapshotApi = new SnapshotApi(apiClient);
    this.databaseApi = new DatabaseApi(apiClient);

    this.fileIO = loadFileIO(properties);

    this.cluster = properties.getOrDefault(CLUSTER_PROPERTY, DEFAULT_CLUSTER);
    this.viewsEnabled =
        Boolean.parseBoolean(properties.getOrDefault(VIEWS_ENABLED_PROPERTY, "false"));
    if (viewsEnabled) {
      // NOTE: the embedded REST catalog is intentionally NOT constructed here. It is built lazily
      // on the first view operation (see getOrCreateViewsRestCatalog()) so that a views-endpoint
      // bootstrap
      // failure cannot break table operations.
      log.info(
          "OpenHouse view support is ENABLED. View operations delegate to the Iceberg REST "
              + "catalog protocol at {} (initialized lazily on first view operation).",
          uri);
    }
  }

  protected FileIO loadFileIO(Map<String, String> properties) {
    String fileIOImpl = properties.get(CatalogProperties.FILE_IO_IMPL);
    return fileIOImpl == null
        ? new HadoopFileIO(this.conf)
        : CatalogUtil.loadFileIO(fileIOImpl, properties, this.conf);
  }

  /**
   * updates the auth token in ApiClient's default header which gets added to every request from
   * ApiClient
   *
   * <p>Also propagates the new token to the embedded views {@link RESTCatalog}: its {@code
   * header.Authorization} is captured immutably at initialization, so the cheapest correct
   * mechanism is to displace the embedded catalog here and let the next view operation lazily
   * rebuild it from the updated {@code auth-token} property. The displaced instance is not closed
   * here (see {@link #displacedViewsRestCatalogs} for the in-flight-operation race this avoids); it
   * is reclaimed by {@link #close()}. With views disabled (or no embedded catalog built yet) this
   * is a no-op.
   *
   * @param token
   */
  protected void updateAuthToken(String token) {
    if (token != null && !token.isEmpty()) {
      this.apiClient.addDefaultHeader(HttpHeaders.AUTHORIZATION, bearerValue(token));
      // The property write joins the monitor that guards every read of it in
      // buildViewsRestCatalog; synchronized is reentrant, so the nested displacement is fine.
      synchronized (viewsRestCatalogLock) {
        this.properties.put(AUTH_TOKEN, token);
        displaceViewsRestCatalog();
      }
    }
  }

  /**
   * returns an unmodifiableMap of catalog properties preserving original properties
   *
   * @return
   */
  @Override
  public Map<String, String> properties() {
    return Collections.unmodifiableMap(properties);
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public List<TableIdentifier> listTables(Namespace namespace) {
    log.info("Calling listTables with namespace: {}", namespace.toString());
    if (namespace.levels().length > 1) {
      throw new ValidationException(
          "Input namespace has more than one levels " + String.join(".", namespace.levels()));
    } else if (namespace.toString().isEmpty()) {
      throw new ValidationException(
          "DatabaseId was not provided, for SQL please run \"SHOW TABLES IN <databaseId>\" instead");
    }
    List<TableIdentifier> tables =
        tableApi
            .searchTablesV1(namespace.toString())
            .map(GetAllTablesResponseBody::getResults)
            .flatMapMany(Flux::fromIterable)
            .map(SparkMapper::toTableIdentifier)
            .collectList()
            .onErrorResume(
                WebClientResponseException.class,
                e -> Mono.error(new WebClientResponseWithMessageException(e)))
            .onErrorResume(
                WebClientRequestException.class,
                e -> Mono.error(new WebClientRequestWithMessageException(e)))
            .block();
    log.debug("Calling listTables succeeded");
    return tables;
  }

  @Override
  public boolean dropTable(TableIdentifier identifier, boolean purge) {
    log.info(
        "Calling dropTable with identifier: {}, and purge option: {}",
        identifier.toString(),
        purge);
    if (identifier.namespace().levels().length > 1) {
      throw new ValidationException(
          "Input namespace has more than one levels "
              + String.join(".", identifier.namespace().levels()));
    }
    // Default to purge = true regardless of the input parameter
    // Currently, SparkCatalog (3.1 and 3.5) will always call dropTable with purge = false and
    // handle purge in purgeTable()
    // To handle on catalog side, we should look to override purgeTable()
    // https://spark.apache.org/docs/3.5.1/api/java/org/apache/spark/sql/connector/catalog/TableCatalog.html#purgeTable(org.apache.spark.sql.connector.catalog.Identifier)

    try {
      tableApi
          .deleteTableV1(identifier.namespace().toString(), identifier.name())
          .onErrorResume(
              WebClientResponseException.NotFound.class,
              e -> Mono.error(new NoSuchTableException("Table " + identifier + " does not exist")))
          .onErrorResume(
              WebClientResponseException.class,
              e -> Mono.error(new WebClientResponseWithMessageException(e)))
          .onErrorResume(
              WebClientRequestException.class,
              e -> Mono.error(new WebClientRequestWithMessageException(e)))
          .block();

    } catch (NoSuchTableException e) {
      log.debug("Table: {} does not exist", identifier.toString());
      return false;
    }
    log.debug("Calling dropTable succeeded");
    return true;
  }

  @Override
  public void renameTable(TableIdentifier from, TableIdentifier to) {
    log.info(
        "Calling renameTable from table identifier: {}, to table identifier: {}",
        from.toString(),
        to.toString());

    if (from.namespace().levels().length > 1) {
      throw new ValidationException(
          "Input namespace has more than one levels "
              + String.join(".", from.namespace().levels()));
    }

    CatalogAndDbNameFromNamespace catalogAndDbName =
        new CatalogAndDbNameFromNamespace(to.namespace());
    if (catalogAndDbName.catalogName() != null
        && !catalogAndDbName.catalogName().equals(this.name())) {
      throw new UnsupportedOperationException(
          String.format(
              "Cannot rename tables across catalogs: from=%s, to=%s",
              String.join(".", this.name(), from.toString()), to));
    }

    tableApi
        .renameTableV1(
            from.namespace().toString(), from.name(), catalogAndDbName.dbName(), to.name())
        .onErrorResume(
            WebClientResponseException.NotFound.class,
            e -> Mono.error(new NoSuchTableException("Table " + from + " does not exist")))
        .onErrorResume(
            WebClientResponseException.class,
            e -> Mono.error(new WebClientResponseWithMessageException(e)))
        .onErrorResume(
            WebClientRequestException.class,
            e -> Mono.error(new WebClientRequestWithMessageException(e)))
        .block();
  }

  @Override
  public TableOperations newTableOps(TableIdentifier tableIdentifier) {
    return OpenHouseTableOperations.builder()
        .tableIdentifier(tableIdentifier)
        .fileIO(fileIO)
        .tableApi(tableApi)
        .snapshotApi(snapshotApi)
        .cluster(cluster)
        .build();
  }

  @Override
  protected boolean isValidIdentifier(TableIdentifier tableIdentifier) {
    return tableIdentifier != null && tableIdentifier.namespace().levels().length == 1;
  }

  /**
   * it's necessary to return null. This function only gets called from {@link
   * BaseMetastoreCatalog}, just before doCommit(). {@link
   * OpenHouseTableOperations#doCommit(org.apache.iceberg.TableMetadata,
   * org.apache.iceberg.TableMetadata)} currently ignores the return value of null.
   *
   * <p>Without this return, an error will be thrown for a simple (CREATE TABLE) statement.
   *
   * <p>This behavior cannot be changed for OH tables, it is decided by table service.
   */
  @Override
  protected String defaultWarehouseLocation(TableIdentifier tableIdentifier) {
    return null;
  }

  /**
   * A {@link BaseMetastoreCatalog} needs to be set as {@link Configurable}.
   *
   * <p>The {@link org.apache.iceberg.spark.SparkCatalog} extensions will provide the right Hadoop
   * configurations from the spark environment when building a custom catalog.
   */
  @Override
  public void setConf(Configuration conf) {
    this.conf = conf;
  }

  @Override
  public Configuration getConf() {
    return conf;
  }

  @Override
  public void createNamespace(Namespace namespace) throws UnsupportedOperationException {
    createNamespace(namespace, null);
  }

  @Override
  public void createNamespace(Namespace namespace, Map<String, String> map)
      throws UnsupportedOperationException {
    throw new UnsupportedOperationException("Create Database is not supported");
  }

  /**
   * List all databases. Support for "show databases" where only the top level databases will be
   * shown.
   *
   * @return
   */
  @Override
  public List<Namespace> listNamespaces() {
    log.info("Calling listNamespaces");
    List<Namespace> namespaces =
        databaseApi
            .getAllDatabasesV1()
            .map(GetAllDatabasesResponseBody::getResults)
            .flatMapMany(Flux::fromIterable)
            .map(SparkMapper::toNamespaces)
            .collectList()
            .block();
    log.debug("Calling listNamespaces succeeded");
    return namespaces;
  }

  /**
   * List databases under a database. Support for "drop database" where the default behavior is
   * cascading and needs to visit databases recursively. We are not supporting multi-level
   * databases, so no need to implement this method.
   *
   * @return
   */
  @Override
  public List<Namespace> listNamespaces(Namespace namespace)
      throws NoSuchNamespaceException, UnsupportedOperationException {
    throw new UnsupportedOperationException("Openhouse supports 2-lvl namespace <schema>.<table>");
  }

  /**
   * Support for "describe database". Implement this if needed.
   *
   * @return
   */
  @Override
  public Map<String, String> loadNamespaceMetadata(Namespace namespace)
      throws NoSuchNamespaceException, UnsupportedOperationException {
    throw new UnsupportedOperationException("Describing database is not supported");
  }

  @Override
  public boolean dropNamespace(Namespace namespace)
      throws NamespaceNotEmptyException, UnsupportedOperationException {
    throw new UnsupportedOperationException("Drop database is not supported");
  }

  @Override
  public boolean setProperties(Namespace namespace, Map<String, String> map)
      throws NoSuchNamespaceException {
    throw new UnsupportedOperationException("Set properties on a database is not supported");
  }

  @Override
  public boolean removeProperties(Namespace namespace, Set<String> set)
      throws NoSuchNamespaceException {
    throw new UnsupportedOperationException("Remove properties of a database is not supported");
  }

  @Override
  public boolean namespaceExists(Namespace namespace) throws NoSuchNamespaceException {
    throw new UnsupportedOperationException("Checking if database exists is not supported");
  }

  @Override
  public void updateTableAclPolicies(
      TableIdentifier tableIdentifier, boolean isGrant, String privilege, String principal) {
    log.info(
        "Calling updateTableAclPolicies with identifier: {}, isGrant: {}, privilege: {}, principal: {}",
        tableIdentifier.toString(),
        isGrant,
        privilege,
        principal);
    if (tableIdentifier.namespace().levels().length > 1) {
      throw new ValidationException(
          "Input namespace has more than one levels "
              + String.join(".", tableIdentifier.namespace().levels()));
    }
    tableApi
        .updateAclPoliciesV1(
            tableIdentifier.namespace().toString(),
            tableIdentifier.name(),
            getUpdateAclPoliciesRequestBody(
                isGrant, principal, Privileges.fromPrivilege(privilege).getRole()))
        .onErrorResume(
            WebClientResponseException.BadRequest.class,
            e ->
                Mono.error(
                    new IllegalArgumentException(
                        e.getStatusCode().value() + " , " + e.getResponseBodyAsString(), e)))
        .onErrorResume(
            WebClientResponseException.class,
            e -> Mono.error(new WebClientResponseWithMessageException(e)))
        .onErrorResume(
            WebClientRequestException.class,
            e -> Mono.error(new WebClientRequestWithMessageException(e)))
        .block();
    log.debug("Calling updateTableAclPolicies succeeded");
  }

  @Override
  public List<AclPolicyDto> getTableAclPolicies(TableIdentifier tableIdentifier) {
    log.info("Calling getTableAclPolicies with identifier: {}", tableIdentifier.toString());
    if (tableIdentifier.namespace().levels().length > 1) {
      throw new ValidationException(
          "Input namespace has more than one levels "
              + String.join(".", tableIdentifier.namespace().levels()));
    }
    List<AclPolicyDto> aclPolicies =
        tableApi.getAclPoliciesV1(tableIdentifier.namespace().toString(), tableIdentifier.name())
            .onErrorResume(
                WebClientResponseException.class,
                e -> Mono.error(new WebClientResponseWithMessageException(e)))
            .onErrorResume(
                WebClientRequestException.class,
                e -> Mono.error(new WebClientRequestWithMessageException(e)))
            .blockOptional().map(GetAclPoliciesResponseBody::getResults)
            .orElse(Collections.emptyList()).stream()
            .map(SparkMapper::toAclPolicyDto)
            .collect(Collectors.toList());

    log.debug("Calling getTableAclPolicies succeeded");
    return aclPolicies;
  }

  @Override
  public void updateDatabaseAclPolicies(
      Namespace identifier, boolean isGrant, String privilege, String principal) {
    log.info(
        "Calling updateDatabaseAclPolicies with namespace: {}, isGrant: {}, privilege: {}, principal: {}",
        identifier.toString(),
        isGrant,
        privilege,
        principal);
    if (identifier.levels().length > 1) {
      throw new ValidationException(
          "Input namespace has more than one levels " + String.join(".", identifier.levels()));
    }
    databaseApi
        .updateDatabaseAclPoliciesV1(
            identifier.toString(),
            getUpdateAclPoliciesRequestBody(
                isGrant, principal, Privileges.fromPrivilege(privilege).getRole()))
        .onErrorResume(
            WebClientResponseException.BadRequest.class,
            e ->
                Mono.error(
                    new IllegalArgumentException(
                        e.getStatusCode().value() + " , " + e.getResponseBodyAsString(), e)))
        .onErrorResume(
            WebClientResponseException.class,
            e -> Mono.error(new WebClientResponseWithMessageException(e)))
        .onErrorResume(
            WebClientRequestException.class,
            e -> Mono.error(new WebClientRequestWithMessageException(e)))
        .block();
    log.debug("Calling updateDatabaseAclPolicies succeeded");
  }

  @Override
  public List<AclPolicyDto> getDatabaseAclPolicies(Namespace namespace) {
    log.info("Calling getDatabaseAclPolicies with identifier: {}", namespace.toString());
    if (namespace.levels().length > 1) {
      throw new ValidationException(
          "Input namespace has more than one levels " + String.join(".", namespace.levels()));
    }
    List<AclPolicyDto> aclPolicies =
        databaseApi.getDatabaseAclPoliciesV1(namespace.toString())
            .onErrorResume(
                WebClientResponseException.class,
                e -> Mono.error(new WebClientResponseWithMessageException(e)))
            .onErrorResume(
                WebClientRequestException.class,
                e -> Mono.error(new WebClientRequestWithMessageException(e)))
            .blockOptional().map(GetAclPoliciesResponseBody::getResults)
            .orElse(Collections.emptyList()).stream()
            .map(SparkMapper::toAclPolicyDto)
            .collect(Collectors.toList());

    log.debug("Calling getDatabaseAclPolicies succeeded");
    return aclPolicies;
  }

  private UpdateAclPoliciesRequestBody getUpdateAclPoliciesRequestBody(
      boolean isGrant, String principal, String role) {
    UpdateAclPoliciesRequestBody updateAclPoliciesRequestBody = new UpdateAclPoliciesRequestBody();
    updateAclPoliciesRequestBody.setOperation(
        isGrant
            ? UpdateAclPoliciesRequestBody.OperationEnum.GRANT
            : UpdateAclPoliciesRequestBody.OperationEnum.REVOKE);
    updateAclPoliciesRequestBody.setPrincipal(principal);
    updateAclPoliciesRequestBody.setRole(role);
    return updateAclPoliciesRequestBody;
  }

  @Override
  public TableBuilder buildTable(TableIdentifier identifier, Schema schema) {
    return new OpenHouseTableBuilder(identifier, schema);
  }

  // ========================== OpenHouse Views (gated, off by default) ==========================
  // Gated by VIEWS_ENABLED_PROPERTY: when enabled, view operations delegate to an embedded
  // Iceberg RESTCatalog (thin REST glue over the spec's /v1/namespaces/{ns}/views endpoints),
  // built lazily on the first view operation. When disabled, each method mirrors how Spark's
  // SparkCatalog treats a non-ViewCatalog (table-only) catalog — no view REST call is ever made.

  /**
   * Lazily builds (and caches) the embedded {@link RESTCatalog} that backs enabled view operations.
   * The first call performs the Iceberg REST {@code GET /v1/config} bootstrap; a bootstrap or
   * configuration failure therefore surfaces as that view operation's failure and leaves nothing
   * cached (the next view operation retries), while table operations are untouched.
   */
  private RESTCatalog getOrCreateViewsRestCatalog() {
    RESTCatalog current = viewsRestCatalog;
    if (current != null) {
      return current;
    }
    synchronized (viewsRestCatalogLock) {
      if (viewsRestCatalog == null) {
        viewsRestCatalog = buildViewsRestCatalog();
      }
      return viewsRestCatalog;
    }
  }

  /**
   * Derives the embedded {@link RESTCatalog}'s configuration from the existing catalog properties —
   * no new user-facing keys beyond the {@code iceberg-views-enabled} gate:
   *
   * <ul>
   *   <li>{@code uri}: the existing {@code uri} property (same service; the REST view routes mount
   *       alongside {@code /v1/databases/...}).
   *   <li>{@code header.Authorization: Bearer <token>}: the existing {@code auth-token} property,
   *       passed through as a plain header. Iceberg's OAuth2 {@code token}/{@code credential}
   *       machinery (token refresh, {@code /v1/oauth/tokens}) is deliberately kept out of the loop.
   *   <li>{@code header.X-Client-Name}/{@code header.session-id}/{@code header.User-Agent}: mirror
   *       the identity headers {@code TablesApiClientFactory} sets on the tables WebClient, so
   *       audit/telemetry sees the same client identity on view calls. {@code User-Agent} is always
   *       set ({@code client-version} property, else this jar's manifest Implementation-Version,
   *       else {@code unknown}); {@code session-id} only when the {@code app-id} property is
   *       present — fabricating a UUID here would break correlation with the tables WebClient's
   *       session.
   *   <li>{@code prefix}: unset — the server serves un-prefixed paths and its {@code /v1/config}
   *       returns no override.
   * </ul>
   *
   * <p>TLS: Iceberg's {@code HTTPClient} builds its connection manager with {@code
   * useSystemProperties()}, so the embedded catalog trusts the JVM's default trust material (and
   * honors the standard {@code javax.net.ssl.trustStore*} system properties). The OpenHouse {@code
   * trust-store} catalog property only configures the tables WebClient; when it is set, the
   * server's certificate must also chain from the JVM trust material for view calls to work over
   * https, and a warning is logged here to make that visible.
   */
  private RESTCatalog buildViewsRestCatalog() {
    Map<String, String> restProperties = new HashMap<>();
    String uri = properties.get(CatalogProperties.URI);
    restProperties.put(CatalogProperties.URI, uri);
    String token = properties.get(AUTH_TOKEN);
    if (isNotEmpty(token)) {
      restProperties.put(REST_HEADER_PREFIX + HttpHeaders.AUTHORIZATION, bearerValue(token));
    }
    String clientName = properties.get(CLIENT_NAME);
    if (isNotEmpty(clientName)) {
      restProperties.put(REST_HEADER_PREFIX + WebClientFactory.HTTP_HEADER_CLIENT_NAME, clientName);
    }
    String sessionId = properties.get(CatalogProperties.APP_ID);
    if (isNotEmpty(sessionId)) {
      restProperties.put(REST_HEADER_PREFIX + WebClientFactory.SESSION_ID, sessionId);
    }
    restProperties.put(
        REST_HEADER_PREFIX + HttpHeaders.USER_AGENT,
        WebClientFactory.USER_AGENT_CLIENT_PRODUCT + "/" + resolveClientVersion());
    String truststore = properties.get(TRUST_STORE);
    if (isNotEmpty(truststore) && uri != null && uri.toLowerCase(Locale.ROOT).startsWith("https://")) {
      // May re-fire on each rebuild (token refresh / close-then-reuse); accepted — the asymmetry
      // is worth re-surfacing whenever a fresh embedded client is about to dial out over https.
      log.warn(
          "Catalog property '{}' configures the tables WebClient only; the embedded views REST "
              + "catalog trusts the JVM default trust store (javax.net.ssl.trustStore* system "
              + "properties). Ensure the service certificate chains from the JVM trust material.",
          TRUST_STORE);
    }
    log.info("Initializing embedded Iceberg REST catalog for OpenHouse views at {}", uri);
    RESTCatalog restCatalog = new RESTCatalog();
    restCatalog.setConf(conf);
    try {
      restCatalog.initialize(name, restProperties);
      return restCatalog;
    } catch (RuntimeException e) {
      try {
        restCatalog.close();
      } catch (IOException | RuntimeException closeFailure) {
        e.addSuppressed(closeFailure);
      }
      throw e;
    }
  }

  /** True when the value is present and non-empty (the uniform absence test for properties). */
  private static boolean isNotEmpty(String value) {
    return value != null && !value.isEmpty();
  }

  /** Formats the {@code Authorization} header value for the given token. */
  private static String bearerValue(String token) {
    return String.format("Bearer %s", token);
  }

  /**
   * Version advertised in the embedded catalog's {@code User-Agent}: the explicit {@code
   * client-version} property, else the Implementation-Version stamped into this jar's manifest,
   * else {@value #CLIENT_VERSION_UNKNOWN} — mirroring {@code WebClientFactory}'s resolution for the
   * tables WebClient.
   */
  private String resolveClientVersion() {
    String clientVersion = properties.get(CLIENT_VERSION);
    if (isNotEmpty(clientVersion)) {
      return clientVersion;
    }
    Package pkg = OpenHouseCatalog.class.getPackage();
    String manifestVersion = pkg == null ? null : pkg.getImplementationVersion();
    return isNotEmpty(manifestVersion) ? manifestVersion : CLIENT_VERSION_UNKNOWN;
  }

  /**
   * Displaces (without closing) the embedded views {@link RESTCatalog}, if one was ever built. The
   * next enabled view operation lazily rebuilds it from the current catalog properties (see {@link
   * #updateAuthToken}); the displaced instance is parked in {@link #displacedViewsRestCatalogs} and
   * reclaimed by {@link #close()}, never closed here — see the field javadoc for the in-flight race
   * that closing here would create.
   */
  private void displaceViewsRestCatalog() {
    synchronized (viewsRestCatalogLock) {
      if (viewsRestCatalog == null) {
        return;
      }
      // Reclaim the previous generation before parking this one: it was displaced at least one
      // token-refresh interval ago, so no operation that read it from the fast path is still in
      // flight. Without this the list grows once per refresh for the life of the catalog.
      closeDisplacedViewsRestCatalogs();
      displacedViewsRestCatalogs.add(viewsRestCatalog);
      viewsRestCatalog = null;
    }
  }

  /** Closes and forgets every parked instance. Caller holds {@link #viewsRestCatalogLock}. */
  private void closeDisplacedViewsRestCatalogs() {
    for (RESTCatalog displaced : displacedViewsRestCatalogs) {
      try {
        displaced.close();
      } catch (IOException | RuntimeException e) {
        log.warn("Failed to close a displaced embedded views REST catalog", e);
      }
    }
    displacedViewsRestCatalogs.clear();
  }

  /**
   * Closes client-side resources; today that is the embedded views {@link RESTCatalog} plus any
   * instance displaced by a token refresh. Not terminal for view support: a subsequent view
   * operation lazily re-initializes a fresh embedded catalog (accepted resurrection semantics — the
   * catalog object itself stays usable).
   *
   * <p>Unlike a token refresh, this does close the live instance, so it must not overlap a view
   * operation on another thread: a concurrent operation holding the reference from the lock-free
   * fast path would see an {@code IllegalStateException} from the closed HTTP client. Callers close
   * a catalog they are done using.
   */
  @Override
  public void close() {
    synchronized (viewsRestCatalogLock) {
      displaceViewsRestCatalog();
      closeDisplacedViewsRestCatalogs();
    }
  }

  /**
   * Unreachable: every view operation delegates to the embedded {@link RESTCatalog} (or answers
   * directly in the disabled state), so the {@link BaseMetastoreViewCatalog} builder machinery that
   * would call this is bypassed.
   */
  @Override
  protected ViewOperations newViewOps(TableIdentifier identifier) {
    throw new IllegalStateException(
        "newViewOps is unreachable: OpenHouse view operations delegate to an embedded Iceberg "
            + "RESTCatalog instead of the BaseMetastoreViewCatalog machinery");
  }

  /**
   * {@inheritDoc}
   *
   * <p>When views are disabled, throws {@link NoSuchViewException} rather than {@link
   * UnsupportedOperationException}. Spark's {@code SparkCatalog.loadView} probes this method while
   * resolving every unqualified identifier and catches only {@code NoSuchViewException} to fall
   * back to table resolution; any other exception propagates and breaks table reads. Throwing
   * {@code NoSuchViewException} here therefore reproduces the table-only (non-{@code ViewCatalog})
   * behavior.
   */
  @Override
  public View loadView(TableIdentifier identifier) {
    if (!viewsEnabled) {
      throw new NoSuchViewException("View does not exist: %s", identifier);
    }
    log.info("Calling loadView with identifier: {}", identifier);
    return getOrCreateViewsRestCatalog().loadView(identifier);
  }

  /**
   * {@inheritDoc}
   *
   * <p>When views are disabled, returns {@code false} without any REST call, matching the
   * table-only (non-{@code ViewCatalog}) behavior. When enabled, delegates to the embedded REST
   * catalog. NOTE: iceberg 1.5.2.17's {@code RESTSessionCatalog} implements {@code viewExists} via
   * the {@code ViewCatalog} default (a {@code GET} load-and-catch), not the spec's {@code HEAD}
   * route — the server's {@code HEAD} endpoint is simply unused by this client version.
   */
  @Override
  public boolean viewExists(TableIdentifier identifier) {
    if (!viewsEnabled) {
      return false;
    }
    log.info("Calling viewExists with identifier: {}", identifier);
    return getOrCreateViewsRestCatalog().viewExists(identifier);
  }

  /**
   * {@inheritDoc}
   *
   * <p>A create operation. When views are disabled this throws Iceberg's {@link
   * NoSuchNamespaceException}. {@code CREATE VIEW} reaches this method through {@code
   * SparkCatalog.createView}, which calls {@code buildView(...).create()} and catches only {@code
   * NoSuchNamespaceException} / {@code AlreadyExistsException} (rethrowing them as Spark {@code
   * AnalysisException}s); any other exception — e.g. {@link UnsupportedOperationException} — would
   * leak as a raw runtime error and break callers that expect an {@code AnalysisException}.
   * Throwing {@code NoSuchNamespaceException} is therefore the signal that normalizes {@code CREATE
   * VIEW} rejection to a Spark {@code AnalysisException}, matching how a table-only catalog
   * (Iceberg 1.2 / Spark 3.1) rejects it. See {@code OpenHouseViewSparkITest}.
   */
  @Override
  public ViewBuilder buildView(TableIdentifier identifier) {
    if (!viewsEnabled) {
      throw new NoSuchNamespaceException(
          "OpenHouse views are not enabled; cannot create view: %s", identifier);
    }
    log.info("Calling buildView with identifier: {}", identifier);
    // Delegates to the REST view builder: create -> POST CreateViewRequest;
    // replace/createOrReplace -> load + commit POST with assert-view-uuid requirements + typed
    // updates. The REST client owns commit semantics; location assignment is the server's job (no
    // client-side default location).
    return getOrCreateViewsRestCatalog().buildView(identifier);
  }

  /**
   * {@inheritDoc}
   *
   * <p>When views are disabled, returns an empty list, matching how {@code SparkCatalog} answers
   * {@code SHOW VIEWS} for a non-{@code ViewCatalog} catalog (no views, rather than an error).
   *
   * <p>When enabled, delegates a single {@code GET .../views} to the embedded REST catalog —
   * iceberg 1.5.2.17's {@code RESTSessionCatalog.listViews} does no {@code next-page-token} paging
   * (the server must return all results when {@code pageToken} is absent). Any {@code 404} from the
   * list route is caught here and answered as an empty list: {@code SparkCatalog.listViews} catches
   * nothing, so without this {@code SHOW VIEWS} would leak a raw error instead of showing no views.
   * In 1.5.2.17 the route's {@code namespaceErrorHandler} switches on the status code, not the
   * envelope's {@code type}, so every list-route 404 (the spec's {@code NoSuchNamespaceException}
   * and the views-disabled {@code NoSuchViewException} envelope alike) arrives client-side as
   * {@link NoSuchNamespaceException}; {@link NoSuchViewException} is caught too as cheap
   * future-proofing against a client that starts honoring the envelope type.
   */
  @Override
  public List<TableIdentifier> listViews(Namespace namespace) {
    if (!viewsEnabled) {
      return Collections.emptyList();
    }
    log.info("Calling listViews with namespace: {}", namespace.toString());
    try {
      return getOrCreateViewsRestCatalog().listViews(namespace);
    } catch (NoSuchNamespaceException | NoSuchViewException e) {
      log.debug("listViews for namespace {} answered 404; returning empty list", namespace, e);
      return Collections.emptyList();
    }
  }

  /**
   * {@inheritDoc}
   *
   * <p>When views are disabled, returns {@code false} (nothing to drop), matching how {@code
   * SparkCatalog} answers {@code DROP VIEW} for a non-{@code ViewCatalog} catalog; this keeps
   * {@code DROP VIEW ... IF EXISTS} a no-op rather than an error.
   */
  @Override
  public boolean dropView(TableIdentifier identifier) {
    if (!viewsEnabled) {
      return false;
    }
    log.info("Calling dropView with identifier: {}", identifier);
    return getOrCreateViewsRestCatalog().dropView(identifier);
  }

  /**
   * {@inheritDoc}
   *
   * <p>Unsupported in both gate states, so no gate check is needed: when views are disabled this
   * matches how {@code SparkCatalog} fails {@code ALTER VIEW ... RENAME} for a non-{@code
   * ViewCatalog} catalog, and when enabled the server deliberately leaves the spec's {@code
   * rename-view} route unclaimed (a plain 404) — delegating would only turn that 404 into a
   * misleading {@link NoSuchViewException}.
   */
  @Override
  public void renameView(TableIdentifier from, TableIdentifier to) {
    throw new UnsupportedOperationException(
        "Renaming views is not supported by OpenHouse (regardless of the "
            + VIEWS_ENABLED_PROPERTY
            + " gate)");
  }

  /**
   * {@link OpenHouseTableBuilder} re-uses most of its functionality to {@link
   * BaseMetastoreCatalogTableBuilder}, except for: {@link
   * OpenHouseTableBuilder#createTransaction()} and {@link
   * OpenHouseTableBuilder#createOrReplaceTransaction()}
   *
   * <p>Overridden behavior is only for CTAS statements, which is, OpenHouseService is contacted
   * with stage=true, and its returned metadata is used for further data processing.
   */
  private final class OpenHouseTableBuilder extends BaseMetastoreCatalogTableBuilder {
    private final TableIdentifier identifier;
    private final Schema schema;

    private final ImmutableMap.Builder<String, String> propertiesBuilder = ImmutableMap.builder();
    private PartitionSpec spec = PartitionSpec.unpartitioned();
    private SortOrder sortOrder = SortOrder.unsorted();

    OpenHouseTableBuilder(TableIdentifier identifier, Schema schema) {
      super(identifier, schema);
      this.identifier = identifier;
      this.schema = schema;
    }

    @Override
    public TableBuilder withPartitionSpec(PartitionSpec newSpec) {
      this.spec = newSpec != null ? newSpec : PartitionSpec.unpartitioned();
      super.withPartitionSpec(newSpec);
      return this;
    }

    @Override
    public TableBuilder withProperties(Map<String, String> properties) {
      if (properties != null) {
        this.propertiesBuilder.putAll(properties);
      }
      super.withProperties(properties);
      return this;
    }

    @Override
    public TableBuilder withProperty(String key, String value) {
      this.propertiesBuilder.put(key, value);
      super.withProperty(key, value);
      return this;
    }

    @Override
    public TableBuilder withSortOrder(SortOrder sortOrder) {
      this.sortOrder = sortOrder != null ? sortOrder : SortOrder.unsorted();
      super.withSortOrder(sortOrder);
      return this;
    }

    /**
     * Start a transaction to create or replace a table. If table does not exist the method will
     * stage create the table. If the table exists, it will stage replace the table. The table will
     * be live and queryable for use only after transaction has been committed.
     */
    @Override
    public Transaction createOrReplaceTransaction() {
      TableOperations ops = newTableOps(this.identifier);
      if (ops.current() == null) {
        return createTransaction();
      } else {
        return replaceTransaction();
      }
    }

    /**
     * Start a transaction to replace an existing table. The method will stage replace the table
     * with schema and partition evolution checks bypassed. The table will be live and queryable for
     * use only after transaction has been committed.
     */
    @Override
    public Transaction replaceTransaction() {
      TableOperations ops = newTableOps(this.identifier);
      if (ops.current() == null) {
        throw new NoSuchTableException("Table does not exist: %s", new Object[] {this.identifier});
      }
      TableMetadata metadata = replaceStagedMetadata(ops);
      return Transactions.replaceTableTransaction(this.identifier.toString(), ops, metadata);
    }

    /**
     * Start a transaction to create a table. If table does not exist the method will stage create
     * the table. The table will be live and queryable for use only after transaction has been
     * committed.
     */
    @Override
    public Transaction createTransaction() {
      TableOperations ops = newTableOps(this.identifier);
      if (ops.current() != null) {
        throw new AlreadyExistsException(
            "Table already exists: %s", new Object[] {this.identifier});
      } else {
        TableMetadata metadata = createStagedMetadata();
        return Transactions.createTableTransaction(this.identifier.toString(), ops, metadata);
      }
    }

    private TableMetadata createStagedMetadata() {
      CreateUpdateTableRequestBody createUpdateTableRequestBody =
          new CreateUpdateTableRequestBody();
      createUpdateTableRequestBody.setTableId(identifier.name());
      createUpdateTableRequestBody.setDatabaseId(identifier.namespace().toString());
      createUpdateTableRequestBody.setClusterId(cluster);
      createUpdateTableRequestBody.setBaseTableVersion(INITIAL_TABLE_VERSION);
      createUpdateTableRequestBody.setSchema(SchemaParser.toJson(schema, false));
      createUpdateTableRequestBody.setStageCreate(true);
      createUpdateTableRequestBody.setTimePartitioning(
          TimePartitionSpecBuilder.builderFor(schema, spec).build());
      createUpdateTableRequestBody.setClustering(
          ClusteringSpecBuilder.builderFor(schema, spec).build());
      createUpdateTableRequestBody.setTableProperties(propertiesBuilder.build());
      createUpdateTableRequestBody.setSortOrder(SortOrderParser.toJson(sortOrder));
      String tableLocation =
          tableApi
              .createTableV1(identifier.namespace().toString(), createUpdateTableRequestBody)
              .onErrorResume(
                  e ->
                      handleCreateUpdateHttpError(
                          e,
                          createUpdateTableRequestBody.getDatabaseId(),
                          createUpdateTableRequestBody.getTableId()))
              .mapNotNull(GetTableResponseBody::getTableLocation)
              .block();
      return new StaticTableOperations(tableLocation, fileIO).refresh();
    }

    private TableMetadata replaceStagedMetadata(TableOperations ops) {
      CreateUpdateTableRequestBody createUpdateTableRequestBody =
          new CreateUpdateTableRequestBody();
      createUpdateTableRequestBody.setTableId(identifier.name());
      createUpdateTableRequestBody.setDatabaseId(identifier.namespace().toString());
      createUpdateTableRequestBody.setClusterId(cluster);
      createUpdateTableRequestBody.setBaseTableVersion(ops.current().metadataFileLocation());
      createUpdateTableRequestBody.setSchema(SchemaParser.toJson(schema, false));
      createUpdateTableRequestBody.setTimePartitioning(
          TimePartitionSpecBuilder.builderFor(schema, spec).build());
      createUpdateTableRequestBody.setClustering(
          ClusteringSpecBuilder.builderFor(schema, spec).build());
      createUpdateTableRequestBody.setTableProperties(propertiesBuilder.build());
      createUpdateTableRequestBody.setSortOrder(SortOrderParser.toJson(sortOrder));
      createUpdateTableRequestBody.setStageReplace(
          true); // indicate this is a replace table operation

      String tableLocation =
          tableApi
              .createTableV1(identifier.namespace().toString(), createUpdateTableRequestBody)
              .onErrorResume(
                  e ->
                      handleCreateUpdateHttpError(
                          e,
                          createUpdateTableRequestBody.getDatabaseId(),
                          createUpdateTableRequestBody.getTableId()))
              .mapNotNull(GetTableResponseBody::getTableLocation)
              .block();
      return new StaticTableOperations(tableLocation, fileIO).refresh();
    }
  }

  /**
   * In scenarios where catalog name is being lumped together with the namespace as it is not being
   * parsed by the Spark Strategy. Needed in some scenarios to maintain compatibility with Hive DDL
   * while also supporting Iceberg Spark DDL. This class is used as a way to parse the catalog name
   * and dbname from a namespace
   */
  private static class CatalogAndDbNameFromNamespace {
    private final String catalogName;
    private final String dbName;

    public CatalogAndDbNameFromNamespace(Namespace namespace) {
      if (namespace.levels().length > 2) {
        throw new ValidationException(
            "Namespace has unexpected levels " + String.join(".", namespace.levels()));
      } else if (namespace.levels().length == 2) {
        this.catalogName = namespace.level(0);
        this.dbName = namespace.level(1);
      } else {
        this.dbName = namespace.toString();
        this.catalogName = null;
      }
    }

    public String catalogName() {
      return this.catalogName;
    }

    public String dbName() {
      return this.dbName;
    }
  }
}
