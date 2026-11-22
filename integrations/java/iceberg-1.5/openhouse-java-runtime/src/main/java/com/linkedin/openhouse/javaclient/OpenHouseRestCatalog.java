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
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.rest.RESTClient;

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
 */
public class OpenHouseRestCatalog extends RESTCatalog
    implements SupportsGrantRevoke, SupportsUnlock {

  private final AtomicReference<OpenHouseRestAdapter> adapter;
  private Object conf;
  private OpenHouseCatalog backing;

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
      if (backing != null) {
        backing.close();
      }
    }
  }
}
