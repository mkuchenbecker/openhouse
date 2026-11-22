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
 * Iceberg client sends it as an ordinary property. Until IRC translates it into a policy change
 * (V5-1), an IRC create or commit that sets or removes it is refused with 400 and a message naming
 * the key, and nothing is stored, rather than storing it as a stray property and reporting a
 * success that applied no policy. The native API's handling of the key is unchanged.
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

  /** The refusal every IRC route gives for the key. */
  static void assertPolicyKeyRefusal(String message) {
    assertTrue(message.contains(KEY), message);
    assertTrue(message.contains("Iceberg REST"), message);
    assertTrue(message.contains("policies cannot be set"), message);
  }

  private static CreateUpdateTableRequestBody nativeBody(String db) {
    return newTable(db, "t").toBuilder().policies(null).build();
  }

  private String createNative(CreateUpdateTableRequestBody body) throws Exception {
    created.add(new String[] {body.getDatabaseId(), body.getTableId()});
    post(mvc, body).andExpect(status().isCreated());
    return json(get(mvc, body.getDatabaseId(), body.getTableId()));
  }

  private String stored(String db) throws Exception {
    return StoredMetadata.normalized(catalog, db, "t");
  }

  private void expectRefusedUnchanged(String db, UpdateTableRequest request) throws Exception {
    String before = stored(db);
    MvcResult result = IrcWrite.commit(mvc, db, "t", request);
    assertPolicyKeyRefusal(assertIrcError(result, 400, "BadRequestException").message());
    assertEquals(before, stored(db));
  }

  private static CreateTableRequest.Builder create(boolean staged, Map<String, String> props) {
    CreateTableRequest.Builder builder =
        CreateTableRequest.builder().withName("t").withSchema(SCHEMA).setProperties(props);
    return staged ? builder.stageCreate() : builder;
  }

  // ---------------------------------------------------------------- commit

  @Test
  void aCommitThatSetsThePolicyKeyIsRefusedAndStoresNothing() throws Exception {
    createNative(nativeBody("irc_pk_set"));
    TableMetadata base = IrcWrite.load(mvc, "irc_pk_set", "t");
    expectRefusedUnchanged(
        "irc_pk_set",
        IrcWrite.simple(
            base,
            TableMetadata.buildFrom(base)
                .setProperties(Collections.singletonMap(KEY, PATCH))
                .build()
                .changes()));
    assertFalse(IrcWrite.load(mvc, "irc_pk_set", "t").properties().containsKey(KEY));
  }

  @Test
  void aCommitThatSetsThePolicyKeyWithOtherPropertiesIsRefusedWhole() throws Exception {
    createNative(nativeBody("irc_pk_mixed"));
    TableMetadata base = IrcWrite.load(mvc, "irc_pk_mixed", "t");
    Map<String, String> props = new HashMap<>();
    props.put(KEY, PATCH);
    props.put("user.k", "v");
    expectRefusedUnchanged(
        "irc_pk_mixed",
        IrcWrite.simple(
            base, TableMetadata.buildFrom(base).setProperties(props).build().changes()));
    assertFalse(IrcWrite.load(mvc, "irc_pk_mixed", "t").properties().containsKey("user.k"));
  }

  @Test
  void aCommitThatRemovesThePolicyKeyIsRefused() throws Exception {
    // A table that already holds the key as a property (the native API stores it when sent raw).
    CreateUpdateTableRequestBody body = nativeBody("irc_pk_rm");
    Map<String, String> props = new HashMap<>(body.getTableProperties());
    props.put(KEY, PATCH);
    createNative(body.toBuilder().tableProperties(props).build());
    TableMetadata base = IrcWrite.load(mvc, "irc_pk_rm", "t");
    assertEquals(PATCH, base.properties().get(KEY));
    expectRefusedUnchanged(
        "irc_pk_rm",
        IrcWrite.simple(
            base,
            TableMetadata.buildFrom(base)
                .removeProperties(Collections.singleton(KEY))
                .build()
                .changes()));
  }

  // ---------------------------------------------------------------- create

  @Test
  void aCreateThatSetsThePolicyKeyIsRefusedAndCreatesNothing() throws Exception {
    created.add(new String[] {"irc_pk_create", "t"});
    MvcResult result =
        IrcWrite.create(
            mvc, "irc_pk_create", create(false, Collections.singletonMap(KEY, PATCH)).build());
    assertPolicyKeyRefusal(assertIrcError(result, 400, "BadRequestException").message());
    assertEquals(404, CommitPathHttp.status(get(mvc, "irc_pk_create", "t")));
  }

  @Test
  void aStagedCreateThatSetsThePolicyKeyIsRefused() throws Exception {
    MvcResult result =
        IrcWrite.create(
            mvc, "irc_pk_stage", create(true, Collections.singletonMap(KEY, PATCH)).build());
    assertPolicyKeyRefusal(assertIrcError(result, 400, "BadRequestException").message());
    assertEquals(404, CommitPathHttp.status(get(mvc, "irc_pk_stage", "t")));
  }

  @Test
  void aStagedCreateCompletionThatSetsThePolicyKeyIsRefusedAndCreatesNothing() throws Exception {
    created.add(new String[] {"irc_pk_sc", "t"});
    TableMetadata staged =
        IrcWrite.parsed(
            IrcWrite.create(mvc, "irc_pk_sc", create(true, Collections.emptyMap()).build()));
    UpdateTableRequest completion =
        IrcWrite.createCommit(
            staged,
            TableMetadata.buildFrom(TableMetadata.buildFrom(staged).discardChanges().build())
                .setProperties(Collections.singletonMap(KEY, PATCH))
                .build()
                .changes());
    MvcResult result = IrcWrite.commit(mvc, "irc_pk_sc", "t", completion);
    assertPolicyKeyRefusal(assertIrcError(result, 400, "BadRequestException").message());
    assertEquals(404, CommitPathHttp.status(get(mvc, "irc_pk_sc", "t")));
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
