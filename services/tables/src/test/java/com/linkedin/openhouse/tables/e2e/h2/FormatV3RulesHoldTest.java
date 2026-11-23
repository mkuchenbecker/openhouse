package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.*;
import static com.linkedin.openhouse.tables.e2e.h2.FormatV3.*;
import static com.linkedin.openhouse.tables.model.IcebergSnapshotsModelTestUtilities.createDummyDataFile;
import static com.linkedin.openhouse.tables.model.IcebergSnapshotsModelTestUtilities.obtainSnapshotRefsFromSnapshot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.internal.catalog.CatalogConstants;
import com.linkedin.openhouse.tables.api.spec.v0.request.CreateUpdateTableRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.request.IcebergSnapshotsRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.ClusteringColumn;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.History;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.Policies;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.PolicyTag;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.Replication;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.ReplicationConfig;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.Retention;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.TimePartitionSpec;
import com.linkedin.openhouse.tables.common.TableType;
import com.linkedin.openhouse.tables.formatversion.IcebergV3;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.SnapshotParser;
import org.apache.iceberg.SnapshotRef;
import org.apache.iceberg.SnapshotRefParser;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Every OpenHouse rule the commit path applies to a table holds unchanged on a format v3 table.
 *
 * <p>Each rule is one scenario, run twice: on a v2 table and on a v3 table created from the same
 * request. The two must answer alike (status and message), leave the same table as the native API
 * reports it (schema, policies, partitioning, clustering, sort order, type, properties), the same
 * number of metadata versions, and the same snapshots and refs by count and name. The scenarios are
 * the rules of the commit path's inventory: policies (retention, column tags, sharing, replication,
 * history), reserved properties, table type, partition and schema evolution, the snapshot reference
 * age bound, locks, the base-version guards, snapshots, branches and write-audit-publish, replace,
 * the capability gate, sort order, rename and drop.
 */
@SpringBootTest(properties = SWITCH_ON)
@AutoConfigureMockMvc
@Import(IrcReadRoutesTest.StubDefaults.class)
@ContextConfiguration(initializers = {PropertyOverrideContextInitializer.class})
public class FormatV3RulesHoldTest {
  @Autowired MockMvc mvc;
  @Autowired Catalog catalog;

  private final List<String> created = new ArrayList<>();

  @AfterEach
  void cleanUp() throws Exception {
    for (String db : created) {
      unlock(mvc, db, "t");
      delete(mvc, db, "t");
      delete(mvc, db, "renamed");
    }
    created.clear();
  }

  /** One rule: the requests it makes on the table {@code db.t}, and its last answer. */
  private interface Scenario {
    ResultActions run(String db) throws Exception;
  }

  private String stored(String db) throws Exception {
    return json(get(mvc, db, "t"));
  }

  private CreateUpdateTableRequestBody current(String db) throws Exception {
    return bodyFromGet(stored(db));
  }

  private ResultActions putPolicies(String db, Policies policies) throws Exception {
    return put(mvc, current(db).toBuilder().policies(policies).build());
  }

  private Policies policies(String db) throws Exception {
    return current(db).getPolicies();
  }

  private ResultActions putSchema(String db, String schema) throws Exception {
    return put(mvc, current(db).toBuilder().schema(schema).build());
  }

  private Snapshot nextSnapshot(String db, String file) throws Exception {
    Table table = catalog.loadTable(TableIdentifier.of(db, "t"));
    return table
        .newAppend()
        .appendFile(createDummyDataFile("/tmp/" + db + "_" + file + ".orc", table.spec()))
        .apply();
  }

  /** A snapshots PUT of the stored snapshots plus {@code added}, with {@code refs}. */
  private ResultActions putSnapshots(
      String db, Snapshot added, Map<String, String> refs, CreateUpdateTableRequestBody body)
      throws Exception {
    List<String> snapshots = new ArrayList<>();
    catalog
        .loadTable(TableIdentifier.of(db, "t"))
        .snapshots()
        .forEach(s -> snapshots.add(SnapshotParser.toJson(s)));
    if (added != null) {
      snapshots.add(SnapshotParser.toJson(added));
    }
    return CommitPathHttp.putSnapshots(
        mvc,
        db,
        "t",
        IcebergSnapshotsRequestBody.builder()
            .baseTableVersion(body.getBaseTableVersion())
            .jsonSnapshots(snapshots)
            .snapshotRefs(refs)
            .createUpdateTableRequestBody(body)
            .build());
  }

  private ResultActions append(String db, String file) throws Exception {
    Snapshot snapshot = nextSnapshot(db, file);
    return putSnapshots(
        db, snapshot, obtainSnapshotRefsFromSnapshot(SnapshotParser.toJson(snapshot)), current(db));
  }

  private Map<String, String> storedRefs(String db) {
    Map<String, String> refs = new HashMap<>();
    FormatV3.metadata(catalog, db, "t")
        .refs()
        .forEach((name, ref) -> refs.put(name, SnapshotRefParser.toJson(ref)));
    return refs;
  }

  /** Takes the replication policy off the table and turns replace on. */
  private void replaceable(String db) throws Exception {
    assertEquals(200, status(putPolicies(db, policies(db).toBuilder().replication(null).build())));
    assertEquals(
        200,
        status(put(mvc, withProp(stored(db), CatalogConstants.RTAS_ENABLED_TABLE_PROP, "true"))));
  }

  // ---------------------------------------------------------------- the scenarios

  private Map<String, Scenario> scenarios() {
    Map<String, Scenario> rules = new LinkedHashMap<>();
    // Policies.
    rules.put(
        "retention",
        db ->
            putPolicies(
                db,
                policies(db)
                    .toBuilder()
                    .retention(
                        Retention.builder()
                            .count(7)
                            .granularity(TimePartitionSpec.Granularity.HOUR)
                            .build())
                    .build()));
    rules.put(
        "retentionThatDoesNotFitThePartitioning",
        db ->
            putPolicies(
                db,
                policies(db)
                    .toBuilder()
                    .retention(
                        Retention.builder()
                            .count(7)
                            .granularity(TimePartitionSpec.Granularity.DAY)
                            .build())
                    .build()));
    rules.put(
        "columnTags",
        db ->
            putPolicies(
                db,
                policies(db)
                    .toBuilder()
                    .columnTags(
                        Collections.singletonMap(
                            "name",
                            PolicyTag.builder()
                                .tags(Collections.singleton(PolicyTag.Tag.PII))
                                .build()))
                    .build()));
    rules.put(
        "sharing", db -> putPolicies(db, policies(db).toBuilder().sharingEnabled(true).build()));
    rules.put(
        "replication",
        db ->
            putPolicies(
                db,
                policies(db)
                    .toBuilder()
                    .replication(
                        Replication.builder()
                            .config(
                                Collections.singletonList(
                                    ReplicationConfig.builder()
                                        .destination("CLUSTER2")
                                        .interval("1D")
                                        .build()))
                            .build())
                    .build()));
    rules.put(
        "history",
        db ->
            putPolicies(
                db,
                policies(db)
                    .toBuilder()
                    .history(
                        History.builder()
                            .maxAge(2)
                            .granularity(TimePartitionSpec.Granularity.DAY)
                            .versions(5)
                            .build())
                    .build()));
    // Reserved properties and the table type.
    rules.put(
        "reservedPropertyChanged",
        db -> put(mvc, withProp(stored(db), "openhouse.tableId", "other")));
    rules.put(
        "reservedPropertyAdded", db -> put(mvc, withProp(stored(db), "openhouse.added", "x")));
    rules.put("rawPolicies", db -> put(mvc, withProp(stored(db), "policies", "{}")));
    rules.put(
        "tableType",
        db -> put(mvc, current(db).toBuilder().tableType(TableType.REPLICA_TABLE).build()));
    rules.put("userProperty", db -> put(mvc, withProp(stored(db), "user.note", "x")));
    rules.put("nothingChanged", db -> put(mvc, current(db)));
    // Partition and schema evolution.
    rules.put(
        "timePartitioningChanged",
        db ->
            put(
                mvc,
                current(db)
                    .toBuilder()
                    .timePartitioning(
                        TimePartitionSpec.builder()
                            .columnName("timestampCol")
                            .granularity(TimePartitionSpec.Granularity.DAY)
                            .build())
                    .build()));
    rules.put(
        "clusteringChanged",
        db ->
            put(
                mvc,
                current(db)
                    .toBuilder()
                    .clustering(
                        Collections.singletonList(
                            ClusteringColumn.builder().columnName("count").build()))
                    .build()));
    rules.put(
        "columnAdded",
        db ->
            putSchema(
                db,
                CommitPathHttp.addColumn(current(db).getSchema(), "extra", Types.LongType.get())));
    rules.put("columnDropped", db -> putSchema(db, dropColumn(current(db).getSchema(), "stats")));
    rules.put(
        "columnRenamed",
        db -> putSchema(db, current(db).getSchema().replace("\"stats\"", "\"statistics\"")));
    rules.put(
        "typePromoted",
        db ->
            putSchema(
                db,
                current(db)
                    .getSchema()
                    .replace(
                        "\"name\":\"count\",\"required\":true,\"type\":\"int\"",
                        "\"name\":\"count\",\"required\":true,\"type\":\"long\"")));
    rules.put(
        "typeNarrowed",
        db ->
            putSchema(
                db,
                current(db)
                    .getSchema()
                    .replace(
                        "\"name\":\"timeLong\",\"required\":true,\"type\":\"long\"",
                        "\"name\":\"timeLong\",\"required\":true,\"type\":\"int\"")));
    rules.put(
        "duplicateColumn",
        db ->
            putSchema(
                db,
                CommitPathHttp.addColumn(current(db).getSchema(), "NAME", Types.LongType.get())));
    // Table property rules of the catalog.
    rules.put(
        "maxRefAgeOutOfBound",
        db -> put(mvc, withProp(stored(db), "history.expire.max-ref-age-ms", "999999999999")));
    rules.put(
        "capabilityTheClientDoesNotDeclare",
        db -> put(mvc, withProp(stored(db), "required-capability.something", "true")));
    rules.put(
        "sortOrder",
        db ->
            put(
                mvc,
                current(db)
                    .toBuilder()
                    .sortOrder(
                        "{\"order-id\":1,\"fields\":[{\"transform\":\"identity\",\"source-id\":1,"
                            + "\"direction\":\"asc\",\"null-order\":\"nulls-first\"}]}")
                    .build()));
    // Locks.
    rules.put(
        "lockedTableRefusesAnUpdate",
        db -> {
          assertEquals(201, status(lock(mvc, db, "t")));
          return put(mvc, withProp(stored(db), "user.note", "x"));
        });
    rules.put(
        "lockedTableRefusesSnapshots",
        db -> {
          assertEquals(201, status(lock(mvc, db, "t")));
          return append(db, "locked");
        });
    rules.put(
        "lockThenUnlock",
        db -> {
          assertEquals(201, status(lock(mvc, db, "t")));
          return unlock(mvc, db, "t");
        });
    rules.put(
        "lockCannotBeFlippedThroughPolicies",
        db -> {
          assertEquals(201, status(lock(mvc, db, "t")));
          String locked = stored(db);
          return put(mvc, bodyFromGet(locked.replace("\"locked\":true", "\"locked\":false")));
        });
    // The base-version guards.
    rules.put(
        "staleBaseVersion",
        db -> {
          CreateUpdateTableRequestBody stale = withProp(stored(db), "user.a", "1");
          assertEquals(200, status(put(mvc, withProp(stored(db), "user.b", "2"))));
          return put(mvc, stale);
        });
    rules.put(
        "replayedRequest",
        db -> {
          CreateUpdateTableRequestBody once = withProp(stored(db), "user.a", "1");
          assertEquals(200, status(put(mvc, once)));
          return put(mvc, once);
        });
    // Snapshots, branches and write-audit-publish.
    rules.put("append", db -> append(db, "a"));
    rules.put(
        "twoAppends",
        db -> {
          assertEquals(200, status(append(db, "a")));
          return append(db, "b");
        });
    rules.put(
        "branchAndTag",
        db -> {
          assertEquals(200, status(append(db, "a")));
          Map<String, String> refs = storedRefs(db);
          long head = FormatV3.metadata(catalog, db, "t").currentSnapshot().snapshotId();
          refs.put("audit", SnapshotRefParser.toJson(SnapshotRef.branchBuilder(head).build()));
          refs.put("v1", SnapshotRefParser.toJson(SnapshotRef.tagBuilder(head).build()));
          return putSnapshots(db, null, refs, current(db));
        });
    rules.put(
        "commitToABranch",
        db -> {
          assertEquals(200, status(append(db, "a")));
          Snapshot onBranch = nextSnapshot(db, "b");
          Map<String, String> refs = storedRefs(db);
          refs.put(
              "audit",
              SnapshotRefParser.toJson(SnapshotRef.branchBuilder(onBranch.snapshotId()).build()));
          return putSnapshots(db, onBranch, refs, current(db));
        });
    rules.put(
        "stagedWapSnapshot",
        db -> {
          assertEquals(
              200,
              status(
                  put(mvc, withProp(stored(db), CatalogConstants.WAP_ENABLED_TABLE_PROP, "true"))));
          assertEquals(200, status(append(db, "a")));
          // A snapshot no ref points to: staged, as a write-audit-publish write leaves it.
          return putSnapshots(db, nextSnapshot(db, "staged"), storedRefs(db), current(db));
        });
    rules.put(
        "snapshotsOnAStaleBase",
        db -> {
          Snapshot snapshot = nextSnapshot(db, "a");
          CreateUpdateTableRequestBody stale = current(db);
          assertEquals(200, status(put(mvc, withProp(stored(db), "user.b", "2"))));
          return putSnapshots(
              db, snapshot, obtainSnapshotRefsFromSnapshot(SnapshotParser.toJson(snapshot)), stale);
        });
    // Replace.
    rules.put(
        "replaceWithoutReplaceEnabled",
        db -> {
          Snapshot snapshot = nextSnapshot(db, "r");
          return putSnapshots(
              db,
              snapshot,
              obtainSnapshotRefsFromSnapshot(SnapshotParser.toJson(snapshot)),
              current(db).toBuilder().replaceCommit(true).build());
        });
    rules.put(
        "replaceEnabledWhileReplicationIsConfigured",
        db -> put(mvc, withProp(stored(db), CatalogConstants.RTAS_ENABLED_TABLE_PROP, "true")));
    rules.put(
        "replace",
        db -> {
          replaceable(db);
          assertEquals(200, status(append(db, "a")));
          Snapshot snapshot = nextSnapshot(db, "r");
          return putSnapshots(
              db,
              snapshot,
              obtainSnapshotRefsFromSnapshot(SnapshotParser.toJson(snapshot)),
              current(db)
                  .toBuilder()
                  .schema(
                      CommitPathHttp.addColumn(current(db).getSchema(), "r", Types.LongType.get()))
                  .replaceCommit(true)
                  .build());
        });
    rules.put(
        "wapEnabledOnAReplaceableTable",
        db -> {
          replaceable(db);
          return put(mvc, withProp(stored(db), CatalogConstants.WAP_ENABLED_TABLE_PROP, "true"));
        });
    rules.put(
        "stagedReplace",
        db -> {
          replaceable(db);
          return post(mvc, current(db).toBuilder().stageReplace(true).build());
        });
    rules.put(
        "stagedReplaceWithoutReplaceEnabled",
        db -> post(mvc, current(db).toBuilder().stageReplace(true).build()));
    // Create, rename and drop.
    rules.put("createOfAnExistingTable", db -> post(mvc, newTable(db, "t")));
    rules.put("renameCaseOnly", db -> rename(mvc, db, "t", db, "T"));
    rules.put("renameAcrossDatabases", db -> rename(mvc, db, "t", db + "_other", "t"));
    rules.put(
        "rename",
        db -> {
          assertEquals(204, status(rename(mvc, db, "t", db, "renamed")));
          return rename(mvc, db, "renamed", db, "t");
        });
    rules.put(
        "dropThenGet",
        db -> {
          assertEquals(204, status(delete(mvc, db, "t")));
          return get(mvc, db, "t");
        });
    return rules;
  }

  // ---------------------------------------------------------------- the comparison

  @Test
  void everyRuleAnswersOnAV3TableAsOnAV2Table() throws Exception {
    assumeTrue(IcebergV3.supported());
    Map<String, String> differences = new TreeMap<>();
    int index = 0;
    for (Map.Entry<String, Scenario> rule : scenarios().entrySet()) {
      String v2 = "fvr" + index + "_v2";
      String v3 = "fvr" + index + "_v3";
      index++;
      String onV2;
      String onV3;
      try {
        onV2 = outcome(v2, null, rule.getValue());
        onV3 = outcome(v3, "3", rule.getValue());
      } catch (AssertionError e) {
        throw new AssertionError("scenario " + rule.getKey() + ": " + e.getMessage(), e);
      }
      if (!onV2.equals(onV3)) {
        differences.put(rule.getKey(), "\n v2: " + onV2 + "\n v3: " + onV3);
      }
    }
    assertEquals(Collections.emptyMap(), differences);
  }

  /** The scenario's answer on a fresh table, and the table it leaves, with the names removed. */
  private String outcome(String db, String formatVersion, Scenario scenario) throws Exception {
    created.add(db);
    CreateUpdateTableRequestBody body = newTable(db, "t");
    assertEquals(
        201, status(post(mvc, formatVersion == null ? body : asking(body, formatVersion))));
    assertEquals(
        formatVersion == null ? 2 : 3, FormatV3.metadata(catalog, db, "t").formatVersion());
    ResultActions answer = scenario.run(db);
    StringBuilder out = new StringBuilder();
    out.append(status(answer));
    String body2xx = status(answer) / 100 == 2 ? "" : message(answer);
    out.append(' ').append(body2xx);
    ResultActions after = get(mvc, db, "t");
    out.append(" | table ").append(status(after));
    if (status(after) == 200) {
      out.append(' ').append(reported(json(after)));
      TableMetadata metadata = FormatV3.metadata(catalog, db, "t");
      out.append(" | versions ").append(metadata.previousFiles().size() + 1);
      out.append(" | snapshots ").append(metadata.snapshots().size());
      out.append(" | refs ").append(new TreeMap<>(metadata.refs()).keySet());
      out.append(" | schemas ").append(metadata.schemas().size());
      out.append(" | sort ").append(metadata.sortOrder().orderId());
    }
    return FormatV3.withoutUuids(out.toString().replace(db, "DB"));
  }

  /** The table as the native API reports it, without what differs from table to table. */
  private static String reported(String getJson) throws Exception {
    ObjectNode table = (ObjectNode) StoredMetadata.MAPPER.readTree(getJson);
    table.remove(
        java.util.Arrays.asList(
            "tableUUID", "tableLocation", "tableVersion", "lastModifiedTime", "creationTime"));
    ObjectNode properties = (ObjectNode) table.get("tableProperties");
    List<String> serverStamped = new ArrayList<>();
    for (Iterator<String> names = properties.fieldNames(); names.hasNext(); ) {
      String name = names.next();
      if (name.startsWith("openhouse.") || name.equals("policies")) {
        serverStamped.add(name);
      }
    }
    properties.remove(serverStamped);
    JsonNode policies = table.get("policies");
    if (policies != null
        && policies.has("replication")
        && policies.get("replication").has("config")) {
      // The replication schedule is drawn at random by the server.
      policies
          .get("replication")
          .get("config")
          .forEach(c -> ((ObjectNode) c).remove("cronSchedule"));
    }
    if (policies != null && policies.has("lockState") && policies.get("lockState").isObject()) {
      ((ObjectNode) policies.get("lockState")).remove("creationTime");
    }
    return StoredMetadata.MAPPER
        .writer()
        .without(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT)
        .writeValueAsString(table);
  }
}
