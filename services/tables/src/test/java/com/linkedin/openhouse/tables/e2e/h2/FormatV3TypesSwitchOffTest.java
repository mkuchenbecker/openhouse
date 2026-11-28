package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.*;
import static com.linkedin.openhouse.tables.e2e.h2.FormatV3.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.tables.api.spec.v0.request.CreateUpdateTableRequestBody;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * The format v3 column types on a cluster with the switch off, as in production: every one of them
 * is refused by the request validation with the answer LinkedIn main gave, whose Iceberg 1.5.2
 * schema parser did not know them (400, {@code schema : Cannot parse type string to primitive:
 * <type>}), whatever format version the request asks for, on both Iceberg lines.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IrcReadRoutesTest.StubDefaults.class)
@ContextConfiguration(initializers = {PropertyOverrideContextInitializer.class})
public class FormatV3TypesSwitchOffTest {
  @Autowired MockMvc mvc;

  private final List<String> created = new ArrayList<>();

  @AfterEach
  void cleanUp() throws Exception {
    for (String db : created) {
      delete(mvc, db, "t");
    }
    created.clear();
  }

  private static CreateUpdateTableRequestBody withColumn(
      CreateUpdateTableRequestBody body, String type) throws Exception {
    return body.toBuilder().schema(addColumn(body.getSchema(), "v", type, null, null)).build();
  }

  /**
   * What the request validation answered where the schema could not be parsed: the schema's failure
   * and, for a request with a sort order, the sort order's, which needs the schema.
   */
  private static String refusal(CreateUpdateTableRequestBody body, String type) {
    String schema = "schema : Cannot parse type string to primitive: " + type;
    return body.getSortOrder() == null
        ? schema
        : schema + "; sortOrder : provided " + body.getSortOrder() + " is not a valid sort order";
  }

  @Test
  void everyFormatV3TypeIsRefusedAsBeforeWithTheSwitchOff() throws Exception {
    for (String type : FormatV3TypesTest.TYPES) {
      String db = "x5off_" + type;
      created.add(db);
      List<CreateUpdateTableRequestBody> creates = new ArrayList<>();
      for (String version : new String[] {"3", "2"}) {
        CreateUpdateTableRequestBody body = asking(plain(db), version);
        creates.add(withColumn(body, type));
        creates.add(withColumn(with(body, "write.format.default", "parquet"), type));
      }
      Map<ResultActions, CreateUpdateTableRequestBody> answers = new LinkedHashMap<>();
      for (CreateUpdateTableRequestBody create : creates) {
        answers.put(post(mvc, create), create);
      }
      assertEquals(201, status(post(mvc, plain(db))));
      String stored = json(get(mvc, db, "t"));
      CreateUpdateTableRequestBody update = withColumn(bodyFromGet(stored), type);
      answers.put(put(mvc, update), update);
      for (Map.Entry<ResultActions, CreateUpdateTableRequestBody> answer : answers.entrySet()) {
        String message = message(answer.getKey());
        assertEquals(400, status(answer.getKey()), message);
        assertEquals(refusal(answer.getValue(), type), message);
      }
      assertEquals(stored, json(get(mvc, db, "t")));
    }
  }
}
