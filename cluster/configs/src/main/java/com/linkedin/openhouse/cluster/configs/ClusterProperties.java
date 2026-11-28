package com.linkedin.openhouse.cluster.configs;

import java.util.List;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

/** Class to load the Runtime Configuration from a YAML file into ClusterProperties object. */
@Configuration
@PropertySource(
    name = "cluster",
    value = "file:${OPENHOUSE_CLUSTER_CONFIG_PATH:/var/config/cluster.yaml}",
    factory = YamlPropertySourceFactory.class,
    ignoreResourceNotFound = true)
@Getter
public class ClusterProperties {

  @Value("${cluster.name:local-cluster}")
  private String clusterName;

  @Value("${cluster.storage.type:hadoop}")
  private String clusterStorageType;

  @Value("${cluster.storage.uri:#{null}}")
  private String clusterStorageURI;

  @Value("${cluster.storage.root-path:/tmp}")
  private String clusterStorageRootPath;

  @Value("${cluster.storage.hadoop.config.core-site.path:#{null}}")
  private String clusterStorageHadoopCoreSitePath;

  @Value("${cluster.storage.hadoop.config.hdfs-site.path:#{null}}")
  private String clusterStorageHadoopHdfsSitePath;

  @Value("${cluster.storage.hadoop.token.refresh.schedule.cron:0 0 0/12 * * ?}")
  private String clusterStorageHadoopTokenRefreshScheduleCron;

  @Value("${cluster.iceberg.write.format.default:orc}")
  private String clusterIcebergWriteFormatDefault;

  @Value("${cluster.iceberg.write.orc.compression-codec:#{null}}")
  private String clusterIcebergWriteOrcCompressionCodec;

  @Value("${cluster.iceberg.format-version:2}")
  private int clusterIcebergFormatVersion;

  /**
   * Whether tables of Iceberg format version 3 may exist on this cluster. Off by default. It never
   * makes a table v3 by itself: tables are still created at {@code cluster.iceberg.format-version},
   * and a table is v3 only when its create asks for format version 3 or an explicit upgrade is
   * requested for it. Upgrading a table cannot be undone.
   */
  @Value("${cluster.iceberg.format-v3.enabled:false}")
  private boolean clusterIcebergFormatV3Enabled;

  /**
   * Whether a format v3 table may have a variant column. Off by default, and needs {@code
   * cluster.iceberg.format-v3.enabled}. Turning it on costs this for every table that takes one:
   * the column can never be dropped; the maintenance jobs, which run on Spark 3.5, do not compact
   * the table and report failure on it; Spark 3.5 cannot read it; and it cannot carry a retention
   * policy. Turning it off again refuses new variant columns only; tables that have one keep it.
   */
  @Value("${cluster.iceberg.format-v3.variant.enabled:false}")
  private boolean clusterIcebergFormatV3VariantEnabled;

  @Value("${cluster.iceberg.write.metadata.delete-after-commit.enabled:false}")
  private boolean clusterIcebergWriteMetadataDeleteAfterCommitEnabled;

  @Value("${cluster.iceberg.write.metadata.previous-versions-max:100}")
  private int clusterIcebergWriteMetadataPreviousVersionsMax;

  @Value("${cluster.housetables.base-uri:#{null}}")
  private String clusterHouseTablesBaseUri;

  @Value("${cluster.housetables.database.type:IN_MEMORY}")
  private String clusterHouseTablesDatabaseType;

  /**
   * The width of the House Tables column that holds a table's or view's current metadata location
   * ({@code user_table_row.metadata_location}), where the deployment states it. With a width set,
   * the tables service refuses, before it writes anything, a write whose location would be longer.
   *
   * <p>The default, 0, checks nothing: every write goes to House Tables as it always did, and a
   * location the column cannot hold fails at the row write, after the metadata file is written. Set
   * it to the column's width to have such writes refused early: 255 on the production store ({@code
   * services/housetables/ddl/0000__baseline.sql}), 512 where the House Tables service created its
   * own tables from its bootstrap {@code schema.sql}. Never set it above the column's real width. A
   * value that is not positive means no check.
   */
  @Value("${cluster.housetables.metadata-location-max-length:0}")
  private int clusterHouseTablesMetadataLocationMaxLength;

  /**
   * The width of the House Tables column that holds a table's or view's name ({@code
   * user_table_row.table_id}), where the deployment states it. With a width set, the tables service
   * refuses, before it writes anything, a rename whose target name would be longer; a create's name
   * is at most 128 characters by request validation, whatever this says.
   *
   * <p>The default, 0, checks nothing: a rename goes to House Tables as it always did, and a name
   * the column cannot hold fails at the row write, after the metadata file is written. Set it to
   * have such renames refused early: 128 where the House Tables service created its own tables from
   * its bootstrap {@code schema.sql}; on the production store ({@code
   * services/housetables/ddl/0000__baseline.sql}) {@code user_table_row.table_id} is 255 wide, but
   * {@code soft_deleted_user_table_row.table_id} only 128, so a table renamed to 129 to 255
   * characters there cannot be soft-deleted, and 128 keeps every name deletable. Never set it above
   * the column's real width. A value that is not positive means no check.
   */
  @Value("${cluster.housetables.table-id-max-length:0}")
  private int clusterHouseTablesTableIdMaxLength;

  @Value("${cluster.housetables.database.url:jdbc:h2:mem:htsdb;MODE=MYSQL;DB_CLOSE_DELAY=-1}")
  private String clusterHouseTablesDatabaseUrl;

  @Value("${HTS_DB_USER:}")
  private String clusterHouseTablesDatabaseUsername;

  @Value("${HTS_DB_PASSWORD:}")
  private String clusterHouseTablesDatabasePassword;

  // MySQL SSL/Certificate configuration for certificate-based authentication
  @Value("${cluster.housetables.database.cert-based-auth.enabled:false}")
  private boolean clusterHouseTablesDatabaseCertBasedAuthEnabled;

  @Value("${cluster.housetables.database.cert-based-auth.ssl-mode:VERIFY_IDENTITY}")
  private String clusterHouseTablesDatabaseCertBasedAuthSslMode;

  @Value("${cluster.housetables.database.cert-based-auth.client-cert-keystore-url:#{null}}")
  private String clusterHouseTablesDatabaseCertBasedAuthClientCertKeystoreUrl;

  @Value("${cluster.housetables.database.cert-based-auth.client-cert-keystore-password:#{null}}")
  private String clusterHouseTablesDatabaseCertBasedAuthClientCertKeystorePassword;

  @Value("${cluster.housetables.database.cert-based-auth.truststore-url:#{null}}")
  private String clusterHouseTablesDatabaseCertBasedAuthTruststoreUrl;

  @Value("${cluster.housetables.database.cert-based-auth.truststore-password:#{null}}")
  private String clusterHouseTablesDatabaseCertBasedAuthTruststorePassword;

  @Value("${cluster.security.token.interceptor.classname:#{null}}")
  private String clusterSecurityTokenInterceptorClassname;

  @Value("${cluster.security.tables.authorization.enabled:false}")
  private boolean clusterSecurityTablesAuthorizationEnabled;

  @Value("${cluster.security.tables.authorization.opa.base-uri:#{null}}")
  private String clusterSecurityTablesAuthorizationOpaBaseUri;

  // due to springboot lack of yaml list support, lists are represented in yaml as a comma separated
  // string
  @Value("${cluster.tables.allowed-client-name-values:}")
  private List<String> allowedClientNameValues;

  /** Per-database live-table ceiling. Non-positive values disable the create-time quota. */
  @Value("${cluster.tables.max-tables-per-database:0}")
  private long maxTablesPerDatabase;

  /**
   * The property that turns on the tables service's Iceberg REST Catalog protocol under {@code
   * /iceberg}: the config, namespace, table, view, commit, transaction, register and metrics
   * routes, and the error mapping and authentication those routes answer with. Off by default:
   * nothing is then mapped under {@code /iceberg}, none of these routes' beans is created, and a
   * request there is answered as any path the service does not map (a 400 "cannot be resolved by
   * server").
   *
   * <p>Every other {@code cluster.tables.irc.*} setting applies only with this one on: scan
   * planning ({@link #IRC_SCAN_PLANNING_ENABLED}) needs both, and with this one off it is ignored,
   * with a warning at start-up. The register-view route needs this one and an Iceberg line that has
   * the route's request class.
   *
   * <p>Only the value {@code true} (in any letter case) turns them on, as {@code
   * ConditionalOnProperty(havingValue = "true")} reads it: {@code on}, {@code yes} or {@code 1} do
   * not. The routes' beans read the property through that condition; it has no field here, so that
   * nothing reads it another way.
   */
  public static final String IRC_ENABLED = "cluster.tables.irc.enabled";

  /** The property that turns on the tables service's Iceberg REST scan planning routes. */
  public static final String IRC_SCAN_PLANNING_ENABLED = "cluster.tables.irc.scan-planning.enabled";

  /**
   * Whether the tables service serves Iceberg REST scan planning ({@code POST
   * /iceberg/v1/namespaces/{namespace}/tables/{table}/plan}) and the credentials route that goes
   * with it ({@code GET .../tables/{table}/credentials}, an empty list). Off by default: the two
   * routes then answer the 406 of every route the service does not serve, and the config answer
   * does not list them, so a client plans its scans itself, as on the native API. It takes effect
   * only with the Iceberg REST routes on ({@link #IRC_ENABLED}); with those off, nothing is served
   * under {@code /iceberg} whatever this says.
   *
   * <p>Turn it on only where every principal that may load a table may also read its data. A plan
   * answers each planned data file's path, partition values and per-column lower and upper bounds
   * (values from the data), read from the table's manifests with the service's own storage access,
   * and the route asks only for the privilege a table load asks for ({@code GET_TABLE_METADATA}).
   * Whether it should ask for a data-read privilege instead is an open question for the owner.
   */
  @Value("${" + IRC_SCAN_PLANNING_ENABLED + ":false}")
  private boolean clusterTablesIrcScanPlanningEnabled;

  /**
   * The most file scan tasks one plan may hold, when scan planning is on. A scan that plans more is
   * answered {@code failed} and holds no more than that. Must be positive: with scan planning on,
   * the service does not start otherwise.
   */
  @Value("${cluster.tables.irc.scan-planning.max-file-scan-tasks:10000}")
  private int clusterTablesIrcScanPlanningMaxFileScanTasks;

  /**
   * The most scans the service plans at once, when scan planning is on; a plan asked for while that
   * many run is answered 503 with a {@code Retry-After} header. Must be positive: with scan
   * planning on, the service does not start otherwise.
   */
  @Value("${cluster.tables.irc.scan-planning.max-concurrent-plans:4}")
  private int clusterTablesIrcScanPlanningMaxConcurrentPlans;

  /**
   * The longest one plan may take, in milliseconds, when scan planning is on; a plan that takes
   * longer is answered {@code failed}, and its planning is interrupted. Must be positive: with scan
   * planning on, the service does not start otherwise.
   */
  @Value("${cluster.tables.irc.scan-planning.timeout-ms:60000}")
  private long clusterTablesIrcScanPlanningTimeoutMs;

  /**
   * View SQL dialects this deployment accepts on the /v1 views API. A representation, and the
   * source dialect naming one, is rejected unless its dialect is listed here. Defaults to Spark
   * only; supporting another engine is a configuration change rather than a code change.
   */
  @Value("${cluster.tables.views.supported-dialects:spark}")
  private List<String> viewsSupportedDialects;
}
