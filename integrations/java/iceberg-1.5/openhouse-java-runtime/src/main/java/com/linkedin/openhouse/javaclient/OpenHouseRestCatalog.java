package com.linkedin.openhouse.javaclient;

import com.linkedin.openhouse.javaclient.api.SupportsGrantRevoke;
import com.linkedin.openhouse.javaclient.api.SupportsUnlock;
import com.linkedin.openhouse.javaclient.api.UnlockTableTarget;
import com.linkedin.openhouse.tables.client.model.CreateUpdateLockRequestBody.ReasonEnum;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.BaseTable;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.SortOrder;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableOperations;
import org.apache.iceberg.Transaction;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.encryption.EncryptionManager;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.exceptions.NoSuchNamespaceException;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.io.LocationProvider;
import org.apache.iceberg.metrics.MetricsReporter;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.rest.ErrorHandlers;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.rest.RESTClient;
import org.apache.iceberg.rest.ResourcePaths;
import org.apache.iceberg.rest.responses.ListTablesResponse;

/**
 * The OpenHouse catalog plugin as Iceberg's {@link RESTCatalog} over an in-process, REST-shaped
 * client ({@link OpenHouseRestAdapter}) that dispatches each request to today's {@link
 * OpenHouseCatalog}. Selected by {@code catalog-impl}; today's {@link OpenHouseCatalog} is
 * unchanged and stays the default.
 *
 * <p>The backing catalog is built once, in {@link #initialize}, with this catalog's name and the
 * properties the caller passed (so {@code uri}, {@code auth-token}, {@code cluster} and {@code
 * header.*} reach it exactly as they reach today's catalog). {@code RESTSessionCatalog} asks for a
 * client twice (once for the config call, then with the merged properties); both calls get the same
 * adapter.
 *
 * <p>OpenHouse-only features: grants ({@link SupportsGrantRevoke}) and unlock ({@link
 * SupportsUnlock}) have no REST route; they delegate to the backing catalog, so the OpenHouse Spark
 * SQL extensions, which look for these interfaces on the catalog {@code catalog-impl} built, keep
 * working, and the calls carry the same headers (including {@code X-OpenHouse-Client-Capabilities})
 * as today.
 *
 * <p>Where {@code RESTCatalog} answers a call differently from today's catalog, this class keeps
 * today's answer and still goes through the adapter:
 *
 * <ul>
 *   <li>{@link #namespaceExists} and {@link #tableExists} send a {@code HEAD} request (a REST spec
 *       route Iceberg 1.5.2's client does not use); {@code RESTCatalog} would describe the
 *       namespace or load the table instead.
 *   <li>{@link #listTables} with an empty namespace is sent to the backing catalog, which rejects
 *       it with today's message; the REST client would reject it locally as an invalid namespace.
 *   <li>Tables it returns reject a commit whose base is not the table's current metadata, as
 *       today's table operations do; {@code RESTTableOperations} sends such a commit anyway.
 * </ul>
 */
public class OpenHouseRestCatalog extends RESTCatalog
    implements SupportsGrantRevoke, SupportsUnlock {

  private final AtomicReference<OpenHouseRestAdapter> adapter;
  private Object conf;
  private OpenHouseCatalog backing;
  private ResourcePaths paths;
  private Map<String, String> rawProperties;
  private MetricsReporter metricsReporter;

  public OpenHouseRestCatalog() {
    this(new AtomicReference<>());
  }

  private OpenHouseRestCatalog(AtomicReference<OpenHouseRestAdapter> adapter) {
    super(props -> clientFor(adapter));
    this.adapter = adapter;
  }

  private static RESTClient clientFor(AtomicReference<OpenHouseRestAdapter> adapter) {
    OpenHouseRestAdapter client = adapter.get();
    Preconditions.checkState(client != null, "OpenHouse REST adapter is not initialized");
    return client;
  }

  /**
   * The catalog the adapter dispatches to. Overridden by an engine's catalog class to supply that
   * engine's {@link OpenHouseCatalog} (which declares the engine's client capabilities).
   */
  protected OpenHouseCatalog newBackingCatalog() {
    return new OpenHouseCatalog();
  }

  @Override
  public void initialize(String name, Map<String, String> props) {
    Preconditions.checkArgument(props != null, "Invalid configuration: null");
    OpenHouseCatalog catalog = newBackingCatalog();
    if (conf instanceof Configuration) {
      catalog.setConf((Configuration) conf);
    }
    catalog.initialize(name, props);
    this.backing = catalog;
    this.rawProperties = props;
    this.paths = ResourcePaths.forCatalogProperties(props);
    adapter.set(new OpenHouseRestAdapter(catalog));
    super.initialize(name, props);
  }

  /** Keeps the Hadoop configuration for the backing catalog, then hands it to Iceberg. */
  @Override
  public void setConf(Object newConf) {
    this.conf = newConf;
    super.setConf(newConf);
  }

  /** The backing catalog (today's OpenHouse catalog), available after {@link #initialize}. */
  protected OpenHouseCatalog backing() {
    return backing;
  }

  // ------------------------------------------------- today's answers, via the adapter

  /**
   * The properties the caller passed, as today's catalog returns them; {@code RESTCatalog} would
   * return them merged with the adapter's config defaults.
   */
  @Override
  public Map<String, String> properties() {
    return backing.properties();
  }

  /** {@code HEAD v1/namespaces/{ns}}; today's catalog rejects it as unsupported. */
  @Override
  public boolean namespaceExists(Namespace namespace) {
    try {
      adapter
          .get()
          .head(
              paths.namespace(namespace), ImmutableMap.of(), ErrorHandlers.namespaceErrorHandler());
      return true;
    } catch (NoSuchNamespaceException e) {
      return false;
    }
  }

  /**
   * {@code HEAD v1/namespaces/{ns}/tables/{t}}: today's existence check, which does not load table
   * metadata (so it answers for locked or unreadable tables too).
   */
  @Override
  public boolean tableExists(TableIdentifier identifier) {
    try {
      adapter
          .get()
          .head(paths.table(identifier), ImmutableMap.of(), ErrorHandlers.tableErrorHandler());
      return true;
    } catch (NoSuchTableException e) {
      return false;
    }
  }

  @Override
  public List<TableIdentifier> listTables(Namespace namespace) {
    if (!namespace.isEmpty()) {
      return super.listTables(namespace);
    }
    return adapter
        .get()
        .get(
            paths.tables(namespace),
            ListTablesResponse.class,
            ImmutableMap.of(),
            ErrorHandlers.namespaceErrorHandler())
        .identifiers();
  }

  @Override
  public Table loadTable(TableIdentifier identifier) {
    return checkingStaleBase(super.loadTable(identifier));
  }

  @Override
  public TableBuilder buildTable(TableIdentifier identifier, Schema schema) {
    return new CheckingTableBuilder(super.buildTable(identifier, schema));
  }

  @Override
  public Table createTable(
      TableIdentifier identifier,
      Schema schema,
      PartitionSpec spec,
      String location,
      Map<String, String> properties) {
    return buildTable(identifier, schema)
        .withPartitionSpec(spec)
        .withLocation(location)
        .withProperties(properties)
        .create();
  }

  @Override
  public Table createTable(
      TableIdentifier identifier,
      Schema schema,
      PartitionSpec spec,
      Map<String, String> properties) {
    return createTable(identifier, schema, spec, null, properties);
  }

  @Override
  public Table createTable(TableIdentifier identifier, Schema schema, PartitionSpec spec) {
    return createTable(identifier, schema, spec, null, null);
  }

  @Override
  public Table createTable(TableIdentifier identifier, Schema schema) {
    return createTable(identifier, schema, PartitionSpec.unpartitioned(), null, null);
  }

  /** The reporter today's catalog gives its tables: {@code metrics-reporter-impl}, loaded once. */
  private synchronized MetricsReporter metricsReporter() {
    if (metricsReporter == null) {
      metricsReporter = CatalogUtil.loadMetricsReporter(rawProperties);
    }
    return metricsReporter;
  }

  private Table checkingStaleBase(Table table) {
    if (table instanceof BaseTable) {
      BaseTable base = (BaseTable) table;
      return new BaseTable(
          new StaleBaseCheckingOperations(base.operations(), base.name()),
          base.name(),
          metricsReporter());
    }
    return table;
  }

  /**
   * Table operations that refuse a commit whose base is not the current metadata, with the
   * exception and message today's {@code BaseMetastoreTableOperations#commit} uses. Everything else
   * is passed to Iceberg's {@code RESTTableOperations}.
   */
  private static final class StaleBaseCheckingOperations implements TableOperations {
    private final TableOperations delegate;
    private final String tableName;

    private StaleBaseCheckingOperations(TableOperations delegate, String tableName) {
      this.delegate = delegate;
      this.tableName = tableName;
    }

    @Override
    public TableMetadata current() {
      return delegate.current();
    }

    @Override
    public TableMetadata refresh() {
      return delegate.refresh();
    }

    @Override
    public void commit(TableMetadata base, TableMetadata metadata) {
      if (base != delegate.current()) {
        if (base != null) {
          throw new CommitFailedException("Cannot commit: stale table metadata");
        }
        throw new AlreadyExistsException("Table already exists: %s", tableName);
      }
      delegate.commit(base, metadata);
    }

    @Override
    public FileIO io() {
      return delegate.io();
    }

    @Override
    public EncryptionManager encryption() {
      return delegate.encryption();
    }

    @Override
    public String metadataFileLocation(String fileName) {
      return delegate.metadataFileLocation(fileName);
    }

    @Override
    public LocationProvider locationProvider() {
      return delegate.locationProvider();
    }

    @Override
    public TableOperations temp(TableMetadata uncommittedMetadata) {
      return delegate.temp(uncommittedMetadata);
    }

    @Override
    public long newSnapshotId() {
      return delegate.newSnapshotId();
    }
  }

  /** Passes every call to the REST builder; tables it creates check the commit base. */
  private final class CheckingTableBuilder implements TableBuilder {
    private final TableBuilder delegate;

    private CheckingTableBuilder(TableBuilder delegate) {
      this.delegate = delegate;
    }

    @Override
    public TableBuilder withPartitionSpec(PartitionSpec spec) {
      delegate.withPartitionSpec(spec);
      return this;
    }

    @Override
    public TableBuilder withSortOrder(SortOrder sortOrder) {
      delegate.withSortOrder(sortOrder);
      return this;
    }

    @Override
    public TableBuilder withLocation(String location) {
      delegate.withLocation(location);
      return this;
    }

    @Override
    public TableBuilder withProperties(Map<String, String> properties) {
      delegate.withProperties(properties);
      return this;
    }

    @Override
    public TableBuilder withProperty(String key, String value) {
      delegate.withProperty(key, value);
      return this;
    }

    @Override
    public Table create() {
      return checkingStaleBase(delegate.create());
    }

    @Override
    public Transaction createTransaction() {
      return delegate.createTransaction();
    }

    @Override
    public Transaction replaceTransaction() {
      return delegate.replaceTransaction();
    }

    @Override
    public Transaction createOrReplaceTransaction() {
      return delegate.createOrReplaceTransaction();
    }
  }

  // ------------------------------------------------------------------ OpenHouse-only features

  @Override
  public void updateTableAclPolicies(
      TableIdentifier tableIdentifier, boolean isGrant, String privilege, String principal) {
    backing.updateTableAclPolicies(tableIdentifier, isGrant, privilege, principal);
  }

  @Override
  public List<AclPolicyDto> getTableAclPolicies(TableIdentifier tableIdentifier) {
    return backing.getTableAclPolicies(tableIdentifier);
  }

  @Override
  public void updateDatabaseAclPolicies(
      Namespace namespace, boolean isGrant, String privilege, String principal) {
    backing.updateDatabaseAclPolicies(namespace, isGrant, privilege, principal);
  }

  @Override
  public List<AclPolicyDto> getDatabaseAclPolicies(Namespace namespace) {
    return backing.getDatabaseAclPolicies(namespace);
  }

  @Override
  public void unlockTable(UnlockTableTarget target, ReasonEnum reason) {
    backing.unlockTable(target, reason);
  }

  @Override
  public void close() throws IOException {
    try {
      super.close();
    } finally {
      if (metricsReporter != null) {
        metricsReporter.close();
      }
      if (backing != null) {
        backing.close();
      }
    }
  }
}
