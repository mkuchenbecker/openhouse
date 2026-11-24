package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.CreateHistory.*;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.linkedin.openhouse.common.security.DummyTokenInterceptor.DummySecurityJWT;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.tables.api.spec.v0.request.CreateUpdateTableRequestBody;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.Transaction;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.BadRequestException;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Work item V14: an unmodified Iceberg {@link RESTCatalog}, over HTTP, runs create transactions
 * that change the schema, the partition spec and the sort order before they commit, and the table
 * they create keeps that history with the client's ids, as Iceberg's in-memory catalog keeps it for
 * the same calls ({@link CreateHistory}). A create transaction without history stores what it
 * always stored, and one whose final spec OpenHouse refuses is still refused with the same answer.
 * A table created with history takes later commits through both APIs.
 */
@SpringBootTest(
    classes = SpringH2Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ContextConfiguration(initializers = {PropertyOverrideContextInitializer.class})
@Import(IrcReadRoutesTest.StubDefaults.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class IrcRestCatalogCreateHistoryTest {
  @LocalServerPort private int port;
  @Autowired MockMvc mvc;

  private RESTCatalog rest;
  private final List<TableIdentifier> created = new ArrayList<>();

  @BeforeEach
  void start() throws Exception {
    Map<String, String> properties = new HashMap<>();
    properties.put("uri", "http://localhost:" + port + "/iceberg");
    properties.put("token", new DummySecurityJWT("irc_history").buildNoopJWT());
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

  /**
   * Runs {@code changes} on a transaction {@code start} opens, on OpenHouse and on the reference,
   * commits both, and checks OpenHouse kept what the reference kept.
   */
  private Table sameAsReference(String db, Start start, UnaryOperator<Transaction> changes) {
    TableIdentifier identifier = TableIdentifier.of(db, "t");
    created.add(identifier);
    Catalog reference = reference(identifier);
    changes.apply(start.start(reference, identifier)).commitTransaction();
    Transaction transaction = changes.apply(start.start(rest, identifier));
    assertFalse(rest.tableExists(identifier), "nothing is stored before the commit");
    transaction.commitTransaction();
    Table expected = reference.loadTable(identifier);
    Table actual = rest.loadTable(identifier);
    assertAll(
        () -> assertEquals(shape(expected), shape(actual)),
        () -> assertEquals(planned(expected), planned(actual)));
    // One metadata file: the create is a single commit, as on the reference.
    assertEquals(0, metadata(actual).previousFiles().size());
    return actual;
  }

  @Test
  void aCreateTransactionKeepsItsSchemaSpecAndOrderHistory() {
    Table table = sameAsReference("irc_hist_all", CREATE, CreateHistory::evolveAll);
    assertEquals(1, table.schema().schemaId());
    assertEquals(1, table.spec().specId());
    assertEquals(2, table.sortOrder().orderId());
  }

  @Test
  void aCreateOrReplaceTransactionOfAMissingTableKeepsItsHistory() {
    Table table = sameAsReference("irc_hist_cor", CREATE_OR_REPLACE, CreateHistory::evolveAll);
    assertEquals(1, table.spec().specId());
  }

  @Test
  void aCreateTransactionThatDropsAColumnKeepsTheClientsColumnIds() {
    Table table = sameAsReference("irc_hist_drop", CREATE, CreateHistory::dropAndAdd);
    assertEquals(4, table.schema().findField("added").fieldId());
    assertEquals(4, metadata(table).lastColumnId());
  }

  /** The case every CTAS is: no history, stored as it always was (schema 0, spec 0, order 1). */
  @Test
  void aCreateTransactionWithoutHistoryIsStoredAsBefore() {
    Table table = sameAsReference("irc_hist_none", CREATE, CreateHistory::appendOnly);
    TableMetadata m = metadata(table);
    assertEquals(1, m.schemas().size());
    assertEquals(0, m.currentSchemaId());
    assertEquals(1, m.specs().size());
    assertEquals(0, m.defaultSpecId());
    assertEquals(1, m.sortOrders().size());
    assertEquals(1, m.defaultSortOrderId());
  }

  /**
   * A final spec of five clustering columns is refused with the native rule's answer, history or
   * not; the history (the spec the transaction started with) does not change the answer.
   */
  @Test
  void aCreateTransactionWhoseFinalSpecIsRefusedIsStillRefused() {
    TableIdentifier identifier = TableIdentifier.of("irc_hist_refused", "t");
    created.add(identifier);
    Transaction create =
        rest.buildTable(
                identifier,
                new org.apache.iceberg.Schema(
                    Types.NestedField.required(1, "a", Types.IntegerType.get()),
                    Types.NestedField.required(2, "b", Types.IntegerType.get()),
                    Types.NestedField.required(3, "c", Types.IntegerType.get()),
                    Types.NestedField.required(4, "d", Types.IntegerType.get()),
                    Types.NestedField.required(5, "e", Types.IntegerType.get())))
            .createTransaction();
    create
        .updateSpec()
        .addField("a")
        .addField("b")
        .addField("c")
        .addField("d")
        .addField("e")
        .commit();
    BadRequestException refused =
        assertThrows(BadRequestException.class, create::commitTransaction);
    assertTrue(
        refused.getMessage().contains("Max allowed clustering columns supported"),
        refused.getMessage());
    assertFalse(rest.tableExists(identifier));
  }

  /**
   * A table created with history takes a later schema change and append through the IRC routes, and
   * a native get-modify-put that adds a column, and keeps its history through both.
   */
  @Test
  void aTableCreatedWithHistoryTakesLaterCommitsThroughBothApis() throws Exception {
    String db = "irc_hist_later";
    Table table = sameAsReference(db, CREATE, CreateHistory::evolveAll);
    table.updateSchema().addColumn("later", Types.StringType.get()).commit();
    table.newFastAppend().appendFile(file(table, "c", "id_bucket=1/new_col=1")).commit();
    Table afterIrc = rest.loadTable(TableIdentifier.of(db, "t"));
    TableMetadata m = metadata(afterIrc);
    assertEquals(3, m.schemas().size());
    assertEquals(2, m.currentSchemaId());
    assertEquals(2, m.specs().size());
    assertEquals(1, m.defaultSpecId());
    assertEquals(3, m.snapshots().size());

    String got = CommitPathHttp.json(CommitPathHttp.get(mvc, db, "t"));
    CreateUpdateTableRequestBody put = CommitPathHttp.bodyFromGet(got);
    put =
        put.toBuilder()
            .schema(FormatV3.addColumn(put.getSchema(), "native_col", "string", null, null))
            .build();
    assertEquals(200, CommitPathHttp.status(CommitPathHttp.put(mvc, put)));
    Table afterNative = rest.loadTable(TableIdentifier.of(db, "t"));
    assertNotNull(afterNative.schema().findField("native_col"));
    TableMetadata n = metadata(afterNative);
    assertEquals(4, n.schemas().size());
    assertEquals(2, n.specs().size());
    assertEquals(1, n.defaultSpecId());
    assertEquals(2, n.defaultSortOrderId());
    assertTrue(shape(afterNative).contains("a.parquet@spec-0"), shape(afterNative));
    assertTrue(shape(afterNative).contains("c.parquet@spec-1"), shape(afterNative));
  }
}
