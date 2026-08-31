package com.linkedin.openhouse.internal.catalog;

/** Constants used across service and catalog layer. */
public final class CatalogConstants {
  public static final String SNAPSHOTS_JSON_KEY = "snapshotsJsonToBePut";
  public static final String SNAPSHOTS_REFS_KEY = "snapshotsRefs";
  public static final String INTERMEDIATE_SCHEMAS_KEY = "newIntermediateSchemas";
  public static final String SORT_ORDER_KEY = "sortOrder";
  public static final String IS_STAGE_CREATE_KEY = "isStageCreate";
  public static final String IS_STAGE_REPLACE_KEY = "isStageReplace";
  public static final String IS_REPLACE_COMMIT_KEY = "isReplaceCommit";
  public static final String OPENHOUSE_TABLE_VERSION = "openhouse.tableVersion";
  public static final String OPENHOUSE_UUID_KEY = "openhouse.tableUUID";
  public static final String OPENHOUSE_TABLEID_KEY = "openhouse.tableId";
  public static final String OPENHOUSE_DATABASEID_KEY = "openhouse.databaseId";
  public static final String OPENHOUSE_IS_TABLE_REPLICATED_KEY = "openhouse.isTableReplicated";
  public static final String OPENHOUSE_TABLEURI_KEY = "openhouse.tableUri";
  public static final String OPENHOUSE_CLUSTERID_KEY = "openhouse.clusterId";
  public static final String INITIAL_VERSION = "INITIAL_VERSION";
  public static final String LAST_UPDATED_MS = "last-updated-ms";
  public static final String TRANSIENT_RESTORE_PREFIX = "__transient_restore_";
  public static final String TRANSIENT_ADDED_PREFIX = "__transient_added_";
  public static final String APPENDED_SNAPSHOTS = "appended_snapshots";
  public static final String STAGED_SNAPSHOTS = "staged_snapshots";
  public static final String CHERRY_PICKED_SNAPSHOTS = "cherry_picked_snapshots";
  public static final String DELETED_SNAPSHOTS = "deleted_snapshots";

  /** Used to uniquely identify an update towards a table from user side. */
  public static final String COMMIT_KEY = "commitKey";

  /**
   * Marks a commit that arrived through the Iceberg REST facade rather than the whole-document
   * {@code /v1} API.
   *
   * <p>The two protocol-level conflict checks in {@code OpenHouseInternalTableOperations} -- {@code
   * abortIfWriterBaseDivergedFromCatalog} and {@code failIfRetryUpdate} -- exist only because a
   * whole-document client declares its own base in {@link #COMMIT_KEY} and the server has to defend
   * against that declaration being stale. A REST client declares its preconditions as Iceberg
   * {@code UpdateRequirement}s instead, which are checked against the base the server itself just
   * loaded, so re-deriving a base declaration for those two checks would only fabricate a
   * precondition the client never stated. This key is how a commit says so.
   *
   * <p>It is a transient, doCommit-local marker: it is stripped from the property map before the
   * commit is derived, exactly as {@link #COMMIT_KEY} is, so it never reaches a metadata.json. The
   * durable linearization point -- House Tables' metadataLocation compare plus JPA {@code @Version}
   * -- is unaffected and still runs for every commit on both paths.
   */
  public static final String IS_REST_COMMIT_KEY = "isIcebergRestCommit";

  public static final String EVOLVED_SCHEMA_KEY = "evolved.table.schema";

  public static final String RTAS_ENABLED_TABLE_PROP = "replace.enabled";

  public static final String WAP_ENABLED_TABLE_PROP = "write.wap.enabled";

  static final String FEATURE_TOGGLE_STOP_CREATE = "stop_create";

  static final String CLIENT_TABLE_SCHEMA = "client.table.schema";

  private CatalogConstants() {
    // Noop
  }
}
