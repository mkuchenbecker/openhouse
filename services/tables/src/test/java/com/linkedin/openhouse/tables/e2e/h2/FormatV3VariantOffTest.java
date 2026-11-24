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
 * Variant columns on a cluster with the format v3 switch on and {@code
 * cluster.iceberg.format-v3.variant.enabled} at its default, off (work item X5): a variant column
 * is refused on a v3 table, by create and by schema evolution, through the native API and the
 * Iceberg REST routes, naming the property, whatever the file format; an unknown column is taken as
 * with the property on.
 */
@SpringBootTest(properties = SWITCH_ON)
@AutoConfigureMockMvc
@Import(IrcReadRoutesTest.StubDefaults.class)
@ContextConfiguration(initializers = {PropertyOverrideContextInitializer.class})
public class FormatV3VariantOffTest {
  static final String REFUSAL =
      "variant columns are off on this cluster (cluster.iceberg.format-v3.variant.enabled is"
          + " false): a variant column can never be dropped, and the maintenance jobs, which run on"
          + " Spark 3.5, cannot read, compact or apply retention to a table that has one.";

  @Autowired MockMvc mvc;

  private final List<String> created = new ArrayList<>();

  @AfterEach
  void cleanUp() throws Exception {
    for (String db : created) {
      delete(mvc, db, "t");
    }
    created.clear();
  }

  private String db(String name) {
    created.add(name);
    return name;
  }

  private static CreateUpdateTableRequestBody parquetV3(String db) {
    return with(asking(plain(db), "3"), "write.format.default", "parquet");
  }

  private static CreateUpdateTableRequestBody withColumn(
      CreateUpdateTableRequestBody body, String type) throws Exception {
    return body.toBuilder().schema(addColumn(body.getSchema(), "v", type, null, null)).build();
  }

  private static void refused(int status, String message, String db) {
    assertEquals(400, status, message);
    assertEquals(
        "FORMAT_V3_TYPE_NOT_SUPPORTED: column v (variant) of table " + db + ".t: " + REFUSAL,
        message);
  }

  private static String ircMessage(MvcResult result) throws Exception {
    return JsonPath.read(IrcHttp.body(result), "$.error.message");
  }

  private static CreateTableRequest.Builder ircCreate(String type) throws Exception {
    Map<String, String> properties = new HashMap<>(parquetV3("x").getTableProperties());
    String json = plain("x").getSchema();
    Schema schema =
        SchemaParser.fromJson(type == null ? json : addColumn(json, "v", type, null, null));
    return CreateTableRequest.builder().withName("t").withSchema(schema).setProperties(properties);
  }

  @Test
  void aVariantColumnIsRefusedThroughTheNativeApiNamingTheProperty() throws Exception {
    assumeTrue(IcebergV3.supported());
    String db = db("x5off_n");
    ResultActions create = post(mvc, withColumn(parquetV3(db), "variant"));
    refused(status(create), message(create), db);
    // Whatever the file format: the property is checked first.
    ResultActions onOrc = post(mvc, withColumn(asking(plain(db), "3"), "variant"));
    refused(status(onOrc), message(onOrc), db);
    assertEquals(404, status(get(mvc, db, "t")));
    assertEquals(201, status(post(mvc, parquetV3(db))));
    String stored = json(get(mvc, db, "t"));
    ResultActions add = put(mvc, withColumn(bodyFromGet(stored), "variant"));
    refused(status(add), message(add), db);
    assertEquals(stored, json(get(mvc, db, "t")));
    // An unknown column is not behind the property.
    String other = db("x5off_n_unknown");
    assertEquals(201, status(post(mvc, withColumn(parquetV3(other), "unknown"))));
  }

  @Test
  void aVariantColumnIsRefusedThroughTheIcebergRestRoutesNamingTheProperty() throws Exception {
    assumeTrue(IcebergV3.supported());
    String db = db("x5off_i");
    for (boolean staged : new boolean[] {false, true}) {
      CreateTableRequest.Builder request = ircCreate("variant");
      MvcResult create =
          IrcWrite.create(mvc, db, (staged ? request.stageCreate() : request).build());
      refused(create.getResponse().getStatus(), ircMessage(create), db);
    }
    assertEquals(404, status(get(mvc, db, "t")));
    TableMetadata base = IrcWrite.parsed(IrcWrite.create(mvc, db, ircCreate(null).build()));
    Schema evolved =
        SchemaParser.fromJson(
            addColumn(SchemaParser.toJson(base.schema()), "v", "variant", null, null));
    List<MetadataUpdate> changes =
        Arrays.asList(IrcWrite.addSchema(evolved), new MetadataUpdate.SetCurrentSchema(-1));
    MvcResult add = IrcWrite.commit(mvc, db, "t", IrcWrite.simple(base, changes));
    refused(add.getResponse().getStatus(), ircMessage(add), db);
    List<String> names =
        JsonPath.read(str(json(get(mvc, db, "t")), "$.schema"), "$.fields[*].name");
    assertTrue(!names.contains("v"), names.toString());
  }
}
