package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.*;
import static com.linkedin.openhouse.tables.e2e.h2.IrcHttp.assertIrcError;
import static com.linkedin.openhouse.tables.model.IcebergSnapshotsModelTestUtilities.obtainSnapshotRefsFromSnapshot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.linkedin.openhouse.common.schema.IcebergSchemaHelper;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.internal.catalog.CatalogConstants;
import com.linkedin.openhouse.tables.api.spec.v0.request.CreateUpdateTableRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.request.IcebergSnapshotsRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.ClusteringColumn;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.Policies;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.TimePartitionSpec;
import com.linkedin.openhouse.tables.readbridge.ClientCapabilityGate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.SnapshotParser;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.UpdateSchema;
import org.apache.iceberg.rest.requests.UpdateTableRequest;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * The native update validation ({@code TablesApiValidator.validateUpdateTable}, which the native
 * table PUT and snapshots PUT run before the service layer) on IRC commits and replace commits.
 *
 * <p>Each test makes the same violating write through the native API and through IRC, on tables in
 * the same state, and expects the IRC refusal to be the native one: 400, {@code
 * BadRequestException}, the native message, and nothing stored. Same context as {@link
 * IrcWriteRoutesTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IrcReadRoutesTest.StubDefaults.class)
@ContextConfiguration(initializers = {PropertyOverrideContextInitializer.class})
public class IrcWriteNativeValidationTest {
  private static final String RTAS = CatalogConstants.RTAS_ENABLED_TABLE_PROP;
  private static final String WAP = CatalogConstants.WAP_ENABLED_TABLE_PROP;

  @Autowired MockMvc mvc;
  @Autowired org.apache.iceberg.catalog.Catalog catalog;

  private final List<String[]> created = new ArrayList<>();

  @AfterEach
  void cleanUp() throws Exception {
    for (String[] key : created) {
      mvc.perform(
          MockMvcRequestBuilders.delete(tableUrl(key[0], key[1]))
              .header(
                  com.linkedin.openhouse.tables.config.TablesMvcConstants
                      .HTTP_HEADER_CLIENT_CAPABILITIES,
                  ClientCapabilityGate.COLUMN_DEFAULTS));
    }
    created.clear();
  }

  // ---------------------------------------------------------------- fixtures

  /** The native test table with the given policies and extra properties. */
  private static CreateUpdateTableRequestBody body(
      String db, Policies policies, Map<String, String> extra) {
    CreateUpdateTableRequestBody b = newTable(db, "t");
    Map<String, String> props = new HashMap<>(b.getTableProperties());
    props.putAll(extra);
    return b.toBuilder().policies(policies).tableProperties(props).build();
  }

  private String createNative(CreateUpdateTableRequestBody body) throws Exception {
    created.add(new String[] {body.getDatabaseId(), body.getTableId()});
    post(mvc, body).andExpect(status().isCreated());
    return json(get(mvc, body.getDatabaseId(), body.getTableId()));
  }

  private String stored(String db) throws Exception {
    return StoredMetadata.normalized(catalog, db, "t");
  }

  private static Map<String, String> with(Map<String, String> props, String key, String value) {
    Map<String, String> out = new HashMap<>(props);
    out.put(key, value);
    return out;
  }

  /** The native message of a refused write, and that the refusal stored nothing. */
  private String nativeRefusal(String db, ThrowingSupplier write) throws Exception {
    String before = stored(db);
    String message = message(write.get().andExpect(status().isBadRequest()));
    assertEquals(before, stored(db));
    return message;
  }

  private interface ThrowingSupplier {
    org.springframework.test.web.servlet.ResultActions get() throws Exception;
  }

  /** The IRC commit is refused with the native status, type and message, and stores nothing. */
  private void expectNativeRefusal(String db, UpdateTableRequest request, String nativeMessage)
      throws Exception {
    String before = stored(db);
    String message =
        assertIrcError(IrcWrite.commit(mvc, db, "t", request), 400, "BadRequestException")
            .message();
    assertEquals(nativeMessage, message);
    assertEquals(before, stored(db));
  }

  private static UpdateTableRequest setProperty(TableMetadata base, String key, String value) {
    return IrcWrite.simple(
        base,
        TableMetadata.buildFrom(base)
            .setProperties(Collections.singletonMap(key, value))
            .build()
            .changes());
  }

  /**
   * The native replace commit the OpenHouse client sends for replacing {@code db.t} with {@code
   * schema} and {@code spec} plus one appended file: a snapshots PUT with {@code replaceCommit}.
   */
  private org.springframework.test.web.servlet.ResultActions nativeReplace(
      String db,
      Schema schema,
      PartitionSpec spec,
      TimePartitionSpec timePartitioning,
      List<ClusteringColumn> clustering)
      throws Exception {
    TableMetadata base = IrcWrite.load(mvc, db, "t");
    TableMetadata replacement = IrcWrite.replacement(base, schema, spec, Collections.emptyMap());
    Snapshot snap = IrcWrite.append(replacement, "/tmp/" + db + "_native_r.orc");
    TableMetadata after = IrcWrite.withMain(replacement, snap);
    String getJson = json(get(mvc, db, "t"));
    CreateUpdateTableRequestBody inner =
        bodyFromGet(getJson)
            .toBuilder()
            .schema(IcebergSchemaHelper.getSchemaJsonFromSchema(after.schema()))
            .timePartitioning(timePartitioning)
            .clustering(clustering)
            .replaceCommit(true)
            .build();
    return putSnapshots(
        mvc,
        db,
        "t",
        IcebergSnapshotsRequestBody.builder()
            .baseTableVersion(str(getJson, "$.tableLocation"))
            .jsonSnapshots(
                after.snapshots().stream().map(SnapshotParser::toJson).collect(Collectors.toList()))
            .snapshotRefs(obtainSnapshotRefsFromSnapshot(SnapshotParser.toJson(snap)))
            .createUpdateTableRequestBody(inner)
            .build());
  }

  /** The replace commit RESTTableOperations sends for the same replacement. */
  private UpdateTableRequest ircReplace(String db, Schema schema, PartitionSpec spec)
      throws Exception {
    TableMetadata base = IrcWrite.load(mvc, db, "t");
    TableMetadata replacement = IrcWrite.replacement(base, schema, spec, Collections.emptyMap());
    Snapshot snap = IrcWrite.append(replacement, "/tmp/" + db + "_irc_r.orc");
    TableMetadata then =
        IrcWrite.withMain(TableMetadata.buildFrom(replacement).discardChanges().build(), snap);
    return IrcWrite.replace(base, replacement, then.changes());
  }

  // ---------------------------------------------------------------- feature compatibility

  @Test
  void enablingRtasOnAReplicatedTableGetsTheNativeRefusal() throws Exception {
    // newTable carries retention, replication and history policies.
    String db = "irc_v_rtas_repl";
    String v0 = createNative(body(db, newTable(db, "t").getPolicies(), Collections.emptyMap()));
    CreateUpdateTableRequestBody b = bodyFromGet(v0);
    String nativeMessage =
        nativeRefusal(
            db,
            () ->
                put(
                    mvc,
                    b.toBuilder()
                        .tableProperties(with(b.getTableProperties(), RTAS, "true"))
                        .build()));
    assertTrue(nativeMessage.contains("cannot enable RTAS"), nativeMessage);
    assertTrue(nativeMessage.contains("replication"), nativeMessage);

    expectNativeRefusal(db, setProperty(IrcWrite.load(mvc, db, "t"), RTAS, "true"), nativeMessage);
  }

  @Test
  void enablingWapOnAnRtasTableGetsTheNativeRefusal() throws Exception {
    String db = "irc_v_rtas_wap";
    String v0 = createNative(body(db, null, Collections.singletonMap(RTAS, "true")));
    CreateUpdateTableRequestBody b = bodyFromGet(v0);
    String nativeMessage =
        nativeRefusal(
            db,
            () ->
                put(
                    mvc,
                    b.toBuilder()
                        .tableProperties(with(b.getTableProperties(), WAP, "true"))
                        .build()));
    assertTrue(nativeMessage.contains("cannot enable RTAS"), nativeMessage);
    assertTrue(nativeMessage.contains("WAP"), nativeMessage);

    expectNativeRefusal(db, setProperty(IrcWrite.load(mvc, db, "t"), WAP, "true"), nativeMessage);
  }

  @Test
  void theCompatibilityOptOutIsHonouredAsNatively() throws Exception {
    // With the opt-out property set, the native PUT accepts RTAS and WAP together; so does IRC.
    String db = "irc_v_rtas_optout";
    Map<String, String> props = new HashMap<>();
    props.put(RTAS, "true");
    props.put(CatalogConstants.FEATURE_COMPATIBILITY_VALIDATION_DISABLED_TABLE_PROP, "true");
    String v0 = createNative(body("irc_v_rtas_optn", null, props));
    CreateUpdateTableRequestBody b = bodyFromGet(v0);
    put(mvc, b.toBuilder().tableProperties(with(b.getTableProperties(), WAP, "true")).build())
        .andExpect(status().isOk());
    createNative(body(db, null, props));
    TableMetadata after =
        IrcWrite.parsed(
            IrcWrite.commit(mvc, db, "t", setProperty(IrcWrite.load(mvc, db, "t"), WAP, "true")));
    assertEquals("true", after.properties().get(WAP));
  }

  // ---------------------------------------------------------------- schema

  @Test
  void aColumnDuplicatingAnotherCaseInsensitivelyGetsTheNativeRefusal() throws Exception {
    String db = "irc_v_dup_col";
    String v0 = createNative(body(db, null, Collections.emptyMap()));
    CreateUpdateTableRequestBody b = bodyFromGet(v0);
    String nativeMessage =
        nativeRefusal(
            db,
            () ->
                put(
                    mvc,
                    b.toBuilder()
                        .schema(addColumn(b.getSchema(), "ID", Types.StringType.get()))
                        .build()));
    assertTrue(nativeMessage.contains("duplicate column names"), nativeMessage);

    TableMetadata base = IrcWrite.load(mvc, db, "t");
    UpdateSchema update = NativeCtas.table(base, IrcWrite.IO).updateSchema();
    Schema evolved = update.addColumn("ID", Types.StringType.get()).apply();
    expectNativeRefusal(
        db,
        IrcWrite.simple(
            base,
            TableMetadata.buildFrom(base)
                .setCurrentSchema(evolved, evolved.highestFieldId())
                .build()
                .changes()),
        nativeMessage);
  }

  // ---------------------------------------------------------------- replace

  @Test
  void aReplaceWithMoreThanFourClusteringColumnsGetsTheNativeRefusal() throws Exception {
    String db = "irc_v_rtas_clust";
    createNative(body(db, null, Collections.singletonMap(RTAS, "true")));
    TableMetadata base = IrcWrite.load(mvc, db, "t");
    UpdateSchema update = NativeCtas.table(base, IrcWrite.IO).updateSchema();
    for (int i = 1; i <= 5; i++) {
      update.addColumn("c" + i, Types.StringType.get());
    }
    Schema schema = update.apply();
    PartitionSpec.Builder spec = PartitionSpec.builderFor(schema);
    List<ClusteringColumn> clustering = new ArrayList<>();
    for (int i = 1; i <= 5; i++) {
      spec.identity("c" + i);
      clustering.add(ClusteringColumn.builder().columnName("c" + i).build());
    }
    String nativeMessage =
        nativeRefusal(db, () -> nativeReplace(db, schema, spec.build(), null, clustering));
    assertTrue(nativeMessage.contains("max clustering columns supported is 4"), nativeMessage);

    expectNativeRefusal(db, ircReplace(db, schema, spec.build()), nativeMessage);
  }

  @Test
  void aReplaceThatDropsTheTimePartitioningARetentionPolicyNeedsGetsTheNativeRefusal()
      throws Exception {
    String db = "irc_v_rtas_ret";
    Policies retentionOnly = newTable(db, "t").getPolicies().toBuilder().replication(null).build();
    createNative(body(db, retentionOnly, Collections.singletonMap(RTAS, "true")));
    TableMetadata base = IrcWrite.load(mvc, db, "t");
    Schema schema = base.schema();
    String nativeMessage =
        nativeRefusal(
            db, () -> nativeReplace(db, schema, PartitionSpec.unpartitioned(), null, null));
    assertTrue(nativeMessage.contains("retention"), nativeMessage);

    expectNativeRefusal(db, ircReplace(db, schema, PartitionSpec.unpartitioned()), nativeMessage);
  }
}
