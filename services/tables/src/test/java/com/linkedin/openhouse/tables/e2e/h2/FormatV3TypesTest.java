package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.*;
import static com.linkedin.openhouse.tables.e2e.h2.FormatV3.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.jayway.jsonpath.JsonPath;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.tables.api.spec.v0.request.CreateUpdateTableRequestBody;
import com.linkedin.openhouse.tables.formatversion.IcebergV3;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.iceberg.MetadataUpdate;
import org.apache.iceberg.Schema;
import org.apache.iceberg.SchemaParser;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.rest.requests.CreateTableRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The column types that exist only from Iceberg format version 3 on (work item X5), type by type,
 * through the native API and the Iceberg REST routes, on a cluster with {@code
 * cluster.iceberg.format-v3.enabled} on: a create with a column of the type, the column added by
 * schema evolution, and the same on a format v2 table. Schemas are written as schema JSON, the way
 * a client sends them, so the native cases compile and run on both Iceberg lines.
 */
@SpringBootTest(properties = SWITCH_ON)
@AutoConfigureMockMvc
@Import(IrcReadRoutesTest.StubDefaults.class)
@ContextConfiguration(initializers = {PropertyOverrideContextInitializer.class})
public class FormatV3TypesTest {
  /** Every type format v3 adds, as Iceberg writes it in schema JSON. */
  static final List<String> TYPES =
      Collections.unmodifiableList(
          Arrays.asList(
              "timestamp_ns", "timestamptz_ns", "unknown", "variant", "geometry", "geography"));

  @Autowired MockMvc mvc;

  private final List<String> created = new ArrayList<>();

  @AfterEach
  void cleanUp() throws Exception {
    for (String db : created) {
      delete(mvc, db, "t");
    }
    created.clear();
  }

  // ---------------------------------------------------------------- fixtures

  /** A database name for {@code type} in a test; registered for clean-up. */
  private String db(String prefix, String type) {
    String db = prefix + "_" + type;
    created.add(db);
    return db;
  }

  /** What an attempt answered: status and message. */
  static final class Answer {
    final int status;
    final String message;

    Answer(int status, String message) {
      this.status = status;
      this.message = message;
    }

    @Override
    public String toString() {
      return status + " " + message;
    }
  }

  private static Answer answer(ResultActions result) throws Exception {
    return new Answer(status(result), message(result));
  }

  private static Answer answer(MvcResult result) throws Exception {
    String body = IrcHttp.body(result);
    String message;
    try {
      message = JsonPath.read(body, "$.error.message");
    } catch (Exception e) {
      message = body;
    }
    return new Answer(result.getResponse().getStatus(), message);
  }

  private static CreateUpdateTableRequestBody withColumn(
      CreateUpdateTableRequestBody body, String type) throws Exception {
    return body.toBuilder().schema(addColumn(body.getSchema(), "v", type, null, null)).build();
  }

  /** A native create at {@code version} with a column {@code v} of {@code type}. */
  private Answer nativeCreate(String db, String version, String type) throws Exception {
    return answer(post(mvc, withColumn(asking(plain(db), version), type)));
  }

  /** A native table at {@code version}, then a PUT that adds a column {@code v} of {@code type}. */
  private Answer nativeAdd(String db, String version, String type) throws Exception {
    ResultActions made = post(mvc, asking(plain(db), version));
    assertEquals(201, status(made), json(made));
    String stored = json(get(mvc, db, "t"));
    return answer(put(mvc, withColumn(bodyFromGet(stored), type)));
  }

  private static Schema ircSchema(String type) throws Exception {
    String json = plain("x").getSchema();
    return SchemaParser.fromJson(type == null ? json : addColumn(json, "v", type, null, null));
  }

  private static CreateTableRequest.Builder ircCreate(String version, String type)
      throws Exception {
    Map<String, String> properties = new HashMap<>(plain("x").getTableProperties());
    properties.put(FORMAT_VERSION, version);
    return CreateTableRequest.builder()
        .withName("t")
        .withSchema(ircSchema(type))
        .setProperties(properties);
  }

  private Answer ircCreate(String db, String version, String type, boolean staged)
      throws Exception {
    CreateTableRequest.Builder request = ircCreate(version, type);
    return answer(IrcWrite.create(mvc, db, (staged ? request.stageCreate() : request).build()));
  }

  /**
   * An IRC table at {@code version}, then the commit {@code ALTER TABLE ... ADD COLUMN} sends:
   * {@code add-schema} and {@code set-current-schema}.
   */
  private Answer ircAdd(String db, String version, String type) throws Exception {
    TableMetadata base =
        IrcWrite.parsed(IrcWrite.create(mvc, db, ircCreate(version, null).build()));
    Schema evolved =
        SchemaParser.fromJson(addColumn(SchemaParser.toJson(base.schema()), "v", type, null, null));
    List<MetadataUpdate> changes =
        Arrays.asList(IrcWrite.addSchema(evolved), new MetadataUpdate.SetCurrentSchema(-1));
    return answer(IrcWrite.commit(mvc, db, "t", IrcWrite.simple(base, changes)));
  }

  /** The stored table has no column {@code v}: a refused write left nothing behind. */
  private void assertNoColumnV(String db) throws Exception {
    String stored = json(get(mvc, db, "t"));
    assertEquals(200, status(get(mvc, db, "t")), stored);
    List<String> names = JsonPath.read(str(stored, "$.schema"), "$.fields[*].name");
    assertTrue(!names.contains("v"), stored);
  }

  private static void refused(Answer answer, int status, String part) {
    assertEquals(status, answer.status, answer.toString());
    assertTrue(answer.message.contains(part), answer.toString());
  }

  /** Today's refusal of a format v3 type on a format v3 table: named X5, but not variant. */
  private static void refusedOnV3AsToday(String type, Answer answer) {
    if ("variant".equals(type)) {
      // The request validation's schema walk does not know the type and refuses it before any
      // rule of the table; the message does not name X5.
      refused(answer, 400, "schema : Unsupported type: variant");
    } else {
      refused(answer, 400, "FORMAT_V3_TYPE_NOT_SUPPORTED: column v (");
      assertTrue(answer.message.contains("work item X5"), answer.toString());
    }
  }

  // ---------------------------------------------------------------- format v3 tables

  @Test
  void onAV3TableEveryFormatV3TypeIsRefusedThroughTheNativeApi() throws Exception {
    assumeTrue(IcebergV3.supported());
    for (String type : TYPES) {
      String db = db("x5n3", type);
      refusedOnV3AsToday(type, nativeCreate(db, "3", type));
      assertEquals(404, status(get(mvc, db, "t")));
      refusedOnV3AsToday(type, nativeAdd(db, "3", type));
      assertNoColumnV(db);
    }
  }

  @Test
  void onAV3TableEveryFormatV3TypeIsRefusedThroughTheIcebergRestRoutes() throws Exception {
    assumeTrue(IcebergV3.supported());
    for (String type : TYPES) {
      String db = db("x5i3", type);
      refusedOnV3AsToday(type, ircCreate(db, "3", type, false));
      refusedOnV3AsToday(type, ircCreate(db, "3", type, true));
      assertEquals(404, status(get(mvc, db, "t")));
      refusedOnV3AsToday(type, ircAdd(db, "3", type));
      assertNoColumnV(db);
    }
  }

  // ---------------------------------------------------------------- format v2 tables

  /**
   * On a format v2 table every format v3 type is refused, as it always was: on the Iceberg 1.12
   * line by Iceberg's own schema check (an answer of 500, which names the type), except a variant
   * column, which the request validation refuses first (400); on the reference line by the request
   * validation, whose Iceberg cannot parse the type.
   */
  @Test
  void onAV2TableEveryFormatV3TypeIsRefusedAsBeforeThroughTheNativeApi() throws Exception {
    for (String type : TYPES) {
      String db = db("x5n2", type);
      for (Answer answer : new Answer[] {nativeCreate(db, "2", type), nativeAdd(db, "2", type)}) {
        if (!IcebergV3.supported()) {
          refused(answer, 400, "schema : ");
          assertTrue(answer.message.contains(type), answer.toString());
        } else if ("variant".equals(type)) {
          refused(answer, 400, "schema : Unsupported type: variant");
        } else {
          refused(answer, 500, "Invalid schema for v2:");
        }
      }
      assertNoColumnV(db);
    }
  }

  @Test
  void onAV2TableEveryFormatV3TypeIsRefusedAsBeforeThroughTheIcebergRestRoutes() throws Exception {
    assumeTrue(IcebergV3.supported());
    for (String type : TYPES) {
      String db = db("x5i2", type);
      String spelled = ircSchema(type).findField("v").type().toString();
      for (Answer answer :
          new Answer[] {
            ircCreate(db, "2", type, false), ircCreate(db, "2", type, true), ircAdd(db, "2", type)
          }) {
        refused(answer, 500, "Invalid schema for v2:");
        assertTrue(
            answer.message.contains(
                "Invalid type for v: " + spelled + " is not supported until v3"),
            answer.toString());
      }
      assertNoColumnV(db);
    }
  }
}
