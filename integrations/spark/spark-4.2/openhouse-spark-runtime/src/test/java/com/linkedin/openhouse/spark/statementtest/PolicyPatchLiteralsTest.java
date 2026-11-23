package com.linkedin.openhouse.spark.statementtest;

import java.nio.file.Files;
import java.util.List;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * The exact policy patch each OpenHouse policy statement's exec node writes as the table property
 * {@code updated.openhouse.policy}, captured by running the statement on an Iceberg Hadoop-catalog
 * table that carries {@code openhouse.tableId} (all the exec nodes check), so the patch is stored
 * as written instead of being rewritten by a catalog. These are the strings the tables service's
 * {@code IrcPolicyPatchTest.EXEC_PATCHES} sends through the IRC routes; the two lists must match.
 *
 * <p>Ported from the Spark 3.5 itest's {@code PolicyPatchLiteralsTest}, with the same table of
 * statements and patches: the Spark 4.2 exec nodes write exactly what the Spark 3.5 ones write. The
 * session is a local one with the extensions, as in the other statement tests, instead of the
 * embedded service's.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class PolicyPatchLiteralsTest {
  private static final String TABLE = "lit.db.t";

  /** Statement (after {@code ALTER TABLE t}) and the patch its exec writes. */
  private static final String[][] STATEMENTS = {
    {"SET POLICY (RETENTION=24h)", "{\"retention\":{\"count\":24,\"granularity\":\"HOUR\"}}"},
    {"SET POLICY (RETENTION=30d)", "{\"retention\":{\"count\":30,\"granularity\":\"DAY\"}}"},
    {"SET POLICY (RETENTION=2m)", "{\"retention\":{\"count\":2,\"granularity\":\"MONTH\"}}"},
    {"SET POLICY (RETENTION=1y)", "{\"retention\":{\"count\":1,\"granularity\":\"YEAR\"}}"},
    {
      "SET POLICY (RETENTION=30d ON COLUMN name)",
      "{\"retention\":{\"count\":30,\"granularity\":\"DAY\","
          + "\"columnPattern\":{\"columnName\":\"name\",\"pattern\":\"\"}}}"
    },
    {
      "SET POLICY (RETENTION=30d ON COLUMN name WHERE PATTERN='yyyy-MM-dd')",
      "{\"retention\":{\"count\":30,\"granularity\":\"DAY\","
          + "\"columnPattern\":{\"columnName\":\"name\",\"pattern\":\"'yyyy-MM-dd'\"}}}"
    },
    {
      "SET POLICY (RETENTION=30d WITH TIMEZONE 'America/Los_Angeles' ON COLUMN name"
          + " WHERE PATTERN='yyyy-MM-dd')",
      "{\"retention\":{\"count\":30,\"granularity\":\"DAY\",\"timeZone\":\"America/Los_Angeles\","
          + "\"columnPattern\":{\"columnName\":\"name\",\"pattern\":\"yyyy-MM-dd\"}}}"
    },
    {"SET POLICY (SHARING=TRUE)", "{\"sharingEnabled\": TRUE}"},
    {"SET POLICY (SHARING=true)", "{\"sharingEnabled\": true}"},
    {"SET POLICY (SHARING=FALSE)", "{\"sharingEnabled\": FALSE}"},
    {"SET POLICY (HISTORY MAX_AGE=3d)", "{\"history\":{\"maxAge\":3,\"granularity\":\"DAY\"}}"},
    {"SET POLICY (HISTORY VERSIONS=5)", "{\"history\":{\"versions\":5}}"},
    {
      "SET POLICY (HISTORY MAX_AGE=24h VERSIONS=5)",
      "{\"history\":{\"maxAge\":24,\"granularity\":\"HOUR\",\"versions\":5}}"
    },
    {
      "SET POLICY (REPLICATION=({destination:'a', interval:12h}))",
      "{\"replication\":{\"config\":[{\"destination\":\"'a'\",\"interval\":\"12H\"}]}}"
    },
    {
      "SET POLICY (REPLICATION=({destination:'a', interval:12h}, {destination:'b'}))",
      "{\"replication\":{\"config\":[{\"destination\":\"'a'\",\"interval\":\"12H\"},"
          + "{\"destination\":\"'b'\",\"interval\":\"\"}]}}"
    },
    {"UNSET POLICY (REPLICATION)", "{\"replication\": {}}"},
    {
      "MODIFY COLUMN name SET TAG = (PII, HC)", "{\"columnTags\":{\"name\": {\"tags\": [PII, HC]}}}"
    },
    {"MODIFY COLUMN count SET TAG = (HC)", "{\"columnTags\":{\"count\": {\"tags\": [HC]}}}"},
    {"MODIFY COLUMN name SET TAG = (NONE)", "{\"columnTags\":{\"name\": {\"tags\": []}}}"},
    {"MODIFY COLUMN name SET TAG = (pii)", "{\"columnTags\":{\"name\": {\"tags\": [pii]}}}"},
    {"MODIFY COLUMN nope SET TAG = (PII)", "{\"columnTags\":{\"nope\": {\"tags\": [PII]}}}"},
  };

  private SparkSession spark;

  @BeforeAll
  void start() throws Exception {
    spark =
        SparkSession.builder()
            .master("local[2]")
            .config(
                "spark.sql.extensions",
                ("org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions,"
                    + "com.linkedin.openhouse.spark.extensions.OpenhouseSparkSessionExtensions"))
            .getOrCreate();
    spark.conf().set("spark.sql.catalog.lit", "org.apache.iceberg.spark.SparkCatalog");
    spark.conf().set("spark.sql.catalog.lit.type", "hadoop");
    spark
        .conf()
        .set(
            "spark.sql.catalog.lit.warehouse",
            Files.createTempDirectory("policy_patch_literals").toString());
    spark.sql(
        "CREATE TABLE "
            + TABLE
            + " (id bigint, name string, ts timestamp) USING iceberg"
            + " TBLPROPERTIES ('openhouse.tableId' = 't')");
  }

  @AfterAll
  void stop() throws Exception {
    spark.close();
  }

  @Test
  void eachStatementWritesItsPatch() {
    for (String[] statement : STATEMENTS) {
      spark.sql("ALTER TABLE " + TABLE + " " + statement[0]);
      List<Row> rows =
          spark
              .sql("SHOW TBLPROPERTIES " + TABLE + " ('updated.openhouse.policy')")
              .collectAsList();
      Assertions.assertEquals(statement[1], rows.get(0).getString(1), statement[0]);
    }
  }
}
