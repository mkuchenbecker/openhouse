package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.CreateHistory.*;
import static com.linkedin.openhouse.tables.e2e.h2.FormatV3.SWITCH_ON;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.jayway.jsonpath.JsonPath;
import com.linkedin.openhouse.common.security.DummyTokenInterceptor.DummySecurityJWT;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.tables.formatversion.IcebergV3;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.HasTableOperations;
import org.apache.iceberg.Schema;
import org.apache.iceberg.SchemaParser;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableMetadataParser;
import org.apache.iceberg.TableOperations;
import org.apache.iceberg.Transaction;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;

/**
 * Work item V14 at format v3 ({@code cluster.iceberg.format-v3.enabled} on): a create transaction
 * that asks for v3, appends, adds a column with native default values, partitions and sorts on it
 * and appends again keeps its history as Iceberg's in-memory catalog keeps it, with the row lineage
 * (next-row-id, each snapshot's first-row-id) and the column's defaults as the client built them.
 */
@SpringBootTest(
    classes = SpringH2Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = SWITCH_ON)
@ContextConfiguration(initializers = {PropertyOverrideContextInitializer.class})
@Import(IrcReadRoutesTest.StubDefaults.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class IrcRestCatalogCreateHistoryV3Test {
  @LocalServerPort private int port;

  private RESTCatalog rest;
  private final List<TableIdentifier> created = new ArrayList<>();

  @BeforeEach
  void start() throws Exception {
    Map<String, String> properties = new HashMap<>();
    properties.put("uri", "http://localhost:" + port + "/iceberg");
    properties.put("token", new DummySecurityJWT("irc_history_v3").buildNoopJWT());
    rest =
        (RESTCatalog)
            CatalogUtil.loadCatalog(
                RESTCatalog.class.getName(), "openhouse", properties, new Configuration());
  }

  @AfterEach
  void stop() throws Exception {
    try {
      for (TableIdentifier identifier : created) {
        if (rest.tableExists(identifier)) {
          rest.dropTable(identifier);
        }
      }
    } finally {
      created.clear();
      rest.close();
    }
  }

  private static Transaction evolveAtV3(Catalog catalog, TableIdentifier identifier)
      throws Exception {
    Transaction create =
        catalog
            .buildTable(identifier, SCHEMA)
            .withPartitionSpec(SPEC)
            .withProperty("format-version", "3")
            .createTransaction();
    create.newFastAppend().appendFile(file(create.table(), "a", "id_bucket=0")).commit();
    // A column with an initial and a write default, as metadata (version-neutral to compile).
    TableOperations ops = ((HasTableOperations) create.table()).operations();
    TableMetadata base = ops.current();
    Schema withDefault =
        SchemaParser.fromJson(
            FormatV3.addColumn(SchemaParser.toJson(base.schema()), "tier", "int", "1", "2"));
    ops.commit(
        base,
        TableMetadata.buildFrom(base)
            .setCurrentSchema(withDefault, withDefault.highestFieldId())
            .build());
    create.updateSpec().addField("tier").commit();
    create.replaceSortOrder().asc("tier").commit();
    create.newFastAppend().appendFile(file(create.table(), "b", "id_bucket=0/tier=1")).commit();
    return create;
  }

  /** Each schema as JSON (defaults included), row lineage, and the history shape. */
  private static String v3Shape(Table table) {
    TableMetadata m = metadata(table);
    StringBuilder s = new StringBuilder(shape(table));
    m.schemas().forEach(schema -> s.append("\nschema-json ").append(SchemaParser.toJson(schema)));
    String json = TableMetadataParser.toJson(m);
    s.append("\nnext-row-id ").append(JsonPath.read(json, "$.next-row-id").toString());
    s.append("\nfirst-row-ids ")
        .append(JsonPath.read(json, "$.snapshots[*].first-row-id").toString());
    s.append("\nadded-rows ").append(JsonPath.read(json, "$.snapshots[*].added-rows").toString());
    return s.toString();
  }

  @Test
  void aV3CreateTransactionKeepsItsHistoryRowLineageAndDefaults() throws Exception {
    assumeTrue(IcebergV3.supported());
    TableIdentifier identifier = TableIdentifier.of("irc_hist_v3", "t");
    created.add(identifier);
    Catalog reference = reference(identifier);
    evolveAtV3(reference, identifier).commitTransaction();
    evolveAtV3(rest, identifier).commitTransaction();
    Table expected = reference.loadTable(identifier);
    Table actual = rest.loadTable(identifier);
    assertEquals(v3Shape(expected), v3Shape(actual));
    assertEquals(planned(expected), planned(actual));
    assertEquals(3, metadata(actual).formatVersion());
    String schemaJson = SchemaParser.toJson(actual.schema());
    assertTrue(schemaJson.contains("\"initial-default\":1"), schemaJson);
    assertTrue(schemaJson.contains("\"write-default\":2"), schemaJson);

    // A later commit on top: a column and an append, row lineage carries on.
    actual.updateSchema().addColumn("later", Types.StringType.get()).commit();
    actual.newFastAppend().appendFile(file(actual, "c", "id_bucket=1/tier=2")).commit();
    TableMetadata after = metadata(rest.loadTable(identifier));
    assertEquals(3, after.schemas().size());
    assertEquals("6", JsonPath.read(TableMetadataParser.toJson(after), "$.next-row-id").toString());
  }
}
