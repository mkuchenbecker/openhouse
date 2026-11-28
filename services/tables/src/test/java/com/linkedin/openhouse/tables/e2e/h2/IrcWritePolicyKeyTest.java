package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.*;
import static com.linkedin.openhouse.tables.e2e.h2.IrcHttp.assertIrcError;
import static com.linkedin.openhouse.tables.model.TableModelConstants.HEALTH_SCHEMA_LITERAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.linkedin.openhouse.common.schema.IcebergSchemaHelper;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.tables.api.spec.v0.request.CreateUpdateTableRequestBody;
import com.linkedin.openhouse.tables.config.TablesMvcConstants;
import com.linkedin.openhouse.tables.readbridge.ClientCapabilityGate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.rest.requests.CreateTableRequest;
import org.apache.iceberg.rest.requests.UpdateTableRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * The request property {@code updated.openhouse.policy} on the IRC write routes.
 *
 * <p>OpenHouse's Spark policy statements ({@code ALTER TABLE ... SET POLICY}, column tags) set this
 * property; the OpenHouse Java client strips it and merges it into the request's policies. A plain
 * Iceberg client sends it as an ordinary property. The IRC create, staged create, staged-create
 * completion and commit routes do what the client does: the patch is merged onto the stored
 * policies and applied, and the key itself is never stored ({@link IrcPolicyPatchTest} holds the
 * equivalence with the client's native requests). The native API's handling of the key is
 * unchanged.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IrcReadRoutesTest.StubDefaults.class)
@ContextConfiguration(initializers = {PropertyOverrideContextInitializer.class})
public class IrcWritePolicyKeyTest {
  static final String KEY = "updated.openhouse.policy";
  static final String PATCH = "{\"retention\":{\"count\":3,\"granularity\":\"HOUR\"}}";

  private static final Schema SCHEMA =
      IcebergSchemaHelper.getSchemaFromSchemaJson(HEALTH_SCHEMA_LITERAL);

  @Autowired MockMvc mvc;
  @Autowired Catalog catalog;

  private final List<String[]> created = new ArrayList<>();

  @AfterEach
  void cleanUp() throws Exception {
    for (String[] key : created) {
      mvc.perform(
          MockMvcRequestBuilders.delete(tableUrl(key[0], key[1]))
              .header(
                  TablesMvcConstants.HTTP_HEADER_CLIENT_CAPABILITIES,
                  ClientCapabilityGate.COLUMN_DEFAULTS));
    }
    created.clear();
  }

  private static CreateUpdateTableRequestBody nativeBody(String db) {
    return newTable(db, "t").toBuilder().policies(null).build();
  }

  private String createNative(CreateUpdateTableRequestBody body) throws Exception {
    created.add(new String[] {body.getDatabaseId(), body.getTableId()});
    post(mvc, body).andExpect(status().isCreated());
    return json(get(mvc, body.getDatabaseId(), body.getTableId()));
  }

  private static CreateTableRequest.Builder create(boolean staged, Map<String, String> props) {
    CreateTableRequest.Builder builder =
        CreateTableRequest.builder().withName("t").withSchema(SCHEMA).setProperties(props);
    return staged ? builder.stageCreate() : builder;
  }

  // ---------------------------------------------------------------- commit

  @Test
  void aCommitThatSetsThePolicyKeyAppliesItAndStoresNoKey() throws Exception {
    createNative(nativeBody("irc_pk_set"));
    TableMetadata base = IrcWrite.load(mvc, "irc_pk_set", "t");
    TableMetadata after =
        IrcWrite.parsed(
            IrcWrite.commit(
                mvc,
                "irc_pk_set",
                "t",
                IrcWrite.simple(
                    base,
                    TableMetadata.buildFrom(base)
                        .setProperties(Collections.singletonMap(KEY, PATCH))
                        .build()
                        .changes())));
    assertFalse(after.properties().containsKey(KEY));
    assertFalse(IrcWrite.load(mvc, "irc_pk_set", "t").properties().containsKey(KEY));
    assertAppliedRetention(json(get(mvc, "irc_pk_set", "t")));
  }

  /** The patch {@link #PATCH} as the native API reports it once applied. */
  private static void assertAppliedRetention(String getJson) {
    assertEquals(Integer.valueOf(3), read(getJson, "$.policies.retention.count"));
    assertEquals("HOUR", str(getJson, "$.policies.retention.granularity"));
  }

  @Test
  void aCommitThatSetsThePolicyKeyWithOtherPropertiesAppliesBoth() throws Exception {
    createNative(nativeBody("irc_pk_mixed"));
    TableMetadata base = IrcWrite.load(mvc, "irc_pk_mixed", "t");
    Map<String, String> props = new HashMap<>();
    props.put(KEY, PATCH);
    props.put("user.k", "v");
    IrcWrite.parsed(
        IrcWrite.commit(
            mvc,
            "irc_pk_mixed",
            "t",
            IrcWrite.simple(
                base, TableMetadata.buildFrom(base).setProperties(props).build().changes())));
    TableMetadata after = IrcWrite.load(mvc, "irc_pk_mixed", "t");
    assertEquals("v", after.properties().get("user.k"));
    assertFalse(after.properties().containsKey(KEY));
    assertAppliedRetention(json(get(mvc, "irc_pk_mixed", "t")));
  }

  @Test
  void aCommitThatRemovesAStoredPolicyKeyRemovesItAndAppliesNothing() throws Exception {
    // A table that already holds the key as a property (the native API stores it when sent raw).
    CreateUpdateTableRequestBody body = nativeBody("irc_pk_rm");
    Map<String, String> props = new HashMap<>(body.getTableProperties());
    props.put(KEY, PATCH);
    createNative(body.toBuilder().tableProperties(props).build());
    TableMetadata base = IrcWrite.load(mvc, "irc_pk_rm", "t");
    assertEquals(PATCH, base.properties().get(KEY));
    String policiesBefore = base.properties().get("policies");
    // As the OpenHouse client: the key is read from the commit's properties, which no longer hold
    // it, so no patch applies, and the properties sent no longer hold it either.
    IrcWrite.parsed(
        IrcWrite.commit(
            mvc,
            "irc_pk_rm",
            "t",
            IrcWrite.simple(
                base,
                TableMetadata.buildFrom(base)
                    .removeProperties(Collections.singleton(KEY))
                    .build()
                    .changes())));
    TableMetadata after = IrcWrite.load(mvc, "irc_pk_rm", "t");
    assertFalse(after.properties().containsKey(KEY));
    assertEquals(policiesBefore, after.properties().get("policies"));
  }

  // ---------------------------------------------------------------- create

  /** The hour partitioning {@link #PATCH}'s retention needs. */
  private static CreateTableRequest.Builder createPartitioned(
      boolean staged, Map<String, String> props) {
    return create(staged, props)
        .withPartitionSpec(PartitionSpec.builderFor(SCHEMA).hour("timestampCol").build());
  }

  @Test
  void aCreateThatSetsThePolicyKeyCreatesTheTableWithThePolicy() throws Exception {
    created.add(new String[] {"irc_pk_create", "t"});
    TableMetadata after =
        IrcWrite.parsed(
            IrcWrite.create(
                mvc,
                "irc_pk_create",
                createPartitioned(false, Collections.singletonMap(KEY, PATCH)).build()));
    assertFalse(after.properties().containsKey(KEY));
    assertAppliedRetention(json(get(mvc, "irc_pk_create", "t")));
  }

  @Test
  void aStagedCreateThatSetsThePolicyKeyCarriesItAndCreatesNothing() throws Exception {
    TableMetadata staged =
        IrcWrite.parsed(
            IrcWrite.create(
                mvc,
                "irc_pk_stage",
                createPartitioned(true, Collections.singletonMap(KEY, PATCH)).build()));
    // The key rides back to the completion, which applies it; the staged create writes nothing.
    assertEquals(PATCH, staged.properties().get(KEY));
    assertEquals(404, CommitPathHttp.status(get(mvc, "irc_pk_stage", "t")));
  }

  @Test
  void aStagedCreateCompletionThatSetsThePolicyKeyCreatesTheTableWithThePolicy() throws Exception {
    created.add(new String[] {"irc_pk_sc", "t"});
    TableMetadata staged =
        IrcWrite.parsed(
            IrcWrite.create(
                mvc, "irc_pk_sc", createPartitioned(true, Collections.emptyMap()).build()));
    UpdateTableRequest completion =
        IrcWrite.createCommit(
            staged,
            TableMetadata.buildFrom(TableMetadata.buildFrom(staged).discardChanges().build())
                .setProperties(Collections.singletonMap(KEY, PATCH))
                .build()
                .changes());
    IrcWrite.parsed(IrcWrite.commit(mvc, "irc_pk_sc", "t", completion));
    assertFalse(IrcWrite.load(mvc, "irc_pk_sc", "t").properties().containsKey(KEY));
    assertAppliedRetention(json(get(mvc, "irc_pk_sc", "t")));
  }

  // ---------------------------------------------------------------- the other rewritten keys

  /*
   * On a create the OpenHouse client also rewrites two more properties into request fields: the
   * raw "policies" property becomes the request's policies, and "openhouse.tableType" its table
   * type. A plain Iceberg client sends them raw, and the create keeps neither (the server sets
   * both), so a value that would be lost is refused. The values the server itself sets (an empty
   * "policies", PRIMARY_TABLE), which a staged create returns and its completion sends back, are
   * accepted as before.
   */

  @Test
  void aCreateThatSetsPoliciesAsAPropertyIsRefusedAndCreatesNothing() throws Exception {
    created.add(new String[] {"irc_pk_pol", "t"});
    for (boolean staged : new boolean[] {false, true}) {
      MvcResult result =
          IrcWrite.create(
              mvc,
              "irc_pk_pol",
              create(staged, Collections.singletonMap("policies", "{\"sharingEnabled\":true}"))
                  .build());
      String message = assertIrcError(result, 400, "BadRequestException").message();
      assertTrue(message.contains("policies"), message);
      assertTrue(message.contains("Iceberg REST"), message);
      assertEquals(404, CommitPathHttp.status(get(mvc, "irc_pk_pol", "t")));
    }
    // The same through a staged create's completion.
    TableMetadata staged =
        IrcWrite.parsed(
            IrcWrite.create(mvc, "irc_pk_pol", create(true, Collections.emptyMap()).build()));
    UpdateTableRequest completion =
        IrcWrite.createCommit(
            staged,
            TableMetadata.buildFrom(TableMetadata.buildFrom(staged).discardChanges().build())
                .setProperties(Collections.singletonMap("policies", "{\"sharingEnabled\":true}"))
                .build()
                .changes());
    String message =
        assertIrcError(
                IrcWrite.commit(mvc, "irc_pk_pol", "t", completion), 400, "BadRequestException")
            .message();
    assertTrue(message.contains("policies"), message);
    assertEquals(404, CommitPathHttp.status(get(mvc, "irc_pk_pol", "t")));
  }

  @Test
  void aCreateThatAsksForAReplicaTableTypeIsRefusedAndCreatesNothing() throws Exception {
    created.add(new String[] {"irc_pk_type", "t"});
    for (boolean staged : new boolean[] {false, true}) {
      MvcResult result =
          IrcWrite.create(
              mvc,
              "irc_pk_type",
              create(staged, Collections.singletonMap("openhouse.tableType", "REPLICA_TABLE"))
                  .build());
      String message = assertIrcError(result, 400, "BadRequestException").message();
      assertTrue(message.contains("openhouse.tableType"), message);
      assertTrue(message.contains("Iceberg REST"), message);
      assertEquals(404, CommitPathHttp.status(get(mvc, "irc_pk_type", "t")));
    }
  }

  @Test
  void aCreateWithTheValuesTheServerSetsIsAccepted() throws Exception {
    created.add(new String[] {"irc_pk_same", "t"});
    Map<String, String> props = new HashMap<>();
    props.put("policies", "");
    props.put("openhouse.tableType", "PRIMARY_TABLE");
    TableMetadata after =
        IrcWrite.parsed(IrcWrite.create(mvc, "irc_pk_same", create(false, props).build()));
    assertEquals("PRIMARY_TABLE", after.properties().get("openhouse.tableType"));
  }

  // ---------------------------------------------------------------- native: unchanged

  /**
   * The native API keeps its handling of the key (the OpenHouse client strips it before sending, so
   * only a raw request carries it): a create or PUT that carries it stores it as a property.
   */
  @Test
  void theNativeApiStillStoresThePolicyKeyWhenSentRaw() throws Exception {
    CreateUpdateTableRequestBody body = nativeBody("irc_pk_native");
    Map<String, String> props = new HashMap<>(body.getTableProperties());
    props.put(KEY, PATCH);
    String v0 = createNative(body.toBuilder().tableProperties(props).build());
    assertEquals(PATCH, str(v0, "$.tableProperties['" + KEY + "']"));

    CreateUpdateTableRequestBody b = bodyFromGet(v0);
    Map<String, String> changed = new HashMap<>(b.getTableProperties());
    changed.put(KEY, "{\"sharingEnabled\":true}");
    String v1 =
        json(put(mvc, b.toBuilder().tableProperties(changed).build()).andExpect(status().isOk()));
    assertEquals("{\"sharingEnabled\":true}", str(v1, "$.tableProperties['" + KEY + "']"));
  }
}
