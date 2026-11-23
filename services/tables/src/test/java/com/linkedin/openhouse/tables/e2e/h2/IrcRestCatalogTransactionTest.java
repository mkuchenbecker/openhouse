package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.model.TableModelConstants.HEALTH_SCHEMA_LITERAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.linkedin.openhouse.common.schema.IcebergSchemaHelper;
import com.linkedin.openhouse.common.security.DummyTokenInterceptor.DummySecurityJWT;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.BaseTransaction;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.HasTableOperations;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.Transaction;
import org.apache.iceberg.catalog.TableCommit;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.BadRequestException;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.exceptions.NoSuchTableException;
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
 * An unmodified Iceberg {@link RESTCatalog}, over HTTP, calling {@code commitTransaction}, the one
 * caller of the multi-table commit route in Iceberg (no engine calls it; a table's own commits,
 * Spark's included, go to the table's commit route). A transaction of one table commits it, with
 * the client's requirements; a transaction of two tables is Iceberg's {@link BadRequestException}
 * saying OpenHouse commits one table per transaction, and changes neither (see {@link
 * IrcTransactionRouteTest} for the route itself).
 */
@SpringBootTest(
    classes = SpringH2Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = {PropertyOverrideContextInitializer.class})
@Import(IrcReadRoutesTest.StubDefaults.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class IrcRestCatalogTransactionTest {
  private static final TableIdentifier A = TableIdentifier.of("irc_tx_rest_a", "a");

  /** In another database. */
  private static final TableIdentifier B = TableIdentifier.of("irc_tx_rest_b", "b");

  @LocalServerPort private int port;

  private RESTCatalog rest;

  @BeforeEach
  void start() throws Exception {
    Map<String, String> properties = new HashMap<>();
    properties.put("uri", "http://localhost:" + port + "/iceberg");
    properties.put("token", new DummySecurityJWT("irc_writer").buildNoopJWT());
    rest =
        (RESTCatalog)
            CatalogUtil.loadCatalog(
                RESTCatalog.class.getName(), "openhouse", properties, new Configuration());
    Schema schema = IcebergSchemaHelper.getSchemaFromSchemaJson(HEALTH_SCHEMA_LITERAL);
    rest.createTable(A, schema);
    rest.createTable(B, schema);
  }

  @AfterEach
  void stop() throws Exception {
    try {
      rest.dropTable(A);
      rest.dropTable(B);
    } finally {
      rest.close();
    }
  }

  private TableMetadata current(TableIdentifier identifier) {
    return ((HasTableOperations) rest.loadTable(identifier)).operations().current();
  }

  /** A commit that sets {@code key} on the table as it is now. */
  private TableCommit setProperty(TableIdentifier identifier, String key) {
    TableMetadata base = current(identifier);
    return TableCommit.create(
        identifier,
        base,
        TableMetadata.buildFrom(base).setProperties(Collections.singletonMap(key, "v")).build());
  }

  /** As Iceberg's own test of the route builds one: a transaction's start and its result. */
  @Test
  void aTransactionOfOneTableCommitsIt() {
    Transaction transaction = rest.loadTable(A).newTransaction();
    transaction.updateProperties().set("user.k", "v").commit();
    transaction.updateSchema().addColumn("added", Types.LongType.get()).commit();
    String before = current(A).metadataFileLocation();

    rest.commitTransaction(
        TableCommit.create(
            A,
            ((BaseTransaction) transaction).startMetadata(),
            ((BaseTransaction) transaction).currentMetadata()));

    Table committed = rest.loadTable(A);
    assertEquals("v", committed.properties().get("user.k"));
    assertNotNull(committed.schema().findField("added"));
    // One commit: the table's previous metadata file is the one the transaction started from.
    TableMetadata after = current(A);
    // (The API reports a location with its scheme, the metadata log without.)
    assertTrue(
        before.endsWith(after.previousFiles().get(after.previousFiles().size() - 1).file()),
        before);
    assertEquals(
        current(B).previousFiles().size() + 1, after.previousFiles().size(), "one new version");
  }

  @Test
  void aTransactionOfTwoTablesIsABadRequestAndChangesNeither() {
    TableCommit onA = setProperty(A, "user.k");
    TableCommit onB = setProperty(B, "user.k");
    String a = current(A).metadataFileLocation();
    String b = current(B).metadataFileLocation();

    BadRequestException refused =
        assertThrows(BadRequestException.class, () -> rest.commitTransaction(onA, onB));
    assertTrue(
        refused.getMessage().contains("OpenHouse commits one table per transaction"),
        refused.getMessage());
    assertEquals(a, current(A).metadataFileLocation());
    assertEquals(b, current(B).metadataFileLocation());
    assertNull(rest.loadTable(A).properties().get("user.k"));
    assertNull(rest.loadTable(B).properties().get("user.k"));

    // What the message tells the caller to do: each table in a transaction of its own.
    rest.commitTransaction(onA);
    rest.commitTransaction(onB);
    assertEquals("v", rest.loadTable(A).properties().get("user.k"));
    assertEquals("v", rest.loadTable(B).properties().get("user.k"));
  }

  @Test
  void aTransactionOnAnUnknownTableIsNoSuchTable() {
    TableMetadata base = current(A);
    TableCommit missing =
        TableCommit.create(
            TableIdentifier.of("irc_tx_rest_a", "missing"),
            base,
            TableMetadata.buildFrom(base)
                .setProperties(Collections.singletonMap("user.k", "v"))
                .build());
    assertThrows(NoSuchTableException.class, () -> rest.commitTransaction(missing));
    assertEquals(false, rest.tableExists(TableIdentifier.of("irc_tx_rest_a", "missing")));
  }

  /**
   * A transaction built on a base that another commit has moved: the requirement Iceberg derives
   * from the base fails, the client gets {@link CommitFailedException} (it does not retry a
   * transaction itself), and the same change built on the table as it is now commits.
   */
  @Test
  void aStaleTransactionIsCommitFailedAndCommitsOnTheCurrentTable() {
    Transaction started = rest.loadTable(A).newTransaction();
    started.updateSchema().addColumn("late", Types.LongType.get()).commit();
    TableCommit stale =
        TableCommit.create(
            A,
            ((BaseTransaction) started).startMetadata(),
            ((BaseTransaction) started).currentMetadata());
    rest.loadTable(A).updateSchema().addColumn("first", Types.LongType.get()).commit();
    String moved = current(A).metadataFileLocation();

    assertThrows(CommitFailedException.class, () -> rest.commitTransaction(stale));
    assertEquals(moved, current(A).metadataFileLocation());

    Transaction retry = rest.loadTable(A).newTransaction();
    retry.updateSchema().addColumn("late", Types.LongType.get()).commit();
    rest.commitTransaction(
        TableCommit.create(
            A,
            ((BaseTransaction) retry).startMetadata(),
            ((BaseTransaction) retry).currentMetadata()));
    assertNotNull(rest.loadTable(A).schema().findField("late"));
    assertNotNull(rest.loadTable(A).schema().findField("first"));
  }
}
