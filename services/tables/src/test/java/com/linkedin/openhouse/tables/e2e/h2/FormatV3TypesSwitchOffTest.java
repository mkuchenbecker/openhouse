package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.*;
import static com.linkedin.openhouse.tables.e2e.h2.FormatV3.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.tables.api.spec.v0.request.CreateUpdateTableRequestBody;
import com.linkedin.openhouse.tables.formatversion.IcebergV3;
import java.util.ArrayList;
import java.util.List;
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
 * The format v3 column types (work item X5) on a cluster with the switch off, as in production: a
 * variant column is refused by the request validation's schema walk exactly as before format v3
 * types could pass it, whatever format version the request asks for; every other format v3 type is
 * refused as before too. On the reference line, whose Iceberg cannot parse the types, by the
 * request validation.
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

  @Test
  void everyFormatV3TypeIsRefusedAsBeforeWithTheSwitchOff() throws Exception {
    for (String type : FormatV3TypesTest.TYPES) {
      String db = "x5off_" + type;
      created.add(db);
      List<ResultActions> answers = new ArrayList<>();
      for (String version : new String[] {"3", "2"}) {
        CreateUpdateTableRequestBody body = asking(plain(db), version);
        answers.add(post(mvc, withColumn(body, type)));
        answers.add(post(mvc, withColumn(with(body, "write.format.default", "parquet"), type)));
      }
      assertEquals(201, status(post(mvc, plain(db))));
      String stored = json(get(mvc, db, "t"));
      answers.add(put(mvc, withColumn(bodyFromGet(stored), type)));
      for (ResultActions answer : answers) {
        String message = message(answer);
        if (!IcebergV3.supported()) {
          assertEquals(400, status(answer), message);
          assertTrue(message.startsWith("schema : ") && message.contains(type), message);
        } else if ("variant".equals(type)) {
          assertEquals(400, status(answer), message);
          assertEquals("schema : Unsupported type: variant", message);
        } else {
          // The create that asks for v3 is refused by the format version rule, the others by
          // Iceberg's schema check of a v2 table (500), as before.
          assertTrue(
              (status(answer) == 400 && message.startsWith("FORMAT_VERSION_REFUSED: "))
                  || (status(answer) == 500 && message.contains("Invalid schema for v2:")),
              status(answer) + " " + message);
        }
      }
      assertEquals(stored, json(get(mvc, db, "t")));
    }
  }
}
