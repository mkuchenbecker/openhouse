package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.*;
import static com.linkedin.openhouse.tables.e2e.h2.IrcHttp.assertIrcError;
import static com.linkedin.openhouse.tables.model.TableModelConstants.HEALTH_SCHEMA_LITERAL;
import static com.linkedin.openhouse.tables.model.TableModelConstants.HISTORY_POLICY;
import static com.linkedin.openhouse.tables.model.TableModelConstants.REPLICATION_POLICY;
import static com.linkedin.openhouse.tables.model.TableModelConstants.RETENTION_POLICY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.gson.JsonParseException;
import com.linkedin.openhouse.common.audit.AuditHandler;
import com.linkedin.openhouse.common.schema.IcebergSchemaHelper;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.internal.catalog.CatalogConstants;
import com.linkedin.openhouse.tables.api.spec.v0.request.CreateUpdateTableRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.Policies;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.PolicyTag;
import com.linkedin.openhouse.tables.audit.model.OperationStatus;
import com.linkedin.openhouse.tables.audit.model.OperationType;
import com.linkedin.openhouse.tables.audit.model.TableAuditEvent;
import com.linkedin.openhouse.tables.config.TablesMvcConstants;
import com.linkedin.openhouse.tables.readbridge.ClientCapabilityGate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.requests.CreateTableRequest;
import org.apache.iceberg.rest.requests.UpdateTableRequest;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Policy changes through the IRC commit and create routes (V5-1), at the HTTP boundary.
 *
 * <p>OpenHouse's Spark policy statements express a policy change as the table property {@value
 * #KEY} holding a JSON patch ({@code updateProperties().set(KEY, patch)}). The OpenHouse Java
 * client strips that key and merges the patch onto the policies of the table it loaded before it
 * sends the native request; a plain Iceberg client sends the key raw. The IRC routes do on the
 * server what the client did.
 *
 * <p>Equivalence: two identical tables; on one, the native request the OpenHouse client sends for
 * {@code updateProperties().set(KEY, patch)} (its merge is {@link ClientPolicyMerge}); on the
 * other, the IRC commit of the same property update. Both outcomes must be the same: on success,
 * byte-equal stored {@code policies} and the same stored metadata under the golden normalization;
 * on refusal, the same status and message, and nothing stored. Every patch is the exact text an
 * exec node writes ({@link #EXEC_PATCHES}), plus a few raw shapes a {@code SET TBLPROPERTIES} can
 * send.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IrcReadRoutesTest.StubDefaults.class)
@ContextConfiguration(initializers = {PropertyOverrideContextInitializer.class})
public class IrcPolicyPatchTest {
  static final String KEY = "updated.openhouse.policy";

  private static final Schema SCHEMA =
      IcebergSchemaHelper.getSchemaFromSchemaJson(HEALTH_SCHEMA_LITERAL);

  @Autowired MockMvc mvc;
  @Autowired Catalog catalog;
  @Autowired AuditHandler<TableAuditEvent> tableAuditHandler;

  private final List<String[]> created = new ArrayList<>();

  @AfterEach
  void cleanUp() throws Exception {
    for (String[] key : created) {
      CommitPathHttp.unlock(mvc, key[0], key[1]);
      mvc.perform(
          MockMvcRequestBuilders.delete(tableUrl(key[0], key[1]))
              .header(
                  TablesMvcConstants.HTTP_HEADER_CLIENT_CAPABILITIES,
                  ClientCapabilityGate.COLUMN_DEFAULTS));
    }
    created.clear();
  }

  // ---------------------------------------------------------------- the exec nodes' patches

  /**
   * The exact value each Spark 3.5 exec node sets (read from the exec sources; the Spark itest
   * {@code PolicyPatchLiteralsTest} runs each statement and checks the same strings), with the
   * statement that produces it. Spark's parser upper-cases only for matching: a token's text keeps
   * the case it was typed in ({@code TRUE} or {@code true}, {@code PII} or {@code pii}), and a
   * quoted STRING token keeps its quotes where the AST builder uses {@code getText}.
   */
  static final String[][] EXEC_PATCHES = {
    // SetRetentionPolicyExec (Jackson ObjectMapper; count, granularity, timeZone, columnPattern)
    {"RETENTION=24h", "{\"retention\":{\"count\":24,\"granularity\":\"HOUR\"}}"},
    {"RETENTION=30d", "{\"retention\":{\"count\":30,\"granularity\":\"DAY\"}}"},
    {"RETENTION=2m", "{\"retention\":{\"count\":2,\"granularity\":\"MONTH\"}}"},
    {"RETENTION=1y", "{\"retention\":{\"count\":1,\"granularity\":\"YEAR\"}}"},
    {
      "RETENTION=30d ON COLUMN name",
      "{\"retention\":{\"count\":30,\"granularity\":\"DAY\","
          + "\"columnPattern\":{\"columnName\":\"name\",\"pattern\":\"\"}}}"
    },
    {
      "RETENTION=30d ON COLUMN name WHERE PATTERN='yyyy-MM-dd'",
      "{\"retention\":{\"count\":30,\"granularity\":\"DAY\","
          + "\"columnPattern\":{\"columnName\":\"name\",\"pattern\":\"'yyyy-MM-dd'\"}}}"
    },
    {
      "RETENTION=30d WITH TIMEZONE 'America/Los_Angeles' ON COLUMN name WHERE PATTERN='yyyy-MM-dd'",
      "{\"retention\":{\"count\":30,\"granularity\":\"DAY\",\"timeZone\":\"America/Los_Angeles\","
          + "\"columnPattern\":{\"columnName\":\"name\",\"pattern\":\"yyyy-MM-dd\"}}}"
    },
    // SetSharingPolicyExec (string template; the BOOLEAN token as typed)
    {"SHARING=TRUE", "{\"sharingEnabled\": TRUE}"},
    {"SHARING=true", "{\"sharingEnabled\": true}"},
    {"SHARING=FALSE", "{\"sharingEnabled\": FALSE}"},
    // SetHistoryPolicyExec (string template)
    {"HISTORY MAX_AGE=3d", "{\"history\":{\"maxAge\":3,\"granularity\":\"DAY\"}}"},
    {"HISTORY VERSIONS=5", "{\"history\":{\"versions\":5}}"},
    {
      "HISTORY MAX_AGE=24h VERSIONS=5",
      "{\"history\":{\"maxAge\":24,\"granularity\":\"HOUR\",\"versions\":5}}"
    },
    // SetReplicationPolicyExec (string template; the destination STRING token keeps its quotes)
    {
      "REPLICATION=({destination:'a', interval:12h})",
      "{\"replication\":{\"config\":[{\"destination\":\"'a'\",\"interval\":\"12H\"}]}}"
    },
    {
      "REPLICATION=({destination:'a', interval:12h}, {destination:'b'})",
      "{\"replication\":{\"config\":[{\"destination\":\"'a'\",\"interval\":\"12H\"},"
          + "{\"destination\":\"'b'\",\"interval\":\"\"}]}}"
    },
    // UnSetReplicationPolicyExec
    {"UNSET POLICY (REPLICATION)", "{\"replication\": {}}"},
    // SetColumnPolicyTagExec (string template; tags unquoted, joined by ", ")
    {
      "MODIFY COLUMN name SET TAG = (PII, HC)", "{\"columnTags\":{\"name\": {\"tags\": [PII, HC]}}}"
    },
    {"MODIFY COLUMN count SET TAG = (HC)", "{\"columnTags\":{\"count\": {\"tags\": [HC]}}}"},
    {"MODIFY COLUMN name SET TAG = (NONE)", "{\"columnTags\":{\"name\": {\"tags\": []}}}"},
    {"MODIFY COLUMN name SET TAG = (pii)", "{\"columnTags\":{\"name\": {\"tags\": [pii]}}}"},
    {"MODIFY COLUMN nope SET TAG = (PII)", "{\"columnTags\":{\"nope\": {\"tags\": [PII]}}}"},
  };

  /** Raw patches a {@code SET TBLPROPERTIES} can send, outside what the execs write. */
  static final String[][] RAW_PATCHES = {
    {"empty object", "{}"},
    {"sharing as a string", "{\"sharingEnabled\":\"maybe\"}"},
    {
      "several fields at once",
      "{\"sharingEnabled\":true,\"history\":{\"versions\":7},"
          + "\"columnTags\":{\"id\":{\"tags\":[\"PII\"]}}}"
    },
    {"an unknown field", "{\"sharingEnabled\":true,\"somethingElse\":{\"a\":1}}"},
    {"a lock state, which the merge ignores", "{\"lockState\":{\"locked\":true}}"},
  };

  // ---------------------------------------------------------------- starting tables

  /** Hour-partitioned, with every kind of policy already set. */
  private static CreateUpdateTableRequestBody partitionedWithPolicies(String db) {
    Map<String, PolicyTag> tags = new LinkedHashMap<>();
    tags.put("name", tag(PolicyTag.Tag.PII, PolicyTag.Tag.HC));
    tags.put("id", tag(PolicyTag.Tag.HC));
    return newTable(db, "t")
        .toBuilder()
        .policies(
            Policies.builder()
                .retention(RETENTION_POLICY)
                .replication(REPLICATION_POLICY)
                .history(HISTORY_POLICY)
                .columnTags(tags)
                .build())
        .build();
  }

  /** Unpartitioned, with no policies. */
  private static CreateUpdateTableRequestBody unpartitionedWithout(String db) {
    return newTable(db, "t")
        .toBuilder()
        .timePartitioning(null)
        .clustering(null)
        .policies(null)
        .build();
  }

  private static PolicyTag tag(PolicyTag.Tag... tags) {
    return PolicyTag.builder().tags(new LinkedHashSet<>(Arrays.asList(tags))).build();
  }

  interface Start {
    CreateUpdateTableRequestBody body(String db);
  }

  private static final Start PARTITIONED = IrcPolicyPatchTest::partitionedWithPolicies;
  private static final Start UNPARTITIONED = IrcPolicyPatchTest::unpartitionedWithout;

  static Stream<Arguments> patches() {
    List<Arguments> out = new ArrayList<>();
    int i = 0;
    for (String[][] group : new String[][][] {EXEC_PATCHES, RAW_PATCHES}) {
      for (String[] patch : group) {
        out.add(Arguments.of(i, "partitioned, with policies: " + patch[0], "p", patch[1]));
        out.add(Arguments.of(i, "unpartitioned, no policies: " + patch[0], "u", patch[1]));
        i++;
      }
    }
    return out.stream();
  }

  // ---------------------------------------------------------------- helpers

  private void createNative(CreateUpdateTableRequestBody body) throws Exception {
    created.add(new String[] {body.getDatabaseId(), body.getTableId()});
    post(mvc, body).andExpect(status().isCreated());
  }

  private Map<String, String> storedProperties(String db) {
    return catalog.loadTable(TableIdentifier.of(db, "t")).properties();
  }

  private String storedPolicies(String db) {
    return storedProperties(db).get("policies");
  }

  private String stored(String db) throws Exception {
    return StoredMetadata.normalized(catalog, db, "t");
  }

  /**
   * The stored {@code policies} with the golden normalization's one change inside it: the
   * replication schedule, whose minute and hour each table's create draws at random.
   */
  private String normalizedPolicies(String db) {
    return storedPolicies(db)
        .replaceAll("\"cronSchedule\": \"[^\"]*\"", "\"cronSchedule\": \"cron\"");
  }

  private void assertSameStored(String nativeDb, String ircDb) throws Exception {
    assertEquals(normalizedPolicies(nativeDb), normalizedPolicies(ircDb), "stored policies");
    assertFalse(storedProperties(ircDb).containsKey(KEY), "the request key is never stored");
    assertEquals(stored(nativeDb).replace(nativeDb, "DB"), stored(ircDb).replace(ircDb, "DB"));
  }

  /** A native request body JSON with {@code policies} replaced by the client's own. */
  private static String withClientPolicies(String bodyJson, JsonNode policies) throws Exception {
    ObjectNode json = (ObjectNode) StoredMetadata.MAPPER.readTree(bodyJson);
    json.set("policies", policies);
    return StoredMetadata.MAPPER.writeValueAsString(json);
  }

  /**
   * The native table PUT the OpenHouse client sends for {@code updateProperties()} with {@code
   * changes} on the stored table: the stored table as a get-modify-put, the changes in its
   * properties, then the client's rewrite ({@link ClientPolicyMerge}: the key stripped, the
   * policies merged onto the stored {@code policies} property).
   */
  private ResultActions nativeClientPut(String db, Map<String, String> changes) throws Exception {
    CreateUpdateTableRequestBody b = bodyFromGet(json(get(mvc, db, "t")));
    Map<String, String> metadataProperties = new HashMap<>(storedProperties(db));
    metadataProperties.putAll(changes);
    JsonNode policies = ClientPolicyMerge.requestPolicies(metadataProperties);
    Map<String, String> sent = new HashMap<>(b.getTableProperties());
    sent.putAll(changes);
    sent.remove(KEY);
    String content =
        withClientPolicies(b.toBuilder().tableProperties(sent).build().toJson(), policies);
    return mvc.perform(
        MockMvcRequestBuilders.put(tableUrl(db, "t"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(content)
            .accept(MediaType.APPLICATION_JSON));
  }

  private MvcResult ircSet(String db, Map<String, String> changes) throws Exception {
    TableMetadata base = IrcWrite.load(mvc, db, "t");
    return IrcWrite.commit(
        mvc, db, "t", base, TableMetadata.buildFrom(base).setProperties(changes).build());
  }

  /**
   * The native client's request and the IRC commit for the same property update on two identical
   * tables made from {@code start}: the same outcome. Returns the native status.
   */
  private int assertSameOutcome(String name, Start start, Map<String, String> changes)
      throws Exception {
    String nativeDb = "irc_pp_n_" + name;
    String ircDb = "irc_pp_i_" + name;
    createNative(start.body(nativeDb));
    createNative(start.body(ircDb));
    String nativeBefore = stored(nativeDb);
    String ircBefore = stored(ircDb);
    ResultActions nativeResult = nativeClientPut(nativeDb, changes);
    int nativeStatus = CommitPathHttp.status(nativeResult);
    MvcResult irc = ircSet(ircDb, changes);
    if (nativeStatus == 200) {
      IrcWrite.parsed(irc);
      assertSameStored(nativeDb, ircDb);
    } else {
      String nativeMessage = message(nativeResult).replace(nativeDb, "DB");
      String ircMessage =
          assertIrcError(irc, nativeStatus, IRC_TYPES.get(nativeStatus))
              .message()
              .replace(ircDb, "DB");
      if (nativeStatus == 500) {
        // An unexpected failure: the native 500 body puts the exception's class name before its
        // message, the IRC error carries the message alone (the routes' general mapping).
        assertTrue(nativeMessage.endsWith(": " + ircMessage), nativeMessage + " / " + ircMessage);
      } else {
        assertEquals(nativeMessage, ircMessage, "message");
      }
      assertEquals(nativeBefore.replace(nativeDb, "DB"), stored(nativeDb).replace(nativeDb, "DB"));
      assertEquals(ircBefore, stored(ircDb), "a refused IRC commit stores nothing");
    }
    return nativeStatus;
  }

  private int assertSameOutcome(String name, Start start, String patch) throws Exception {
    return assertSameOutcome(name, start, Collections.singletonMap(KEY, patch));
  }

  /** The IRC error type of each native status these tests meet. */
  private static final Map<Integer, String> IRC_TYPES = new HashMap<>();

  static {
    IRC_TYPES.put(400, "BadRequestException");
    IRC_TYPES.put(403, "ForbiddenException");
    IRC_TYPES.put(500, "ServiceFailureException");
  }

  // ---------------------------------------------------------------- equivalence

  /**
   * Every exec's patch (and the raw shapes), on a table that has every kind of policy and on one
   * that has none: the IRC commit has the native client's outcome.
   */
  @ParameterizedTest(name = "{1}")
  @MethodSource("patches")
  void eachPatchHasTheNativeClientsOutcome(int index, String label, String start, String patch)
      throws Exception {
    assertSameOutcome(start + index, start.equals("p") ? PARTITIONED : UNPARTITIONED, patch);
  }

  /** The statements whose patches the native API accepts on these tables are applied. */
  @Test
  void theStatementsThatApplyNativelyApplyThroughIrc() throws Exception {
    assertEquals(200, assertSameOutcome("ok_r", PARTITIONED, EXEC_PATCHES[0][1]));
    assertEquals(200, assertSameOutcome("ok_rc", UNPARTITIONED, EXEC_PATCHES[4][1]));
    assertEquals(200, assertSameOutcome("ok_s", PARTITIONED, EXEC_PATCHES[7][1]));
    assertEquals(200, assertSameOutcome("ok_h", PARTITIONED, EXEC_PATCHES[11][1]));
    assertEquals(200, assertSameOutcome("ok_rep", PARTITIONED, EXEC_PATCHES[13][1]));
    assertEquals(200, assertSameOutcome("ok_unrep", PARTITIONED, EXEC_PATCHES[15][1]));
    assertEquals(200, assertSameOutcome("ok_tag", PARTITIONED, EXEC_PATCHES[16][1]));
    // What the server stored is what the patch says, merged onto the stored policies.
    String policies = storedPolicies("irc_pp_i_ok_tag");
    assertTrue(policies.contains("\"id\""), policies); // the other column's tags are kept
    assertTrue(policies.contains("\"CLUSTER1\""), policies); // so is everything else
    assertTrue(storedPolicies("irc_pp_i_ok_s").contains("\"sharingEnabled\": true"));
    assertTrue(storedPolicies("irc_pp_i_ok_r").contains("\"count\": 24"));
    assertFalse(storedPolicies("irc_pp_i_ok_unrep").contains("CLUSTER1"));
  }

  // ---------------------------------------------------------------- native refusals

  /**
   * Each native refusal of a policy, through the client's request and through IRC: same status and
   * message, nothing stored.
   */
  @Test
  void eachNativeRefusalOfAPolicyIsTheIrcRefusal() throws Exception {
    // Retention: granularity other than the time partitioning's.
    assertEquals(400, assertSameOutcome("no_rgran", PARTITIONED, EXEC_PATCHES[1][1]));
    // Retention on an unpartitioned table without a column.
    assertEquals(400, assertSameOutcome("no_rnocol", UNPARTITIONED, EXEC_PATCHES[1][1]));
    // Retention column on a time-partitioned table.
    assertEquals(400, assertSameOutcome("no_rcolp", PARTITIONED, EXEC_PATCHES[4][1]));
    // Retention on a missing column.
    assertEquals(
        400,
        assertSameOutcome(
            "no_rmiss",
            UNPARTITIONED,
            "{\"retention\":{\"count\":30,\"granularity\":\"DAY\","
                + "\"columnPattern\":{\"columnName\":\"nope\",\"pattern\":\"\"}}}"));
    // Retention time zone on a column that is not a string, and an unknown zone.
    assertEquals(
        400,
        assertSameOutcome(
            "no_rtzcol",
            UNPARTITIONED,
            "{\"retention\":{\"count\":30,\"granularity\":\"DAY\",\"timeZone\":\"UTC\","
                + "\"columnPattern\":{\"columnName\":\"count\",\"pattern\":\"yyyyMMdd\"}}}"));
    assertEquals(
        400,
        assertSameOutcome(
            "no_rtz",
            UNPARTITIONED,
            "{\"retention\":{\"count\":30,\"granularity\":\"DAY\",\"timeZone\":\"Mars/Base\","
                + "\"columnPattern\":{\"columnName\":\"name\",\"pattern\":\"yyyy-MM-dd\"}}}"));
    // Retention without a granularity (bean validation).
    assertEquals(400, assertSameOutcome("no_rnull", PARTITIONED, "{\"retention\":{\"count\":3}}"));
    // History out of bounds: more than three days, fewer than two versions.
    assertEquals(
        400,
        assertSameOutcome(
            "no_hage", PARTITIONED, "{\"history\":{\"maxAge\":5,\"granularity\":\"DAY\"}}"));
    assertEquals(400, assertSameOutcome("no_hver", PARTITIONED, "{\"history\":{\"versions\":1}}"));
    // Replication with an interval the server cannot schedule (any count of hours or days is
    // scheduled; a count that is not a number is not).
    assertNotEquals(
        200,
        assertSameOutcome(
            "no_repint",
            PARTITIONED,
            "{\"replication\":{\"config\":[{\"destination\":\"'a'\",\"interval\":\"xH\"}]}}"));
    // Replication without a destination (bean validation).
    assertEquals(
        400,
        assertSameOutcome(
            "no_repdest", PARTITIONED, "{\"replication\":{\"config\":[{\"interval\":\"12H\"}]}}"));
  }

  /** Replication on a table that enables RTAS: the native feature-compatibility refusal. */
  @Test
  void replicationOnAnRtasTableIsTheNativeIncompatibility() throws Exception {
    Start rtas =
        db -> {
          CreateUpdateTableRequestBody body = unpartitionedWithout(db);
          Map<String, String> props = new HashMap<>(body.getTableProperties());
          props.put(CatalogConstants.RTAS_ENABLED_TABLE_PROP, "true");
          return body.toBuilder().tableProperties(props).build();
        };
    assertEquals(400, assertSameOutcome("no_rtasrep", rtas, EXEC_PATCHES[13][1]));
    assertTrue(
        message(
                nativeClientPut(
                    "irc_pp_n_no_rtasrep", Collections.singletonMap(KEY, EXEC_PATCHES[13][1])))
            .contains("cannot enable"));
  }

  /** A policy change on a locked table: the native lock refusal. */
  @Test
  void aPolicyChangeOnALockedTableIsTheNativeLockRefusal() throws Exception {
    String nativeDb = "irc_pp_n_lock";
    String ircDb = "irc_pp_i_lock";
    createNative(partitionedWithPolicies(nativeDb));
    createNative(partitionedWithPolicies(ircDb));
    lock(mvc, nativeDb, "t").andExpect(status().isCreated());
    lock(mvc, ircDb, "t").andExpect(status().isCreated());
    String before = stored(ircDb);
    // The OpenHouse client cannot even build its request here: its Gson parse of the stored
    // policies fails on the lock's reason (only a SYSTEM_ONLY lock has a fallback parse).
    Map<String, String> properties = new HashMap<>(storedProperties(nativeDb));
    properties.put(KEY, EXEC_PATCHES[7][1]);
    assertThrows(
        JsonParseException.class, () -> ClientPolicyMerge.buildUpdatedPolicies(properties));
    // The native API's refusal of the request it would have sent (stored policies, sharing on).
    CreateUpdateTableRequestBody b = bodyFromGet(json(get(mvc, nativeDb, "t")));
    ResultActions nativeResult =
        put(
            mvc,
            b.toBuilder()
                .policies(b.getPolicies().toBuilder().sharingEnabled(true).build())
                .build());
    assertEquals(400, CommitPathHttp.status(nativeResult));
    assertTrue(message(nativeResult).contains("locked state"), message(nativeResult));
    assertEquals(
        message(nativeResult).replace(nativeDb, "DB"),
        assertIrcError(
                ircSet(ircDb, Collections.singletonMap(KEY, EXEC_PATCHES[7][1])),
                400,
                "BadRequestException")
            .message()
            .replace(ircDb, "DB"));
    assertEquals(before, stored(ircDb));
  }

  /**
   * A patch the client's parse cannot read: the client fails before it sends anything; IRC answers
   * 400 naming the key, and stores nothing.
   */
  @Test
  void anUnreadablePatchIsRefusedNamingTheKey() throws Exception {
    createNative(partitionedWithPolicies("irc_pp_bad"));
    String before = stored("irc_pp_bad");
    String doubleQuotedDestination =
        "{\"replication\":{\"config\":[{\"destination\":\"\"a\"\",\"interval\":\"12H\"}]}}";
    for (String patch : Arrays.asList("{\"retention\":", doubleQuotedDestination, "[1]")) {
      Map<String, String> properties = new HashMap<>(storedProperties("irc_pp_bad"));
      properties.put(KEY, patch);
      assertThrows(
          JsonParseException.class, () -> ClientPolicyMerge.buildUpdatedPolicies(properties));
      String message =
          assertIrcError(
                  ircSet("irc_pp_bad", Collections.singletonMap(KEY, patch)),
                  400,
                  "BadRequestException")
              .message();
      assertTrue(message.contains(KEY), message);
      assertEquals(before, stored("irc_pp_bad"));
    }
  }

  // ---------------------------------------------------------------- combined and racing commits

  /**
   * A patch with other property changes in one commit: one write that stores both, as the client's
   * one PUT does.
   */
  @Test
  void aPatchWithOtherPropertiesIsOneWriteOfBoth() throws Exception {
    Map<String, String> changes = new HashMap<>();
    changes.put(KEY, EXEC_PATCHES[7][1]);
    changes.put("user.k", "v");
    assertEquals(200, assertSameOutcome("both", PARTITIONED, changes));
    assertEquals("v", storedProperties("irc_pp_i_both").get("user.k"));
    assertTrue(storedPolicies("irc_pp_i_both").contains("\"sharingEnabled\": true"));
    assertEquals(2, StoredMetadata.storedVersions(catalog, "irc_pp_i_both", "t").size());
  }

  /**
   * A patch with a schema change in one commit (a transaction that adds a column and tags it): one
   * write, validated against the new schema, as the client's one PUT with the new schema.
   */
  @Test
  void aPatchWithASchemaChangeIsOneWriteValidatedOnTheNewSchema() throws Exception {
    String patch = "{\"columnTags\":{\"added\": {\"tags\": [PII]}}}";
    createNative(partitionedWithPolicies("irc_pp_n_schema"));
    createNative(partitionedWithPolicies("irc_pp_i_schema"));

    CreateUpdateTableRequestBody b = bodyFromGet(json(get(mvc, "irc_pp_n_schema", "t")));
    Map<String, String> metadataProperties = new HashMap<>(storedProperties("irc_pp_n_schema"));
    metadataProperties.put(KEY, patch);
    String content =
        withClientPolicies(
            b.toBuilder()
                .schema(addColumn(b.getSchema(), "added", Types.StringType.get()))
                .build()
                .toJson(),
            ClientPolicyMerge.requestPolicies(metadataProperties));
    mvc.perform(
            MockMvcRequestBuilders.put(tableUrl("irc_pp_n_schema", "t"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(content))
        .andExpect(status().isOk());

    TableMetadata base = IrcWrite.load(mvc, "irc_pp_i_schema", "t");
    Schema evolved =
        NativeCtas.table(base, IrcWrite.IO)
            .updateSchema()
            .addColumn("added", Types.StringType.get())
            .apply();
    IrcWrite.parsed(
        IrcWrite.commit(
            mvc,
            "irc_pp_i_schema",
            "t",
            base,
            TableMetadata.buildFrom(base)
                .setCurrentSchema(evolved, evolved.highestFieldId())
                .setProperties(Collections.singletonMap(KEY, patch))
                .build()));
    assertSameStored("irc_pp_n_schema", "irc_pp_i_schema");
    assertEquals(2, StoredMetadata.storedVersions(catalog, "irc_pp_i_schema", "t").size());
  }

  /**
   * A patch whose retention needs a column a schema change in the same commit drops is refused: the
   * policies are validated against the candidate schema, as the native update validates the
   * request's schema.
   */
  @Test
  void aPatchIsValidatedAgainstTheCommitsSchema() throws Exception {
    createNative(unpartitionedWithout("irc_pp_n_schemav"));
    createNative(unpartitionedWithout("irc_pp_i_schemav"));
    String patch =
        "{\"retention\":{\"count\":30,\"granularity\":\"DAY\","
            + "\"columnPattern\":{\"columnName\":\"added\",\"pattern\":\"\"}}}";
    // Natively and through IRC, retention on a column the commit adds is accepted ...
    CreateUpdateTableRequestBody b = bodyFromGet(json(get(mvc, "irc_pp_n_schemav", "t")));
    Map<String, String> metadataProperties = new HashMap<>(storedProperties("irc_pp_n_schemav"));
    metadataProperties.put(KEY, patch);
    mvc.perform(
            MockMvcRequestBuilders.put(tableUrl("irc_pp_n_schemav", "t"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    withClientPolicies(
                        b.toBuilder()
                            .schema(addColumn(b.getSchema(), "added", Types.StringType.get()))
                            .build()
                            .toJson(),
                        ClientPolicyMerge.requestPolicies(metadataProperties))))
        .andExpect(status().isOk());
    TableMetadata base = IrcWrite.load(mvc, "irc_pp_i_schemav", "t");
    Schema evolved =
        NativeCtas.table(base, IrcWrite.IO)
            .updateSchema()
            .addColumn("added", Types.StringType.get())
            .apply();
    IrcWrite.parsed(
        IrcWrite.commit(
            mvc,
            "irc_pp_i_schemav",
            "t",
            base,
            TableMetadata.buildFrom(base)
                .setCurrentSchema(evolved, evolved.highestFieldId())
                .setProperties(Collections.singletonMap(KEY, patch))
                .build()));
    assertSameStored("irc_pp_n_schemav", "irc_pp_i_schemav");
    // ... and without the column, refused (the stored schema has none).
    MvcResult refused =
        ircSet("irc_pp_i_schemav", Collections.singletonMap(KEY, patch.replace("added", "absent")));
    assertIrcError(refused, 400, "BadRequestException");
  }

  /**
   * Two policy commits built on the same loaded table. The OpenHouse client merges onto the table
   * it loaded and sends the whole policies object with that table's version as base, so the second
   * request is a 409 conflict (the client then reloads and retries). An IRC policy commit asserts
   * only the table UUID and the server merges onto the policies stored when the commit runs, so the
   * second commit is applied on top of the first: both changes are stored, with no conflict.
   */
  @Test
  void twoPolicyCommitsOnTheSameLoadedTableBothApply() throws Exception {
    createNative(partitionedWithPolicies("irc_pp_race"));
    TableMetadata loaded = IrcWrite.load(mvc, "irc_pp_race", "t");
    UpdateTableRequest sharing =
        IrcWrite.simple(
            loaded,
            TableMetadata.buildFrom(loaded)
                .setProperties(Collections.singletonMap(KEY, EXEC_PATCHES[7][1]))
                .build()
                .changes());
    UpdateTableRequest history =
        IrcWrite.simple(
            loaded,
            TableMetadata.buildFrom(loaded)
                .setProperties(Collections.singletonMap(KEY, EXEC_PATCHES[11][1]))
                .build()
                .changes());
    IrcWrite.parsed(IrcWrite.commit(mvc, "irc_pp_race", "t", sharing));
    IrcWrite.parsed(IrcWrite.commit(mvc, "irc_pp_race", "t", history));
    String policies = storedPolicies("irc_pp_race");
    assertTrue(policies.contains("\"sharingEnabled\": true"), policies);
    assertTrue(policies.contains("\"versions\": 5"), policies);
    assertEquals(3, StoredMetadata.storedVersions(catalog, "irc_pp_race", "t").size());

    // The client's two requests from one load: the second is a conflict.
    createNative(partitionedWithPolicies("irc_pp_race_n"));
    String getJson = json(get(mvc, "irc_pp_race_n", "t"));
    Map<String, String> loadedProperties = new HashMap<>(storedProperties("irc_pp_race_n"));
    for (String patch : Arrays.asList(EXEC_PATCHES[7][1], EXEC_PATCHES[11][1])) {
      Map<String, String> metadataProperties = new HashMap<>(loadedProperties);
      metadataProperties.put(KEY, patch);
      int status =
          CommitPathHttp.status(
              mvc.perform(
                  MockMvcRequestBuilders.put(tableUrl("irc_pp_race_n", "t"))
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          withClientPolicies(
                              bodyFromGet(getJson).toJson(),
                              ClientPolicyMerge.requestPolicies(metadataProperties)))));
      assertEquals(patch.equals(EXEC_PATCHES[7][1]) ? 200 : 409, status);
    }
  }

  /** A patch that changes nothing writes no version, as the client's unchanged PUT writes none. */
  @Test
  void aPatchThatChangesNothingWritesNothing() throws Exception {
    // FALSE on a table whose sharing is off: the merged policies are the stored ones.
    assertEquals(200, assertSameOutcome("noop", PARTITIONED, EXEC_PATCHES[9][1]));
    assertEquals(
        StoredMetadata.storedVersions(catalog, "irc_pp_n_noop", "t").size(),
        StoredMetadata.storedVersions(catalog, "irc_pp_i_noop", "t").size());
  }

  // ---------------------------------------------------------------- the key on the table itself

  /**
   * A table that holds the key as a stored property (the native API stores it when sent raw). The
   * client applies whatever the key holds on every commit and strips it, since it reads the key
   * from the table's properties; a commit that removes the key applies nothing and removes it. IRC
   * does the same on the candidate.
   */
  @Test
  void aStoredKeyIsTreatedAsTheClientTreatsIt() throws Exception {
    Start holding =
        db -> {
          CreateUpdateTableRequestBody body = partitionedWithPolicies(db);
          Map<String, String> props = new HashMap<>(body.getTableProperties());
          props.put(KEY, EXEC_PATCHES[7][1]);
          return body.toBuilder().tableProperties(props).build();
        };
    // An unrelated change applies the stored patch and drops the key.
    assertEquals(200, assertSameOutcome("held", holding, Collections.singletonMap("user.k", "v")));
    assertTrue(storedPolicies("irc_pp_i_held").contains("\"sharingEnabled\": true"));

    // Removing the key applies nothing and removes it.
    createNative(holding.body("irc_pp_i_heldrm"));
    String policiesBefore = storedPolicies("irc_pp_i_heldrm");
    TableMetadata base = IrcWrite.load(mvc, "irc_pp_i_heldrm", "t");
    IrcWrite.parsed(
        IrcWrite.commit(
            mvc,
            "irc_pp_i_heldrm",
            "t",
            base,
            TableMetadata.buildFrom(base).removeProperties(Collections.singleton(KEY)).build()));
    assertFalse(storedProperties("irc_pp_i_heldrm").containsKey(KEY));
    assertEquals(policiesBefore, storedPolicies("irc_pp_i_heldrm"));
  }

  // ---------------------------------------------------------------- create

  private static CreateTableRequest.Builder ircCreate(
      String name, boolean partitioned, Map<String, String> properties) {
    CreateTableRequest.Builder builder =
        CreateTableRequest.builder().withName(name).withSchema(SCHEMA).setProperties(properties);
    if (partitioned) {
      builder.withPartitionSpec(
          PartitionSpec.builderFor(SCHEMA)
              .hour("timestampCol")
              .identity("id")
              .truncate("name", 10)
              .build());
    }
    return builder;
  }

  /** The native create the client sends for a create with {@code patch} in its properties. */
  private void nativeClientCreate(CreateUpdateTableRequestBody body, String patch)
      throws Exception {
    created.add(new String[] {body.getDatabaseId(), body.getTableId()});
    Map<String, String> metadataProperties = new HashMap<>(body.getTableProperties());
    metadataProperties.put(KEY, patch);
    mvc.perform(
            MockMvcRequestBuilders.post(
                    String.format("/v1/databases/%s/tables/", body.getDatabaseId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    withClientPolicies(
                        body.toJson(), ClientPolicyMerge.requestPolicies(metadataProperties))))
        .andExpect(status().isCreated());
  }

  /** A create carrying a patch creates the table with the patch as its policies. */
  @Test
  void aCreateWithAPatchStoresWhatTheClientsCreateStores() throws Exception {
    String patch = EXEC_PATCHES[0][1] /* retention 24 HOUR */;
    nativeClientCreate(newTable("irc_pp_n_create", "t").toBuilder().policies(null).build(), patch);
    created.add(new String[] {"irc_pp_i_create", "t"});
    IrcWrite.parsed(
        IrcWrite.create(
            mvc,
            "irc_pp_i_create",
            ircCreate("t", true, withKey(newTable("x", "t").getTableProperties(), patch)).build()));
    assertSameStored("irc_pp_n_create", "irc_pp_i_create");

    // A refused patch refuses the create, with the native message, and creates nothing.
    created.add(new String[] {"irc_pp_i_createno", "t"});
    String nativeMessage =
        message(
            mvc.perform(
                    MockMvcRequestBuilders.post("/v1/databases/irc_pp_n_createno/tables/")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            withClientPolicies(
                                unpartitionedWithout("irc_pp_n_createno").toJson(),
                                ClientPolicyMerge.requestPolicies(
                                    Collections.singletonMap(KEY, EXEC_PATCHES[1][1])))))
                .andExpect(status().isBadRequest()));
    assertEquals(
        nativeMessage.replace("irc_pp_n_createno", "DB"),
        assertIrcError(
                IrcWrite.create(
                    mvc,
                    "irc_pp_i_createno",
                    ircCreate(
                            "t",
                            false,
                            withKey(newTable("x", "t").getTableProperties(), EXEC_PATCHES[1][1]))
                        .build()),
                400,
                "BadRequestException")
            .message()
            .replace("irc_pp_i_createno", "DB"));
    assertEquals(404, CommitPathHttp.status(get(mvc, "irc_pp_i_createno", "t")));
  }

  /**
   * A staged create carrying a patch validates it as the create would, and carries the key back in
   * the staged metadata, so the commit that completes the create (which sends the staged properties
   * back, as {@code RESTSessionCatalog.createChanges} does) applies it. The completed table stores
   * what the client's create stores.
   */
  @Test
  void aStagedCreateWithAPatchIsAppliedByItsCompletion() throws Exception {
    String patch = EXEC_PATCHES[4][1] /* retention 30 DAY on column name */;
    nativeClientCreate(unpartitionedWithout("irc_pp_n_staged"), patch);
    created.add(new String[] {"irc_pp_i_staged", "t"});
    TableMetadata staged =
        IrcWrite.parsed(
            IrcWrite.create(
                mvc,
                "irc_pp_i_staged",
                ircCreate("t", false, withKey(newTable("x", "t").getTableProperties(), patch))
                    .stageCreate()
                    .build()));
    assertEquals(patch, staged.properties().get(KEY));
    IrcWrite.parsed(
        IrcWrite.commit(
            mvc, "irc_pp_i_staged", "t", IrcWrite.createCommit(staged, Collections.emptyList())));
    assertSameStored("irc_pp_n_staged", "irc_pp_i_staged");

    // A refused patch refuses the staged create with the create's message.
    MvcResult refused =
        IrcWrite.create(
            mvc,
            "irc_pp_i_stagedno",
            ircCreate(
                    "t",
                    false,
                    withKey(newTable("x", "t").getTableProperties(), EXEC_PATCHES[1][1]))
                .stageCreate()
                .build());
    assertTrue(assertIrcError(refused, 400, "BadRequestException").message().contains("retention"));
  }

  private static Map<String, String> withKey(Map<String, String> props, String patch) {
    Map<String, String> out = new HashMap<>(props);
    out.put(KEY, patch);
    return out;
  }

  // ---------------------------------------------------------------- audit

  private void expectAudited(String db, OperationType type, OperationStatus status) {
    verify(tableAuditHandler, atLeastOnce())
        .audit(
            argThat(
                (TableAuditEvent event) ->
                    event != null
                        && db.equals(event.getDatabaseName())
                        && "t".equals(event.getTableName())
                        && event.getOperationType() == type
                        && event.getOperationStatus() == status));
  }

  /**
   * An IRC policy change is in the table audit as the client's native request is: an UPDATE, a
   * SUCCESS when applied and a FAILED when a policy rule refuses it.
   */
  @Test
  void anIrcPolicyChangeIsAuditedAsTheNativeOne() throws Exception {
    assertEquals(200, assertSameOutcome("audit", PARTITIONED, EXEC_PATCHES[7][1]));
    expectAudited("irc_pp_n_audit", OperationType.UPDATE, OperationStatus.SUCCESS);
    expectAudited("irc_pp_i_audit", OperationType.UPDATE, OperationStatus.SUCCESS);
    assertEquals(400, assertSameOutcome("auditno", PARTITIONED, EXEC_PATCHES[1][1]));
    expectAudited("irc_pp_n_auditno", OperationType.UPDATE, OperationStatus.FAILED);
    expectAudited("irc_pp_i_auditno", OperationType.UPDATE, OperationStatus.FAILED);
  }
}
