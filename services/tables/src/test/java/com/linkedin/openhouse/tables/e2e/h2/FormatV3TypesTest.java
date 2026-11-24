package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.*;
import static com.linkedin.openhouse.tables.e2e.h2.FormatV3.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.jayway.jsonpath.JsonPath;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.tables.api.spec.v0.request.CreateUpdateTableRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.ClusteringColumn;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.Policies;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.PolicyTag;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.Retention;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.TimePartitionSpec;
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
  static final String WRITE_FORMAT = "write.format.default";

  /** Every type format v3 adds, as Iceberg writes it in schema JSON. */
  static final List<String> TYPES =
      Collections.unmodifiableList(
          Arrays.asList(
              "timestamp_ns", "timestamptz_ns", "unknown", "variant", "geometry", "geography"));

  /** The types OpenHouse refuses on format v3 tables, with a word of each one's reason. */
  static final Map<String, String> REFUSED;

  static {
    Map<String, String> refused = new java.util.LinkedHashMap<>();
    refused.put("timestamp_ns", "no Spark integration of Iceberg 1.12");
    refused.put("timestamptz_ns", "no Spark integration of Iceberg 1.12");
    refused.put("geometry", "Spark 3.5 cannot read a table with this column");
    refused.put("geography", "Spark 3.5 cannot read a table with this column");
    REFUSED = Collections.unmodifiableMap(refused);
  }

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

  /** The native test table at {@code version}, writing {@code format} ({@code null}: default). */
  private static CreateUpdateTableRequestBody table(String db, String version, String format) {
    CreateUpdateTableRequestBody body = asking(plain(db), version);
    return format == null ? body : with(body, WRITE_FORMAT, format);
  }

  /** A native create at {@code version} with a column {@code v} of {@code type}. */
  private Answer nativeCreate(String db, String version, String type) throws Exception {
    return nativeCreate(db, version, type, null);
  }

  private Answer nativeCreate(String db, String version, String type, String format)
      throws Exception {
    return answer(post(mvc, withColumn(table(db, version, format), type)));
  }

  /** A native table at {@code version}, then a PUT that adds a column {@code v} of {@code type}. */
  private Answer nativeAdd(String db, String version, String type) throws Exception {
    return nativeAdd(db, version, type, null);
  }

  private Answer nativeAdd(String db, String version, String type, String format) throws Exception {
    ResultActions made = post(mvc, table(db, version, format));
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
    return ircCreate(version, type, null);
  }

  private static CreateTableRequest.Builder ircCreate(String version, String type, String format)
      throws Exception {
    Map<String, String> properties = new HashMap<>(plain("x").getTableProperties());
    properties.put(FORMAT_VERSION, version);
    if (format != null) {
      properties.put(WRITE_FORMAT, format);
    }
    return CreateTableRequest.builder()
        .withName("t")
        .withSchema(ircSchema(type))
        .setProperties(properties);
  }

  private Answer ircCreate(String db, String version, String type, boolean staged)
      throws Exception {
    return ircCreate(db, version, type, null, staged);
  }

  private Answer ircCreate(String db, String version, String type, String format, boolean staged)
      throws Exception {
    CreateTableRequest.Builder request = ircCreate(version, type, format);
    return answer(IrcWrite.create(mvc, db, (staged ? request.stageCreate() : request).build()));
  }

  /**
   * An IRC table at {@code version}, then the commit {@code ALTER TABLE ... ADD COLUMN} sends:
   * {@code add-schema} and {@code set-current-schema}.
   */
  private Answer ircAdd(String db, String version, String type) throws Exception {
    return ircAdd(db, version, type, null);
  }

  private Answer ircAdd(String db, String version, String type, String format) throws Exception {
    TableMetadata base =
        IrcWrite.parsed(IrcWrite.create(mvc, db, ircCreate(version, null, format).build()));
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

  /** The refusal of a type OpenHouse does not take on format v3 tables: names it, why and X5. */
  private static void refusedOnV3(String type, Answer answer) {
    refused(answer, 400, "FORMAT_V3_TYPE_NOT_SUPPORTED: column v (");
    assertTrue(answer.message.contains("OpenHouse does not take the type"), answer.toString());
    assertTrue(answer.message.contains(REFUSED.get(type)), answer.toString());
    assertTrue(answer.message.contains("work item X5"), answer.toString());
  }

  /** The refusal of a variant column on a format v3 table that writes ORC. */
  private static void refusedVariantOnOrc(Answer answer) {
    refused(answer, 400, "FORMAT_V3_TYPE_NOT_SUPPORTED: column v (variant) of table ");
    assertTrue(
        answer.message.contains(
            "the table writes ORC data files (write.format.default=orc), and Iceberg's Spark"
                + " integration cannot write a variant column to ORC"),
        answer.toString());
  }

  private static void ok(Answer answer, int status) {
    assertEquals(status, answer.status, answer.toString());
  }

  /** The type of the stored column {@code v}, as the native GET's schema JSON spells it. */
  private String storedType(String db) throws Exception {
    String stored = json(get(mvc, db, "t"));
    List<Object> types = JsonPath.read(str(stored, "$.schema"), "$.fields[?(@.name == 'v')].type");
    assertEquals(1, types.size(), stored);
    return String.valueOf(types.get(0));
  }

  // ---------------------------------------------------------------- format v3 tables

  /**
   * timestamp_ns, timestamptz_ns, geometry and geography are refused on a v3 table through the
   * native API, by create and by schema evolution, with a message that names the type, says why and
   * names X5; nothing is left behind.
   */
  @Test
  void theRefusedTypesAreRefusedOnAV3TableThroughTheNativeApi() throws Exception {
    assumeTrue(IcebergV3.supported());
    for (String type : REFUSED.keySet()) {
      String db = db("x5n3", type);
      // Whatever the file format.
      refusedOnV3(type, nativeCreate(db, "3", type));
      refusedOnV3(type, nativeCreate(db, "3", type, "parquet"));
      assertEquals(404, status(get(mvc, db, "t")));
      refusedOnV3(type, nativeAdd(db, "3", type, "parquet"));
      assertNoColumnV(db);
    }
  }

  @Test
  void theRefusedTypesAreRefusedOnAV3TableThroughTheIcebergRestRoutes() throws Exception {
    assumeTrue(IcebergV3.supported());
    for (String type : REFUSED.keySet()) {
      String db = db("x5i3", type);
      refusedOnV3(type, ircCreate(db, "3", type, "parquet", false));
      refusedOnV3(type, ircCreate(db, "3", type, "parquet", true));
      refusedOnV3(type, ircCreate(db, "3", type, false));
      assertEquals(404, status(get(mvc, db, "t")));
      refusedOnV3(type, ircAdd(db, "3", type, "parquet"));
      assertNoColumnV(db);
    }
  }

  /**
   * An unknown column (Spark's {@code void}) is taken on a v3 table, by create and by schema
   * evolution, through both APIs and whatever the file format: no format stores it.
   */
  @Test
  void anUnknownColumnIsTakenOnAV3Table() throws Exception {
    assumeTrue(IcebergV3.supported());
    String db = db("x5n3c", "unknown");
    ok(nativeCreate(db, "3", "unknown"), 201);
    assertEquals("unknown", storedType(db));
    db = db("x5n3a", "unknown");
    ok(nativeAdd(db, "3", "unknown"), 200);
    assertEquals("unknown", storedType(db));
    db = db("x5i3c", "unknown");
    ok(ircCreate(db, "3", "unknown", false), 200);
    assertEquals("unknown", storedType(db));
    db = db("x5i3s", "unknown");
    ok(ircCreate(db, "3", "unknown", true), 200);
    assertEquals(404, status(get(mvc, db, "t")));
    db = db("x5i3a", "unknown");
    ok(ircAdd(db, "3", "unknown"), 200);
    assertEquals("unknown", storedType(db));
  }

  /**
   * A variant column is taken on a v3 table that writes Parquet, by create and by schema evolution,
   * through both APIs; on a v3 table that writes ORC (the cluster's default) it is refused, with a
   * message that names the column and says what to set.
   */
  @Test
  void aVariantColumnIsTakenOnAV3TableThatWritesParquetAndRefusedOnOrc() throws Exception {
    assumeTrue(IcebergV3.supported());
    String db = db("x5n3c", "variant");
    refusedVariantOnOrc(nativeCreate(db, "3", "variant"));
    refusedVariantOnOrc(nativeCreate(db, "3", "variant", "orc"));
    assertEquals(404, status(get(mvc, db, "t")));
    ok(nativeCreate(db, "3", "variant", "parquet"), 201);
    assertEquals("variant", storedType(db));
    db = db("x5n3a", "variant");
    ok(nativeAdd(db, "3", "variant", "parquet"), 200);
    assertEquals("variant", storedType(db));
    db = db("x5n3o", "variant");
    refusedVariantOnOrc(nativeAdd(db, "3", "variant"));
    assertNoColumnV(db);

    db = db("x5i3c", "variant");
    refusedVariantOnOrc(ircCreate(db, "3", "variant", false));
    refusedVariantOnOrc(ircCreate(db, "3", "variant", true));
    assertEquals(404, status(get(mvc, db, "t")));
    ok(ircCreate(db, "3", "variant", "parquet", false), 200);
    assertEquals("variant", storedType(db));
    db = db("x5i3s", "variant");
    ok(ircCreate(db, "3", "variant", "parquet", true), 200);
    db = db("x5i3a", "variant");
    ok(ircAdd(db, "3", "variant", "parquet"), 200);
    assertEquals("variant", storedType(db));
    db = db("x5i3o", "variant");
    refusedVariantOnOrc(ircAdd(db, "3", "variant"));
    assertNoColumnV(db);
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

  /**
   * A variant column added by schema evolution to a v3 table with data: refused while a branch of
   * the table reads ORC data files (Iceberg's Spark ORC reader cannot read the column from them,
   * whatever the table writes now); taken where the data files are Parquet.
   */
  @Test
  void aVariantColumnIsRefusedOnATableThatStillHasOrcDataFiles() throws Exception {
    assumeTrue(IcebergV3.supported());
    for (String file : new String[] {"orc", "parquet"}) {
      String db = db("x5f" + file, "variant");
      TableMetadata base =
          IrcWrite.parsed(IrcWrite.create(mvc, db, ircCreate("3", null, "parquet").build()));
      TableMetadata withData =
          IrcWrite.parsed(
              IrcWrite.commit(
                  mvc,
                  db,
                  "t",
                  base,
                  IrcWrite.withMain(base, IrcWrite.append(base, "/tmp/" + db + "_a." + file))));
      Schema evolved =
          SchemaParser.fromJson(
              addColumn(SchemaParser.toJson(withData.schema()), "v", "variant", null, null));
      List<MetadataUpdate> changes =
          Arrays.asList(IrcWrite.addSchema(evolved), new MetadataUpdate.SetCurrentSchema(-1));
      Answer ircAnswer = answer(IrcWrite.commit(mvc, db, "t", IrcWrite.simple(withData, changes)));
      String stored = json(get(mvc, db, "t"));
      if ("orc".equals(file)) {
        for (Answer answer :
            new Answer[] {
              ircAnswer, answer(put(mvc, withColumn(bodyFromGet(stored), "variant")))
            }) {
          refused(answer, 400, "FORMAT_V3_TYPE_NOT_SUPPORTED: column v (variant) of table ");
          assertTrue(
              answer.message.contains("the table has ORC data files (")
                  && answer.message.contains("_a.orc)"),
              answer.toString());
        }
        assertNoColumnV(db);
      } else {
        ok(ircAnswer, 200);
        assertEquals("variant", storedType(db));
      }
    }
  }

  /**
   * OpenHouse's retention job runs on Spark 3.5, which cannot read a table with a variant column:
   * such a table may not have a retention policy, by create or by a later policy update. An unknown
   * column does not stop one.
   */
  @Test
  void aTableWithAVariantColumnCannotHaveARetentionPolicy() throws Exception {
    assumeTrue(IcebergV3.supported());
    Policies retention =
        Policies.builder()
            .retention(
                Retention.builder()
                    .count(30)
                    .granularity(TimePartitionSpec.Granularity.DAY)
                    .build())
            .build();
    for (String type : Arrays.asList("variant", "unknown")) {
      String db = db("x5ret", type);
      CreateUpdateTableRequestBody body =
          withColumn(table(db, "3", "parquet"), type)
              .toBuilder()
              .timePartitioning(dayOf("timestampCol"))
              .build();
      Answer withPolicy = answer(post(mvc, body.toBuilder().policies(retention).build()));
      if ("unknown".equals(type)) {
        ok(withPolicy, 201);
        continue;
      }
      refused(withPolicy, 400, "FORMAT_V3_TYPE_NOT_SUPPORTED: column v (variant) of table ");
      assertTrue(
          withPolicy.message.contains(
              "the table has a retention policy, and OpenHouse's retention job runs on Spark 3.5"),
          withPolicy.toString());
      assertEquals(404, status(get(mvc, db, "t")));
      ok(answer(post(mvc, body)), 201);
      String stored = json(get(mvc, db, "t"));
      refused(
          answer(put(mvc, bodyFromGet(stored).toBuilder().policies(retention).build())),
          400,
          "the table has a retention policy");
      assertEquals(stored, json(get(mvc, db, "t")));
    }
  }

  private static final String SORT_BY_V =
      "{\"order-id\":1,\"fields\":[{\"transform\":\"identity\",\"source-id\":7,"
          + "\"direction\":\"asc\",\"null-order\":\"nulls-first\"}]}";

  private static ClusteringColumn clusteringOn(String column) {
    return ClusteringColumn.builder().columnName(column).build();
  }

  private static TimePartitionSpec dayOf(String column) {
    return TimePartitionSpec.builder()
        .columnName(column)
        .granularity(TimePartitionSpec.Granularity.DAY)
        .build();
  }

  /**
   * {@code body}'s schema with a struct column {@code s} whose one field {@code x} is {@code type}.
   */
  private static String withStruct(String schemaJson, String type) throws Exception {
    return addColumn(schemaJson, "s", "string", null, null)
        .replace(
            "\"name\":\"s\",\"required\":false,\"type\":\"string\"",
            "\"name\":\"s\",\"required\":false,\"type\":{\"type\":\"struct\",\"fields\":"
                + "[{\"id\":8,\"name\":\"x\",\"required\":false,\"type\":\""
                + type
                + "\"}]}");
  }

  /**
   * The supported types under OpenHouse's rules on columns, on a v3 table that writes Parquet:
   * neither can be a clustering or time-partitioning column (refused as any other type those rules
   * do not allow), unknown may be a sort column and variant may not (Iceberg cannot order it), both
   * take column tags, keep their casing rule and their schema-evolution rules, and may sit in a
   * struct; a variant column refuses a later switch of the table to ORC.
   */
  @Test
  void theSupportedTypesUnderOpenHousesColumnRules() throws Exception {
    assumeTrue(IcebergV3.supported());
    for (String type : Arrays.asList("unknown", "variant")) {
      String db = db("x5r", type);
      CreateUpdateTableRequestBody body = withColumn(table(db, "3", "parquet"), type);
      String upper = type.toUpperCase(java.util.Locale.ROOT);
      // Clustering: refused by name, as for any type clustering does not allow.
      refused(
          answer(post(mvc, body.toBuilder().clustering(Arrays.asList(clusteringOn("v"))).build())),
          400,
          "Column name v of type " + upper + " is not supported clustering type");
      // Time partitioning: refused by Iceberg's transform, with the answer a double column gets.
      Answer onDouble =
          answer(post(mvc, body.toBuilder().timePartitioning(dayOf("stats")).build()));
      Answer onV = answer(post(mvc, body.toBuilder().timePartitioning(dayOf("v")).build()));
      assertEquals(onDouble.status, onV.status, onDouble + " / " + onV);
      assertTrue(onV.message.contains(type), onV.toString());
      // Sort order: unknown sorts (all nulls); variant cannot be ordered.
      Answer sorted = answer(post(mvc, body.toBuilder().sortOrder(SORT_BY_V).build()));
      if ("unknown".equals(type)) {
        ok(sorted, 201);
        delete(mvc, db, "t");
      } else {
        refused(sorted, 400, "sortOrder : provided " + SORT_BY_V + " is not a valid sort order");
      }
      assertEquals(404, status(get(mvc, db, "t")));

      ok(answer(post(mvc, body)), 201);
      // A column tag, as on any column.
      Map<String, PolicyTag> tags = new HashMap<>();
      tags.put("v", PolicyTag.builder().tags(Collections.singleton(PolicyTag.Tag.PII)).build());
      String stored = json(get(mvc, db, "t"));
      ok(
          answer(
              put(
                  mvc,
                  bodyFromGet(stored)
                      .toBuilder()
                      .policies(Policies.builder().columnTags(tags).build())
                      .build())),
          200);
      stored = json(get(mvc, db, "t"));
      assertEquals(
          Collections.singletonList("PII"),
          JsonPath.read(stored, "$.policies.columnTags.v.tags"),
          stored);
      // A write that spells the column in another case keeps the table's casing.
      ok(
          answer(
              put(
                  mvc,
                  bodyFromGet(stored)
                      .toBuilder()
                      .schema(str(stored, "$.schema").replace("\"name\":\"v\"", "\"name\":\"V\""))
                      .build())),
          200);
      assertEquals(type, storedType(db));
      // No other type, and no drop.
      stored = json(get(mvc, db, "t"));
      refused(
          answer(
              put(
                  mvc,
                  bodyFromGet(stored)
                      .toBuilder()
                      .schema(withType(str(stored, "$.schema"), "v", "string"))
                      .build())),
          400,
          "contains invalid schema evolution");
      refused(
          answer(
              put(
                  mvc,
                  bodyFromGet(stored)
                      .toBuilder()
                      .schema(dropColumn(str(stored, "$.schema"), "v"))
                      .build())),
          400,
          "contains invalid schema evolution");
      // A later switch to ORC: refused for a variant column, taken for an unknown one.
      Answer toOrc = answer(put(mvc, withProp(stored, WRITE_FORMAT, "orc")));
      if ("variant".equals(type)) {
        refusedVariantOnOrc(toOrc);
      } else {
        ok(toOrc, 200);
      }
      assertEquals(type, storedType(db));

      // In a struct: taken on Parquet; a variant refused on ORC there too.
      String nested = db("x5s", type);
      ok(
          answer(
              post(
                  mvc,
                  table(nested, "3", "parquet")
                      .toBuilder()
                      .schema(withStruct(plain(nested).getSchema(), type))
                      .build())),
          201);
      if ("variant".equals(type)) {
        Answer onOrc =
            answer(
                post(
                    mvc,
                    table(db("x5so", type), "3", null)
                        .toBuilder()
                        .schema(withStruct(plain(nested).getSchema(), type))
                        .build()));
        refused(onOrc, 400, "FORMAT_V3_TYPE_NOT_SUPPORTED: column s.x (variant) of table ");
      }
    }
  }

  /**
   * A column default on an unknown or variant column cannot be parsed by Iceberg 1.12 at all (it
   * has no default value for either type): the request validation's second parse of the schema
   * fails with 500, on any table version, as it did before. Turning it into a 400 would change that
   * answer on v2 tables too, so it is pinned here and left as it is.
   */
  @Test
  void aDefaultOnAnUnknownOrVariantColumnIsRefusedByIcebergsParser() throws Exception {
    assumeTrue(IcebergV3.supported());
    for (String type : Arrays.asList("unknown", "variant")) {
      for (String version : new String[] {"3", "2"}) {
        String db = db("x5d" + version, type);
        CreateUpdateTableRequestBody body = table(db, version, "parquet");
        for (String schema :
            new String[] {
              addColumn(body.getSchema(), "v", type, "\"x\"", null),
              addColumn(body.getSchema(), "v", type, null, "\"x\"")
            }) {
          refused(
              answer(post(mvc, body.toBuilder().schema(schema).build())),
              500,
              "Type: " + type + " is not supported");
        }
        assertEquals(404, status(get(mvc, db, "t")));
      }
    }
  }
}
