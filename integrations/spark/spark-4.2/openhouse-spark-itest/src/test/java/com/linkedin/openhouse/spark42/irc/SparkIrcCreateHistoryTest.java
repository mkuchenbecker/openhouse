package com.linkedin.openhouse.spark42.irc;

import static com.linkedin.openhouse.spark42.irc.IrcSpark.DB;
import static com.linkedin.openhouse.spark42.irc.IrcSpark.row;
import static com.linkedin.openhouse.spark42.irc.IrcSpark.rows;
import static com.linkedin.openhouse.spark42.irc.IrcSpark.service;
import static com.linkedin.openhouse.spark42.irc.IrcSpark.session;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.iceberg.HasTableOperations;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.spark.Spark3Util;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Work item V14 from Spark 4.2: a CTAS and a {@code CREATE OR REPLACE TABLE ... AS SELECT} of a
 * missing table over IRC are create transactions without schema, spec or order history, and the
 * table they store has the same schemas, specs, sort orders and ids as the same statement on
 * Iceberg's own Hadoop catalog (the reference) stores.
 */
public class SparkIrcCreateHistoryTest {
  private static final String REF = "ref_hist";

  @BeforeAll
  static void reference() throws Exception {
    String prefix = "spark.sql.catalog." + REF;
    session().conf().set(prefix, "org.apache.iceberg.spark.SparkCatalog");
    session().conf().set(prefix + ".type", "hadoop");
    session()
        .conf()
        .set(prefix + ".warehouse", service().work().resolve("ref-hist-warehouse").toString());
    rows("CREATE NAMESPACE IF NOT EXISTS " + REF + "." + DB);
  }

  /** The history parts of a table's metadata, one per line. */
  private static String shape(String table) throws Exception {
    Table loaded = Spark3Util.loadIcebergTable(session(), table);
    TableMetadata m = ((HasTableOperations) loaded).operations().current();
    List<String> lines = new ArrayList<>();
    lines.add("format-version " + m.formatVersion());
    m.schemas().forEach(s -> lines.add("schema " + s.schemaId() + " " + s.asStruct()));
    lines.add("current-schema-id " + m.currentSchemaId());
    m.specs().forEach(s -> lines.add("spec " + s.specId() + " " + s.fields()));
    lines.add("default-spec-id " + m.defaultSpecId());
    m.sortOrders().forEach(o -> lines.add("sort-order " + o.orderId() + " " + o.fields()));
    lines.add("default-sort-order-id " + m.defaultSortOrderId());
    lines.add("last-column-id " + m.lastColumnId());
    lines.add("last-partition-id " + m.lastAssignedPartitionId());
    for (Snapshot snapshot : m.snapshots()) {
      lines.add("snapshot schema-id " + snapshot.schemaId());
    }
    return String.join("\n", lines);
  }

  private static void sameAsReference(String statement, String table) throws Exception {
    String irc = "irc." + DB + "." + table;
    String ref = REF + "." + DB + "." + table;
    try {
      rows(String.format(statement, ref));
      rows(String.format(statement, irc));
      Assertions.assertEquals(shape(ref), shape(irc));
      Assertions.assertEquals(
          Arrays.asList(row(1L, "a", "x"), row(2L, "b", "y")),
          rows("SELECT * FROM " + irc + " ORDER BY id"));
    } finally {
      rows("DROP TABLE IF EXISTS " + irc);
      rows("DROP TABLE IF EXISTS " + ref);
    }
  }

  @Test
  void aCtasStoresWhatTheReferenceStores() throws Exception {
    sameAsReference(
        "CREATE TABLE %s USING iceberg PARTITIONED BY (p)"
            + " AS SELECT 1L AS id, 'a' AS data, 'x' AS p UNION ALL SELECT 2L, 'b', 'y'",
        "hist_ctas");
  }

  @Test
  void aCreateOrReplaceAsSelectOfAMissingTableStoresWhatTheReferenceStores() throws Exception {
    sameAsReference(
        "CREATE OR REPLACE TABLE %s USING iceberg PARTITIONED BY (p)"
            + " AS SELECT 1L AS id, 'a' AS data, 'x' AS p UNION ALL SELECT 2L, 'b', 'y'",
        "hist_cor");
  }
}
