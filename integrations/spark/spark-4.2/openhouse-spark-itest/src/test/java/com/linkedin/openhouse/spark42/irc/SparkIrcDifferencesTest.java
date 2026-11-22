package com.linkedin.openhouse.spark42.irc;

import static com.linkedin.openhouse.spark42.irc.IrcSpark.DB;
import static com.linkedin.openhouse.spark42.irc.IrcSpark.rows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import scala.collection.JavaConverters;

/**
 * What Spark 4.2 over IRC shows for a table, compared with what the existing OpenHouse Java client
 * shows for the same table (recorded by the service right after seeding). The Java client is what
 * the Spark 3.5 + OpenHouse catalog path reads through. Every difference is pinned here as
 * observed; nothing is normalized away except value representations (a Spark timestamp is compared
 * as the microseconds Iceberg stores).
 */
public class SparkIrcDifferencesTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Keys Spark's {@code SparkTable} adds to {@code SHOW TBLPROPERTIES}; not table properties. */
  private static final List<String> SPARK_DERIVED_PROPERTIES =
      Arrays.asList(
          "current-snapshot-id",
          "format",
          "format-version",
          "location",
          "provider",
          "sort-order",
          "identifier-fields");

  private static JsonNode nativeView(String table) throws Exception {
    return IrcSpark.service().table(table);
  }

  private static String name(String table) {
    return "irc." + DB + "." + table;
  }

  private static Map<String, String> describeExtended(String table) throws Exception {
    Map<String, String> info = new LinkedHashMap<>();
    for (List<Object> row : rows("DESCRIBE TABLE EXTENDED " + name(table))) {
      info.putIfAbsent(String.valueOf(row.get(0)), String.valueOf(row.get(1)));
    }
    return info;
  }

  @ParameterizedTest
  @ValueSource(strings = {"parted", "flat", "flat_orc"})
  void tableNameAndLocation(String table) throws Exception {
    JsonNode view = nativeView(table);
    Map<String, String> info = describeExtended(table);
    // The Java client names the table <catalog>.<database>.<table> with its own catalog name.
    // Spark 4.2's DESCRIBE TABLE EXTENDED has no single name row; it shows the session's catalog,
    // the namespace and the table.
    Assertions.assertEquals("openhouse." + DB + "." + table, view.get("name").asText());
    Assertions.assertEquals("irc", info.get("Catalog"), info.toString());
    Assertions.assertEquals(DB, info.get("Namespace"), info.toString());
    Assertions.assertEquals(table, info.get("Table"), info.toString());
    Assertions.assertNull(info.get("Name"), info.toString());
    Assertions.assertEquals(view.get("location").asText(), info.get("Location"), info.toString());
    Assertions.assertEquals("iceberg", info.get("Provider"), info.toString());
  }

  @ParameterizedTest
  @ValueSource(strings = {"parted", "flat", "flat_orc"})
  void tablePropertiesAreTheStoredOnes(String table) throws Exception {
    Map<String, String> viaIrc = new TreeMap<>();
    for (List<Object> row : rows("SHOW TBLPROPERTIES " + name(table))) {
      if (!SPARK_DERIVED_PROPERTIES.contains(String.valueOf(row.get(0)))) {
        viaIrc.put(String.valueOf(row.get(0)), String.valueOf(row.get(1)));
      }
    }
    Map<String, String> viaJavaClient =
        new TreeMap<>(MAPPER.convertValue(nativeView(table).get("properties"), Map.class));
    Assertions.assertEquals(viaJavaClient, viaIrc);
  }

  @ParameterizedTest
  @ValueSource(strings = {"parted", "flat", "flat_orc"})
  void metadataTablesShowWhatTheJavaClientShows(String table) throws Exception {
    Map<String, String> sortKeys = new HashMap<>();
    sortKeys.put("snapshots", "snapshot_id");
    sortKeys.put("history", "snapshot_id");
    sortKeys.put("files", "file_path");
    for (Map.Entry<String, String> entry : sortKeys.entrySet()) {
      String metadataTable = entry.getKey();
      JsonNode expected = nativeView(table).get("metadataTables").get(metadataTable);
      Dataset<Row> read =
          IrcSpark.session().sql("SELECT * FROM " + name(table) + "." + metadataTable);
      List<String> columns = Arrays.asList(read.schema().fieldNames());
      List<String> expectedColumns = MAPPER.convertValue(expected.get("columns"), List.class);
      Assertions.assertEquals(expectedColumns, columns, table + "." + metadataTable + " columns");

      List<JsonNode> viaIrc = new ArrayList<>();
      for (Row row : read.collectAsList()) {
        ObjectNode values = MAPPER.createObjectNode();
        for (int i = 0; i < columns.size(); i++) {
          values.set(columns.get(i), json(row.get(i)));
        }
        viaIrc.add(values);
      }
      List<JsonNode> viaJavaClient = new ArrayList<>();
      expected.get("rows").forEach(viaJavaClient::add);
      Comparator<JsonNode> order = Comparator.comparing(n -> n.get(entry.getValue()).asText());
      viaIrc.sort(order);
      viaJavaClient.sort(order);
      // JsonNode equality: field order does not count, values and their types do.
      Assertions.assertEquals(viaJavaClient, viaIrc, table + "." + metadataTable + " rows");
    }
  }

  /** A Spark value as the service wrote the Java client's value: JSON, timestamps as micros. */
  private static JsonNode json(Object value) throws Exception {
    return MAPPER.readTree(MAPPER.writeValueAsString(plain(value)));
  }

  private static Object plain(Object value) {
    if (value instanceof Timestamp) {
      Instant instant = ((Timestamp) value).toInstant();
      return instant.getEpochSecond() * 1_000_000L + instant.getNano() / 1_000;
    }
    if (value instanceof scala.collection.Map) {
      Map<String, Object> map = new LinkedHashMap<>();
      JavaConverters.mapAsJavaMap((scala.collection.Map<?, ?>) value)
          .forEach((k, v) -> map.put(String.valueOf(k), plain(v)));
      return map;
    }
    if (value instanceof Row) {
      Row row = (Row) value;
      List<Object> fields = new ArrayList<>();
      for (int i = 0; i < row.size(); i++) {
        fields.add(plain(row.get(i)));
      }
      return fields;
    }
    if (value instanceof scala.collection.Seq) {
      List<Object> list = new ArrayList<>();
      JavaConverters.seqAsJavaList((scala.collection.Seq<?>) value)
          .forEach(v -> list.add(plain(v)));
      return list;
    }
    if (value instanceof byte[]) {
      return null;
    }
    return value;
  }
}
