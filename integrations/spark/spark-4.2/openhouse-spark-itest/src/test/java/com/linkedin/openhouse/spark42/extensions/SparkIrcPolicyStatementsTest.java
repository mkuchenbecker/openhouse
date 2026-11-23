package com.linkedin.openhouse.spark42.extensions;

import static com.linkedin.openhouse.spark42.extensions.ExtensionsSpark.IRC;
import static com.linkedin.openhouse.spark42.extensions.ExtensionsSpark.outcome;
import static com.linkedin.openhouse.spark42.extensions.ExtensionsTestService.DB;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Every OpenHouse policy statement from Spark 4.2, with the OpenHouse SQL extensions next to
 * Iceberg's, through Iceberg's own {@code SparkCatalog} over Iceberg's own {@code RESTCatalog}
 * against the service's IRC routes, stores what the same statement stores from Spark 3.5 with the
 * OpenHouse catalog.
 *
 * <p>The Spark 3.5 reference, without Spark 3.5: each statement runs on two tables that start
 * identical, both made through the existing OpenHouse Java client in the service JVM. On one, the
 * Java client sets the patch the Spark 3.5 exec node writes for the statement and commits, which is
 * exactly what the 3.5 exec does on the OpenHouse catalog (the client merges the patch onto the
 * loaded policies and sends the native PUT). The patches are the Spark 3.5 itest's {@code
 * PolicyPatchLiteralsTest} table, copied; the 4.2 runtime's own {@code PolicyPatchLiteralsTest}
 * pins that its exec nodes write the very same strings. On the other table the statement runs in
 * Spark 4.2 over IRC, where the server merges the patch. Both tables' {@code policies}, read back
 * through the Java client, must be equal (the replication schedule aside, which each write draws at
 * random), and the patch must not be stored. This is the comparison the Spark 3.5 itest's {@code
 * IcebergRestPolicySqlTest} makes from Spark 3.5.
 */
public class SparkIrcPolicyStatementsTest {

  /**
   * Statement (after {@code ALTER TABLE t}), the patch the Spark 3.5 exec writes for it (the 3.5
   * itest's {@code PolicyPatchLiteralsTest}), and the table it runs on: {@code P} partitioned by
   * {@code hours(ts)}, {@code U} unpartitioned, {@code PU} both.
   */
  static final String[][] STATEMENTS = {
    {"SET POLICY (RETENTION=24h)", "{\"retention\":{\"count\":24,\"granularity\":\"HOUR\"}}", "P"},
    {"SET POLICY (RETENTION=30d)", "{\"retention\":{\"count\":30,\"granularity\":\"DAY\"}}", "P"},
    {"SET POLICY (RETENTION=2m)", "{\"retention\":{\"count\":2,\"granularity\":\"MONTH\"}}", "P"},
    {"SET POLICY (RETENTION=1y)", "{\"retention\":{\"count\":1,\"granularity\":\"YEAR\"}}", "P"},
    {
      "SET POLICY (RETENTION=30d ON COLUMN name)",
      "{\"retention\":{\"count\":30,\"granularity\":\"DAY\","
          + "\"columnPattern\":{\"columnName\":\"name\",\"pattern\":\"\"}}}",
      "U"
    },
    {
      "SET POLICY (RETENTION=30d ON COLUMN name WHERE PATTERN='yyyy-MM-dd')",
      "{\"retention\":{\"count\":30,\"granularity\":\"DAY\","
          + "\"columnPattern\":{\"columnName\":\"name\",\"pattern\":\"'yyyy-MM-dd'\"}}}",
      "U"
    },
    {
      "SET POLICY (RETENTION=30d WITH TIMEZONE 'America/Los_Angeles' ON COLUMN name"
          + " WHERE PATTERN='yyyy-MM-dd')",
      "{\"retention\":{\"count\":30,\"granularity\":\"DAY\",\"timeZone\":\"America/Los_Angeles\","
          + "\"columnPattern\":{\"columnName\":\"name\",\"pattern\":\"yyyy-MM-dd\"}}}",
      "U"
    },
    {"SET POLICY (SHARING=TRUE)", "{\"sharingEnabled\": TRUE}", "PU"},
    {"SET POLICY (SHARING=true)", "{\"sharingEnabled\": true}", "PU"},
    {"SET POLICY (SHARING=FALSE)", "{\"sharingEnabled\": FALSE}", "PU"},
    {
      "SET POLICY (HISTORY MAX_AGE=3d)", "{\"history\":{\"maxAge\":3,\"granularity\":\"DAY\"}}", "P"
    },
    {"SET POLICY (HISTORY VERSIONS=5)", "{\"history\":{\"versions\":5}}", "P"},
    {
      "SET POLICY (HISTORY MAX_AGE=24h VERSIONS=5)",
      "{\"history\":{\"maxAge\":24,\"granularity\":\"HOUR\",\"versions\":5}}",
      "P"
    },
    {
      "SET POLICY (REPLICATION=({destination:'a', interval:12h}))",
      "{\"replication\":{\"config\":[{\"destination\":\"'a'\",\"interval\":\"12H\"}]}}",
      "P"
    },
    {
      "SET POLICY (REPLICATION=({destination:'a', interval:12h}, {destination:'b'}))",
      "{\"replication\":{\"config\":[{\"destination\":\"'a'\",\"interval\":\"12H\"},"
          + "{\"destination\":\"'b'\",\"interval\":\"\"}]}}",
      "P"
    },
    {"UNSET POLICY (REPLICATION)", "{\"replication\": {}}", "P"},
    {
      "MODIFY COLUMN name SET TAG = (PII, HC)",
      "{\"columnTags\":{\"name\": {\"tags\": [PII, HC]}}}",
      "PU"
    },
    {"MODIFY COLUMN count SET TAG = (HC)", "{\"columnTags\":{\"count\": {\"tags\": [HC]}}}", "P"},
    {"MODIFY COLUMN name SET TAG = (NONE)", "{\"columnTags\":{\"name\": {\"tags\": []}}}", "P"},
    {"MODIFY COLUMN name SET TAG = (pii)", "{\"columnTags\":{\"name\": {\"tags\": [pii]}}}", "P"},
    {"MODIFY COLUMN nope SET TAG = (PII)", "{\"columnTags\":{\"nope\": {\"tags\": [PII]}}}", "P"},
  };

  /**
   * The policies the second run of each partitioned statement starts from, as {@code
   * IcebergRestPolicySqlTest}'s {@code WITH_POLICIES}: the 3.5 patches of {@code SET POLICY
   * (HISTORY VERSIONS=7)}, {@code MODIFY COLUMN name SET TAG = (PII)} and {@code SET POLICY
   * (RETENTION=48h)}, applied to both tables through the Java client.
   */
  static final String[] WITH_POLICIES = {
    "{\"history\":{\"versions\":7}}",
    "{\"columnTags\":{\"name\": {\"tags\": [PII]}}}",
    "{\"retention\":{\"count\":48,\"granularity\":\"HOUR\"}}",
  };

  private static final Pattern NATIVE_MESSAGE = Pattern.compile("\"message\"\\s*:\\s*\"([^\"]*)\"");

  private static int next = 0;

  private static String policies(Map<String, String> properties) {
    String policies = properties.get("policies");
    return policies == null
        ? null
        : policies.replaceAll("\"cronSchedule\": \"[^\"]*\"", "\"cronSchedule\": \"cron\"");
  }

  /** Two identical tables through the Java client; returns their names (reference, IRC). */
  private static String[] pair(boolean partitioned, String... patches) throws Exception {
    ExtensionsTestService service = ExtensionsTestService.get();
    String name = "p" + next++;
    String[] tables = {name + "_oh", name + "_irc"};
    for (String table : tables) {
      service.create(table, partitioned, patches);
    }
    return tables;
  }

  /**
   * The statement through both paths: the same stored policies and no stored patch, or, where the
   * native rules refuse the patch, the same native message on both and nothing changed.
   *
   * @return a line describing the outcome, for the failure message
   */
  private static String assertSameThroughBoth(
      String statement, String patch, boolean partitioned, String... setUp) throws Exception {
    ExtensionsTestService service = ExtensionsTestService.get();
    String[] tables = pair(partitioned, setUp);
    String before = policies(service.properties(tables[1]));
    String reference = service.patch(tables[0], patch);
    String irc = outcome("ALTER TABLE " + IRC + "." + DB + "." + tables[1] + " " + statement);
    String where =
        statement
            + (partitioned ? " (partitioned" : " (unpartitioned")
            + (setUp.length > 0 ? ", with policies)" : ")");
    Map<String, String> ircProperties = service.properties(tables[1]);
    Assertions.assertFalse(
        ircProperties.containsKey(ExtensionsTestService.POLICY_PATCH),
        where + ": " + ircProperties);
    if (reference == null) {
      Assertions.assertEquals("ok", irc, where);
      Assertions.assertEquals(
          policies(service.properties(tables[0])), policies(ircProperties), where);
      return where + ": applied";
    }
    Matcher message = NATIVE_MESSAGE.matcher(reference);
    Assertions.assertTrue(message.find(), where + ": no native message in " + reference);
    // The native message names the table it refused. Over IRC it is Iceberg's BadRequestException,
    // whose message is "Malformed request: " and the server's message (as from Spark 3.5 over IRC);
    // the Java client raises its own exception with the status and the response body instead.
    Assertions.assertEquals(
        "org.apache.iceberg.exceptions.BadRequestException: Malformed request: "
            + message.group(1).replace(tables[0], tables[1]),
        irc,
        where);
    Assertions.assertEquals(before, policies(service.properties(tables[0])), where);
    Assertions.assertEquals(before, policies(ircProperties), where);
    return where + ": refused with " + message.group(1);
  }

  @Test
  void everyPolicyStatementStoresWhatSpark35WithTheOpenHouseCatalogStores() throws Exception {
    List<String> outcomes = new ArrayList<>();
    int applied = 0;
    for (String[] statement : STATEMENTS) {
      if (statement[2].contains("P")) {
        outcomes.add(assertSameThroughBoth(statement[0], statement[1], true));
        outcomes.add(assertSameThroughBoth(statement[0], statement[1], true, WITH_POLICIES));
      }
      if (statement[2].contains("U")) {
        outcomes.add(assertSameThroughBoth(statement[0], statement[1], false));
      }
    }
    for (String outcome : outcomes) {
      if (outcome.endsWith(": applied")) {
        applied++;
      }
    }
    // 18 statements on the partitioned table, twice each, and 7 on the unpartitioned one. Only the
    // day, month and year retentions on the hour-partitioned table are refused, by the native
    // granularity rule, twice each.
    Assertions.assertEquals(43, outcomes.size(), String.join("\n", outcomes));
    Assertions.assertEquals(37, applied, String.join("\n", outcomes));
  }

  /**
   * A refused policy: the user sees the native rule's message, as Iceberg's {@code
   * BadRequestException} (as on Spark 3.5 over IRC, {@code IcebergRestPolicySqlTest}), and nothing
   * is stored.
   */
  @Test
  void aRefusedPolicyShowsTheNativeMessage() throws Exception {
    ExtensionsTestService service = ExtensionsTestService.get();
    String[] tables = pair(true);
    String before = service.properties(tables[1]).get("policies");
    String irc =
        outcome("ALTER TABLE " + IRC + "." + DB + "." + tables[1] + " SET POLICY (RETENTION=30d)");
    Assertions.assertTrue(
        irc.startsWith("org.apache.iceberg.exceptions.BadRequestException: "), irc);
    Assertions.assertTrue(
        irc.contains("Policies granularity must be equal to or lesser than time partition spec"),
        irc);
    Assertions.assertEquals(before, service.properties(tables[1]).get("policies"));
  }
}
