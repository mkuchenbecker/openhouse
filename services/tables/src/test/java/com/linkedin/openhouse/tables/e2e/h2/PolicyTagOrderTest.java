package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.tables.api.spec.v0.request.CreateUpdateTableRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.Policies;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.PolicyTag;
import com.linkedin.openhouse.tables.config.TablesMvcConstants;
import com.linkedin.openhouse.tables.readbridge.ClientCapabilityGate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * The order of a column's policy tags, at the HTTP boundary of both APIs.
 *
 * <p>The tags are a set ({@link PolicyTag}). The service writes them in the declaration order of
 * {@link PolicyTag.Tag}, {@code PII} then {@code HC}, whatever order a request lists them in: in
 * the stored {@code policies} property, in the native API's responses and in the IRC load response
 * (which carries the stored property). Before, the order was a {@code HashSet}'s, which follows the
 * enum constants' identity hash codes and so changed from one service process to the next; these
 * tests passed or failed with the process.
 *
 * <p>A table stored with the tags in the other order (by a service process before the fix) is read
 * as it is and is not rewritten by a read; a request with the same tags, in either order, is not a
 * change of its policies; a real change writes the declaration order.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IrcReadRoutesTest.StubDefaults.class)
@ContextConfiguration(initializers = {PropertyOverrideContextInitializer.class})
public class PolicyTagOrderTest {
  private static final String KEY = "updated.openhouse.policy";
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final List<String> DECLARATION_ORDER = Arrays.asList("PII", "HC");
  private static final List<String> OTHER_ORDER = Arrays.asList("HC", "PII");

  @Autowired MockMvc mvc;
  @Autowired Catalog catalog;

  private final List<String> created = new ArrayList<>();

  @AfterEach
  void cleanUp() throws Exception {
    for (String db : created) {
      mvc.perform(
          MockMvcRequestBuilders.delete(tableUrl(db, "t"))
              .header(
                  TablesMvcConstants.HTTP_HEADER_CLIENT_CAPABILITIES,
                  ClientCapabilityGate.COLUMN_DEFAULTS));
    }
    created.clear();
  }

  // ---------------------------------------------------------------- helpers

  private static PolicyTag tags(PolicyTag.Tag... tags) {
    return PolicyTag.builder().tags(new LinkedHashSet<>(Arrays.asList(tags))).build();
  }

  /** A create body whose column {@code name} has both tags. */
  private static CreateUpdateTableRequestBody withBothTags(String db) {
    CreateUpdateTableRequestBody body = newTable(db, "t");
    Map<String, PolicyTag> columnTags = new LinkedHashMap<>();
    columnTags.put("name", tags(PolicyTag.Tag.PII, PolicyTag.Tag.HC));
    Policies policies =
        (body.getPolicies() == null ? Policies.builder() : body.getPolicies().toBuilder())
            .columnTags(columnTags)
            .build();
    return body.toBuilder().policies(policies).build();
  }

  /** {@code bodyJson} with the tags of column {@code name} listed as {@code order}. */
  private static String withTagsListed(String bodyJson, List<String> order) throws Exception {
    ObjectNode json = (ObjectNode) MAPPER.readTree(bodyJson);
    ArrayNode tags =
        ((ObjectNode) json.path("policies").path("columnTags").path("name")).putArray("tags");
    order.forEach(tags::add);
    return MAPPER.writeValueAsString(json);
  }

  private ResultActions send(String method, String db, String content) throws Exception {
    String url =
        method.equals("POST")
            ? String.format(
                "%s/databases/%s/tables/", ValidationUtilities.CURRENT_MAJOR_VERSION_PREFIX, db)
            : tableUrl(db, "t");
    return mvc.perform(
        (method.equals("POST") ? MockMvcRequestBuilders.post(url) : MockMvcRequestBuilders.put(url))
            .contentType(MediaType.APPLICATION_JSON)
            .content(content)
            .accept(MediaType.APPLICATION_JSON));
  }

  private void createNative(String db, List<String> order) throws Exception {
    created.add(db);
    String response =
        json(
            send("POST", db, withTagsListed(withBothTags(db).toJson(), order))
                .andExpect(status().isCreated()));
    assertEquals(DECLARATION_ORDER, nameTags(MAPPER.readTree(response).path("policies")), response);
  }

  /** The tags of column {@code name} in a policies JSON object, in the order written. */
  private static List<String> nameTags(JsonNode policies) {
    List<String> out = new ArrayList<>();
    policies.path("columnTags").path("name").path("tags").forEach(t -> out.add(t.asText()));
    return out;
  }

  private String storedPolicies(String db) {
    return catalog.loadTable(TableIdentifier.of(db, "t")).properties().get("policies");
  }

  private List<String> storedTags(String db) throws Exception {
    return nameTags(MAPPER.readTree(storedPolicies(db)));
  }

  private List<String> nativeGetTags(String db) throws Exception {
    String body = json(get(mvc, db, "t").andExpect(status().isOk()));
    return nameTags(MAPPER.readTree(body).path("policies"));
  }

  private String ircLoadedPolicies(String db) throws Exception {
    return IrcWrite.load(mvc, db, "t").properties().get("policies");
  }

  private void ircSet(String db, String patch) throws Exception {
    TableMetadata base = IrcWrite.load(mvc, db, "t");
    IrcWrite.parsed(
        IrcWrite.commit(
            mvc,
            db,
            "t",
            base,
            TableMetadata.buildFrom(base)
                .setProperties(Collections.singletonMap(KEY, patch))
                .build()));
  }

  /** The text a service process before the fix may have stored: the tags in the other order. */
  private String storeTheOtherOrder(String db) throws Exception {
    String declared = storedPolicies(db);
    String other =
        declared.replace(
            "\"tags\": [\n        \"PII\",\n        \"HC\"\n      ]",
            "\"tags\": [\n        \"HC\",\n        \"PII\"\n      ]");
    assertNotEquals(declared, other, declared);
    catalog
        .loadTable(TableIdentifier.of(db, "t"))
        .updateProperties()
        .set("policies", other)
        .commit();
    assertEquals(other, storedPolicies(db));
    return other;
  }

  // ---------------------------------------------------------------- the order written

  /** The native create, in either request order: declaration order stored and answered. */
  @Test
  void theNativeApiWritesTheTagsInDeclarationOrder() throws Exception {
    for (List<String> order : Arrays.asList(DECLARATION_ORDER, OTHER_ORDER)) {
      String db = "tag_order_n_" + order.get(0).toLowerCase();
      createNative(db, order);
      assertEquals(DECLARATION_ORDER, storedTags(db), storedPolicies(db));
      assertEquals(DECLARATION_ORDER, nativeGetTags(db));
      assertEquals(storedPolicies(db), ircLoadedPolicies(db));
    }
  }

  /** The IRC commit of the column-tag statement's patch, in either order: declaration order. */
  @Test
  void theIrcCommitWritesTheTagsInDeclarationOrder() throws Exception {
    for (List<String> order : Arrays.asList(DECLARATION_ORDER, OTHER_ORDER)) {
      String db = "tag_order_i_" + order.get(0).toLowerCase();
      created.add(db);
      post(mvc, newTable(db, "t")).andExpect(status().isCreated());
      // The exact text SetColumnPolicyTagExec writes: the tags unquoted, joined by ", ".
      ircSet(db, "{\"columnTags\":{\"name\": {\"tags\": [" + String.join(", ", order) + "]}}}");
      assertEquals(DECLARATION_ORDER, storedTags(db), storedPolicies(db));
      assertEquals(storedPolicies(db), ircLoadedPolicies(db));
      assertEquals(DECLARATION_ORDER, nativeGetTags(db));
    }
  }

  // ---------------------------------------------------------------- set semantics, stored text

  /**
   * A table stored with the other order: both APIs read it, the native answer lists the tags in
   * declaration order, the IRC answer carries the stored text, and no read rewrites it.
   */
  @Test
  void aTableStoredInTheOtherOrderIsReadAndNotRewrittenByARead() throws Exception {
    String db = "tag_order_read";
    createNative(db, DECLARATION_ORDER);
    String other = storeTheOtherOrder(db);
    assertEquals(DECLARATION_ORDER, nativeGetTags(db));
    assertEquals(other, ircLoadedPolicies(db));
    assertEquals(other, storedPolicies(db), "a read rewrites nothing");
  }

  /**
   * The same tags, listed in either order, are the same policy: neither a native PUT nor an IRC
   * commit of them rewrites the stored text, even when it holds the other order; a real change
   * writes the declaration order.
   */
  @Test
  void theSameTagsInAnyOrderAreNotAChangeOfThePolicies() throws Exception {
    String db = "tag_order_same";
    createNative(db, DECLARATION_ORDER);
    String declared = storedPolicies(db);
    for (List<String> order : Arrays.asList(OTHER_ORDER, DECLARATION_ORDER)) {
      String getJson = json(get(mvc, db, "t"));
      send("PUT", db, withTagsListed(bodyFromGet(getJson).toJson(), order))
          .andExpect(status().isOk());
      assertEquals(declared, storedPolicies(db), "native PUT listing " + order);
    }

    String other = storeTheOtherOrder(db);
    for (List<String> order : Arrays.asList(OTHER_ORDER, DECLARATION_ORDER)) {
      String getJson = json(get(mvc, db, "t"));
      send("PUT", db, withTagsListed(bodyFromGet(getJson).toJson(), order))
          .andExpect(status().isOk());
      assertEquals(
          other, storedPolicies(db), "native PUT listing " + order + " on the other order");
      ircSet(db, "{\"columnTags\":{\"name\": {\"tags\": [" + String.join(", ", order) + "]}}}");
      assertEquals(
          other, storedPolicies(db), "IRC commit listing " + order + " on the other order");
    }

    // A real change of the policies: written as this service writes them, tags in declaration
    // order.
    ircSet(db, "{\"columnTags\":{\"id\": {\"tags\": [HC]}}}");
    String changed = storedPolicies(db);
    assertTrue(changed.contains("\"id\""), changed);
    assertEquals(DECLARATION_ORDER, storedTags(db), changed);
  }
}
