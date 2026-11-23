package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.*;
import static com.linkedin.openhouse.tables.e2e.h2.IrcHttp.assertIrcError;
import static com.linkedin.openhouse.tables.e2e.h2.IrcHttp.body;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.linkedin.openhouse.common.audit.AuditHandler;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.internal.catalog.CatalogConstants;
import com.linkedin.openhouse.internal.catalog.model.HouseTable;
import com.linkedin.openhouse.internal.catalog.model.HouseTablePrimaryKey;
import com.linkedin.openhouse.internal.catalog.repository.HouseTableRepository;
import com.linkedin.openhouse.tables.audit.model.OperationStatus;
import com.linkedin.openhouse.tables.audit.model.OperationType;
import com.linkedin.openhouse.tables.audit.model.TableAuditEvent;
import com.linkedin.openhouse.tables.config.TablesMvcConstants;
import com.linkedin.openhouse.tables.readbridge.ClientCapabilityGate;
import com.linkedin.openhouse.tables.toggle.repository.ToggleStatusesRepository;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.apache.iceberg.MetadataUpdate;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.SnapshotRef;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.UpdateRequirement;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.IrcClientParsers;
import org.apache.iceberg.rest.requests.CreateTableRequest;
import org.apache.iceberg.rest.requests.UpdateTableRequest;
import org.apache.iceberg.rest.responses.ErrorResponse;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * The multi-table commit route of the Iceberg REST Catalog (IRC), {@code POST
 * /v1/transactions/commit}, at the HTTP boundary, on the full Spring context of {@link
 * IrcViewRoutesTest} (the real internal catalog and the real view engine).
 *
 * <p>What the route promises. A transaction that changes one table is the ordinary commit of that
 * change ({@code POST /v1/namespaces/{namespace}/tables/{table}}): the same stored metadata, the
 * same number of files and row versions, the same refusals with the same messages, the same audit,
 * answered 204 with no body as the spec has it. A transaction with more than one table change is
 * refused with 400 before anything is read or written, because House Tables can only
 * compare-and-set one row at a time, so two tables cannot be committed atomically. The route never
 * creates a table: a table that does not exist is the spec's 404.
 *
 * <p>Every request is built as Iceberg's {@code RESTSessionCatalog.commitTransaction} builds it
 * ({@link IrcWrite#transaction}), with the client's own object mapper.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({IrcReadRoutesTest.StubDefaults.class, IrcViewRoutesTest.ViewAudit.class})
@ContextConfiguration(initializers = {PropertyOverrideContextInitializer.class})
public class IrcTransactionRouteTest {
  private static final String CAPABILITIES = TablesMvcConstants.HTTP_HEADER_CLIENT_CAPABILITIES;

  /** What every refusal of a transaction with several table changes says. */
  private static final String ONE_TABLE = "OpenHouse commits one table per transaction";

  @Autowired MockMvc mvc;
  @Autowired Catalog catalog;
  @Autowired HouseTableRepository houseTables;
  @Autowired ToggleStatusesRepository toggles;
  @Autowired AuditHandler<TableAuditEvent> tableAuditHandler;

  private final List<String[]> created = new ArrayList<>();
  private final List<String[]> views = new ArrayList<>();

  @AfterEach
  void cleanUp() throws Exception {
    for (String[] key : views) {
      NativeViewHttp.delete(mvc, key[0], key[1]);
    }
    for (String[] key : created) {
      mvc.perform(
          MockMvcRequestBuilders.delete(tableUrl(key[0], key[1]))
              .header(CAPABILITIES, ClientCapabilityGate.COLUMN_DEFAULTS));
    }
    views.clear();
    created.clear();
  }

  // ---------------------------------------------------------------- fixtures

  /** The native test table {@code db.t}, without policies. */
  private void create(String db) throws Exception {
    create(db, "t");
  }

  private void create(String db, String table) throws Exception {
    created.add(new String[] {db, table});
    post(mvc, newTable(db, table).toBuilder().policies(null).build())
        .andExpect(status().isCreated());
  }

  private static UpdateTableRequest setProperty(TableMetadata base, String key, String value) {
    return IrcWrite.simple(
        base,
        Collections.singletonList(
            new MetadataUpdate.SetProperties(Collections.singletonMap(key, value))));
  }

  private static UpdateTableRequest changes(TableMetadata base, TableMetadata updated) {
    return IrcWrite.simple(base, updated.changes());
  }

  /** A transaction that changes {@code db.t} only. */
  private MvcResult transaction(String db, UpdateTableRequest request) throws Exception {
    return IrcWrite.transaction(mvc, IrcWrite.change(db, "t", request));
  }

  /** The spec's answer to a committed transaction: 204 and no body. */
  private static void assertCommitted(MvcResult result) throws Exception {
    assertEquals(204, result.getResponse().getStatus(), body(result));
    assertEquals("", body(result));
  }

  private String stored(String db) throws Exception {
    return StoredMetadata.normalized(catalog, db, "t");
  }

  private HouseTable row(String db, String table) {
    return houseTables
        .findById(HouseTablePrimaryKey.builder().databaseId(db).tableId(table).build())
        .orElseThrow(() -> new AssertionError("no row for " + db + "." + table));
  }

  /**
   * A location without its scheme: the API reports {@code file:/tmp/..}, the log {@code /tmp/..}.
   */
  private static String path(String location) {
    return location.startsWith("file:") ? location.substring("file:".length()) : location;
  }

  /** Every file under the database's directory of the test storage, sorted. */
  private static Set<String> filesUnder(String db) {
    Set<String> files = new TreeSet<>();
    collect(new File("/tmp/" + db), files);
    return files;
  }

  private static void collect(File file, Set<String> out) {
    if (!file.exists()) {
      return;
    }
    if (file.isFile()) {
      out.add(file.getPath());
      return;
    }
    File[] children = file.listFiles();
    if (children != null) {
      for (File child : children) {
        collect(child, out);
      }
    }
  }

  /**
   * Everything a write to {@code db.table} could change: its House Tables row, every file under the
   * database's directory, and every stored metadata version.
   */
  private List<Object> state(String db, String table) throws Exception {
    return Arrays.asList(
        row(db, table), filesUnder(db), StoredMetadata.normalized(catalog, db, table));
  }

  private void expectAudited(String db, OperationType type, OperationStatus status) {
    verify(tableAuditHandler, atLeastOnce())
        .audit(
            argThat(
                (TableAuditEvent event) ->
                    event != null
                        && db.equals(event.getDatabaseName())
                        && "t".equals(event.getTableName())
                        && event.getOperationType() == type
                        && event.getOperationStatus() == status));
  }

  // ---------------------------------------------------------------- one table

  /**
   * The same four commits (a property, an append, a branch, a property removal) and one commit
   * without changes are made on two tables, on one through the ordinary commit route and on the
   * other through transactions of one table. Both tables end with the same stored versions, the
   * same number of files and the same audit, and each row points at its last version.
   */
  @Test
  void aTransactionThatChangesOneTableStoresWhatTheOrdinaryCommitStores() throws Exception {
    String ordinary = "irc_tx_o";
    String tx = "irc_tx_t";
    create(ordinary);
    create(tx);
    clearInvocations(tableAuditHandler);

    // A property: a table update.
    TableMetadata o0 = IrcWrite.load(mvc, ordinary, "t");
    IrcWrite.parsed(IrcWrite.commit(mvc, ordinary, "t", setProperty(o0, "user.k", "v")));
    TableMetadata t0 = IrcWrite.load(mvc, tx, "t");
    assertCommitted(transaction(tx, setProperty(t0, "user.k", "v")));
    expectAudited(tx, OperationType.UPDATE, OperationStatus.SUCCESS);
    TableMetadata t1 = IrcWrite.load(mvc, tx, "t");
    assertEquals("v", t1.properties().get("user.k"));

    // An append: a snapshots update.
    TableMetadata o1 = IrcWrite.load(mvc, ordinary, "t");
    Snapshot oSnapshot = IrcWrite.append(o1, "/tmp/irc_tx_o_a.orc");
    IrcWrite.parsed(IrcWrite.commit(mvc, ordinary, "t", o1, IrcWrite.withMain(o1, oSnapshot)));
    Snapshot tSnapshot = IrcWrite.append(t1, "/tmp/irc_tx_t_a.orc");
    assertCommitted(transaction(tx, changes(t1, IrcWrite.withMain(t1, tSnapshot))));
    expectAudited(tx, OperationType.COMMIT, OperationStatus.SUCCESS);
    TableMetadata t2 = IrcWrite.load(mvc, tx, "t");
    assertEquals(tSnapshot.snapshotId(), t2.currentSnapshot().snapshotId());

    // A branch: refs only.
    TableMetadata o2 = IrcWrite.load(mvc, ordinary, "t");
    IrcWrite.parsed(
        IrcWrite.commit(
            mvc,
            ordinary,
            "t",
            o2,
            TableMetadata.buildFrom(o2)
                .setRef("b1", SnapshotRef.branchBuilder(oSnapshot.snapshotId()).build())
                .build()));
    assertCommitted(
        transaction(
            tx,
            changes(
                t2,
                TableMetadata.buildFrom(t2)
                    .setRef("b1", SnapshotRef.branchBuilder(tSnapshot.snapshotId()).build())
                    .build())));

    // No changes: nothing is written, on either route.
    TableMetadata o3 = IrcWrite.load(mvc, ordinary, "t");
    TableMetadata t3 = IrcWrite.load(mvc, tx, "t");
    List<Object> before = state(tx, "t");
    UpdateTableRequest nothing =
        new UpdateTableRequest(
            Collections.singletonList(new UpdateRequirement.AssertTableUUID(o3.uuid())),
            Collections.emptyList());
    IrcWrite.parsed(IrcWrite.commit(mvc, ordinary, "t", nothing));
    assertCommitted(
        transaction(
            tx,
            new UpdateTableRequest(
                Collections.singletonList(new UpdateRequirement.AssertTableUUID(t3.uuid())),
                Collections.emptyList())));
    assertEquals(before, state(tx, "t"));

    // A property removal.
    IrcWrite.parsed(
        IrcWrite.commit(
            mvc,
            ordinary,
            "t",
            o3,
            TableMetadata.buildFrom(o3).removeProperties(Collections.singleton("user.k")).build()));
    assertCommitted(
        transaction(
            tx,
            changes(
                t3,
                TableMetadata.buildFrom(t3)
                    .removeProperties(Collections.singleton("user.k"))
                    .build())));

    // The same stored versions, file for file ...
    assertEquals(stored(ordinary).replace(ordinary, "DB"), stored(tx).replace(tx, "DB"));
    // ... of which there are five: the create and the four commits that changed something ...
    assertEquals(5, StoredMetadata.storedVersions(catalog, tx, "t").size());
    // ... and no file more than the ordinary route leaves (no orphan metadata file).
    assertEquals(filesUnder(ordinary).size(), filesUnder(tx).size());
    // Each row points at its table's last version, which was built on the one before.
    for (String db : new String[] {ordinary, tx}) {
      TableMetadata last = IrcWrite.load(mvc, db, "t");
      assertEquals(path(last.metadataFileLocation()), path(row(db, "t").getTableLocation()));
      assertEquals(4, last.previousFiles().size());
    }
  }

  /**
   * A replace commit (recognized by its shape, as on the ordinary route) goes through a transaction
   * as it goes through the ordinary route: refused with the native message while the table does not
   * allow replaces, stored like the ordinary replace once it does.
   */
  @Test
  void aReplaceInATransactionIsTheOrdinaryReplace() throws Exception {
    String ordinary = "irc_tx_rep_o";
    String tx = "irc_tx_rep_t";
    create(ordinary);
    create(tx);
    String oBefore = stored(ordinary);
    String tBefore = stored(tx);
    String refused =
        assertIrcError(
                IrcWrite.commit(mvc, ordinary, "t", replaceWithData(ordinary, "a")),
                400,
                "BadRequestException")
            .message();
    assertTrue(refused.contains("REPLACE TABLE AS SELECT is not enabled"), refused);
    assertEquals(
        refused.replace(ordinary, "DB"),
        assertIrcError(transaction(tx, replaceWithData(tx, "a")), 400, "BadRequestException")
            .message()
            .replace(tx, "DB"));
    assertEquals(oBefore, stored(ordinary));
    assertEquals(tBefore, stored(tx));

    for (String db : new String[] {ordinary, tx}) {
      IrcWrite.parsed(
          IrcWrite.commit(
              mvc,
              db,
              "t",
              setProperty(
                  IrcWrite.load(mvc, db, "t"), CatalogConstants.RTAS_ENABLED_TABLE_PROP, "true")));
    }
    clearInvocations(tableAuditHandler);
    IrcWrite.parsed(IrcWrite.commit(mvc, ordinary, "t", replaceWithData(ordinary, "b")));
    assertCommitted(transaction(tx, replaceWithData(tx, "b")));
    expectAudited(tx, OperationType.REPLACE_COMMIT, OperationStatus.SUCCESS);
    assertEquals(stored(ordinary).replace(ordinary, "DB"), stored(tx).replace(tx, "DB"));
    assertTrue(IrcWrite.load(mvc, tx, "t").schema().findField("r") != null);
  }

  /**
   * The replace commit {@code RESTCatalog} sends for a replace transaction of {@code db.t} that
   * adds a column and writes one data file.
   */
  private UpdateTableRequest replaceWithData(String db, String file) throws Exception {
    TableMetadata base = IrcWrite.load(mvc, db, "t");
    TableMetadata replacement =
        IrcWrite.replacement(
            base,
            NativeCtas.table(base, IrcWrite.IO)
                .updateSchema()
                .addColumn("r", Types.LongType.get())
                .apply(),
            base.spec(),
            Collections.emptyMap());
    Snapshot snapshot = IrcWrite.append(replacement, "/tmp/" + db + "_" + file + ".orc");
    TableMetadata then =
        IrcWrite.withMain(TableMetadata.buildFrom(replacement).discardChanges().build(), snapshot);
    return IrcWrite.replace(base, replacement, then.changes());
  }

  // ---------------------------------------------------------------- requirements

  /**
   * Every requirement type Iceberg has, failing: the transaction route refuses it with the status,
   * type and message the ordinary commit route gives the same requirement on the same table, and
   * stores nothing. Then every requirement that can hold on a table, holding: committed.
   */
  @Test
  void everyRequirementTypeRefusesAsOnTheOrdinaryCommitRoute() throws Exception {
    String db = "irc_tx_req";
    create(db);
    TableMetadata base = IrcWrite.load(mvc, db, "t");
    Map<String, UpdateRequirement> failing = new LinkedHashMap<>();
    failing.put("AssertTableDoesNotExist", new UpdateRequirement.AssertTableDoesNotExist());
    failing.put(
        "AssertTableUUID",
        new UpdateRequirement.AssertTableUUID("00000000-0000-0000-0000-000000000000"));
    failing.put(
        "AssertViewUUID",
        new UpdateRequirement.AssertViewUUID("00000000-0000-0000-0000-000000000000"));
    failing.put("AssertRefSnapshotID", new UpdateRequirement.AssertRefSnapshotID("main", 42L));
    failing.put(
        "AssertLastAssignedFieldId",
        new UpdateRequirement.AssertLastAssignedFieldId(base.lastColumnId() + 1));
    failing.put(
        "AssertCurrentSchemaID",
        new UpdateRequirement.AssertCurrentSchemaID(base.currentSchemaId() + 1));
    failing.put(
        "AssertLastAssignedPartitionId",
        new UpdateRequirement.AssertLastAssignedPartitionId(base.lastAssignedPartitionId() + 1));
    failing.put(
        "AssertDefaultSpecID", new UpdateRequirement.AssertDefaultSpecID(base.defaultSpecId() + 1));
    failing.put(
        "AssertDefaultSortOrderID",
        new UpdateRequirement.AssertDefaultSortOrderID(base.defaultSortOrderId() + 1));
    // Every requirement type of this Iceberg line is among them.
    Set<String> types = new TreeSet<>();
    for (Class<?> type : UpdateRequirement.class.getDeclaredClasses()) {
      types.add(type.getSimpleName());
    }
    assertEquals(types, new TreeSet<>(failing.keySet()));

    List<Object> before = state(db, "t");
    List<MetadataUpdate> update =
        Collections.singletonList(
            new MetadataUpdate.SetProperties(Collections.singletonMap("user.k", "v")));
    for (Map.Entry<String, UpdateRequirement> requirement : failing.entrySet()) {
      UpdateTableRequest request =
          new UpdateTableRequest(Collections.singletonList(requirement.getValue()), update);
      MvcResult ordinary = IrcWrite.commit(mvc, db, "t", request);
      int status = ordinary.getResponse().getStatus();
      assertTrue(status == 409 || status == 400, requirement.getKey() + ": " + body(ordinary));
      ErrorResponse expected = IrcClientParsers.parseError(body(ordinary));
      ErrorResponse refused =
          assertIrcError(transaction(db, request), expected.code(), expected.type());
      assertEquals(expected.message(), refused.message(), requirement.getKey());
      assertEquals(before, state(db, "t"), requirement.getKey());
    }
    // The requirement every Iceberg commit makes is a 409 the client retries.
    assertIrcError(
        transaction(
            db,
            new UpdateTableRequest(
                Collections.singletonList(failing.get("AssertCurrentSchemaID")), update)),
        409,
        "CommitFailedException");

    assertCommitted(
        transaction(
            db,
            new UpdateTableRequest(
                Arrays.asList(
                    new UpdateRequirement.AssertTableUUID(base.uuid()),
                    new UpdateRequirement.AssertRefSnapshotID("main", null),
                    new UpdateRequirement.AssertLastAssignedFieldId(base.lastColumnId()),
                    new UpdateRequirement.AssertCurrentSchemaID(base.currentSchemaId()),
                    new UpdateRequirement.AssertLastAssignedPartitionId(
                        base.lastAssignedPartitionId()),
                    new UpdateRequirement.AssertDefaultSpecID(base.defaultSpecId()),
                    new UpdateRequirement.AssertDefaultSortOrderID(base.defaultSortOrderId())),
                update)));
    assertEquals("v", IrcWrite.load(mvc, db, "t").properties().get("user.k"));
  }

  /**
   * A transaction built on a base another commit has moved since is refused like the ordinary
   * commit built on it (409, which the client retries), and the retry on the new base commits.
   */
  @Test
  void aTransactionOnAStaleBaseIs409AndTheRetryCommits() throws Exception {
    String db = "irc_tx_stale";
    create(db);
    TableMetadata base = IrcWrite.load(mvc, db, "t");
    Snapshot first = IrcWrite.append(base, "/tmp/irc_tx_stale_a.orc");
    assertCommitted(transaction(db, changes(base, IrcWrite.withMain(base, first))));
    Snapshot second = IrcWrite.append(base, "/tmp/irc_tx_stale_b.orc");
    UpdateTableRequest stale = changes(base, IrcWrite.withMain(base, second));
    List<Object> before = state(db, "t");
    ErrorResponse ordinary =
        assertIrcError(IrcWrite.commit(mvc, db, "t", stale), 409, "CommitFailedException");
    assertEquals(
        ordinary.message(),
        assertIrcError(transaction(db, stale), 409, "CommitFailedException").message());
    assertEquals(before, state(db, "t"));

    TableMetadata fresh = IrcWrite.load(mvc, db, "t");
    Snapshot retried = IrcWrite.append(fresh, "/tmp/irc_tx_stale_c.orc");
    assertCommitted(transaction(db, changes(fresh, IrcWrite.withMain(fresh, retried))));
    assertEquals(retried.snapshotId(), IrcWrite.load(mvc, db, "t").currentSnapshot().snapshotId());
  }

  // ---------------------------------------------------------------- OpenHouse's rules

  /**
   * A change the commit core refuses is refused in a transaction with the ordinary route's status
   * and message, and stores nothing: a reserved property, a new location, a locked table.
   */
  @Test
  void whatTheCommitCoreRefusesIsRefusedAsOnTheOrdinaryCommitRoute() throws Exception {
    String db = "irc_tx_rules";
    create(db);
    TableMetadata base = IrcWrite.load(mvc, db, "t");
    List<UpdateTableRequest> refusedRequests =
        Arrays.asList(
            setProperty(base, "openhouse.tableId", "x"),
            IrcWrite.simple(
                base,
                Collections.singletonList(new MetadataUpdate.SetLocation("/tmp/irc_tx_rules_x"))));
    List<Object> before = state(db, "t");
    for (UpdateTableRequest request : refusedRequests) {
      ErrorResponse ordinary =
          assertIrcError(IrcWrite.commit(mvc, db, "t", request), 400, "BadRequestException");
      assertEquals(
          ordinary.message(),
          assertIrcError(transaction(db, request), 400, "BadRequestException").message());
      assertEquals(before, state(db, "t"));
    }

    lock(mvc, db, "t").andExpect(status().isCreated());
    try {
      TableMetadata locked = IrcWrite.load(mvc, db, "t");
      UpdateTableRequest request = setProperty(locked, "user.k", "v");
      List<Object> lockedState = state(db, "t");
      ErrorResponse ordinary =
          assertIrcError(IrcWrite.commit(mvc, db, "t", request), 400, "BadRequestException");
      assertTrue(ordinary.message().contains("locked"), ordinary.message());
      assertEquals(
          ordinary.message(),
          assertIrcError(transaction(db, request), 400, "BadRequestException").message());
      assertEquals(lockedState, state(db, "t"));
    } finally {
      unlock(mvc, db, "t").andExpect(status().isNoContent());
    }
  }

  // ---------------------------------------------------------------- several tables

  /**
   * A transaction with more than one table change is refused with 400 before anything is read or
   * written: no table's row, files or stored versions change, whether the tables are in one
   * database or two, whether every change could have been committed or one could not, and no table
   * write is audited. Two changes to one table are refused the same way.
   */
  @Test
  void aTransactionWithSeveralTableChangesIsRefusedAndWritesNothing() throws Exception {
    create("irc_tx_m1");
    create("irc_tx_m1", "u");
    create("irc_tx_m2");
    TableMetadata t1 = IrcWrite.load(mvc, "irc_tx_m1", "t");
    TableMetadata u1 = IrcWrite.load(mvc, "irc_tx_m1", "u");
    TableMetadata t2 = IrcWrite.load(mvc, "irc_tx_m2", "t");
    UpdateTableRequest onT1 = IrcWrite.change("irc_tx_m1", "t", setProperty(t1, "user.k", "v"));
    UpdateTableRequest onU1 = IrcWrite.change("irc_tx_m1", "u", setProperty(u1, "user.k", "v"));
    UpdateTableRequest onT2 = IrcWrite.change("irc_tx_m2", "t", setProperty(t2, "user.k", "v"));
    // A change that would be refused on its own: its requirement fails.
    UpdateTableRequest failing =
        IrcWrite.change(
            "irc_tx_m2",
            "t",
            new UpdateTableRequest(
                Collections.singletonList(
                    new UpdateRequirement.AssertTableUUID("00000000-0000-0000-0000-000000000000")),
                onT2.updates()));
    UpdateTableRequest onMissing =
        IrcWrite.change("irc_tx_m2", "missing", setProperty(t2, "user.k", "v"));

    List<Object> before =
        Arrays.asList(state("irc_tx_m1", "t"), state("irc_tx_m1", "u"), state("irc_tx_m2", "t"));
    clearInvocations(tableAuditHandler);
    List<UpdateTableRequest[]> transactions =
        Arrays.asList(
            // Two tables of one database, two of two databases, three tables.
            new UpdateTableRequest[] {onT1, onU1},
            new UpdateTableRequest[] {onT1, onT2},
            new UpdateTableRequest[] {onT1, onU1, onT2},
            // A committable change first, then one that would fail; and the other way round.
            new UpdateTableRequest[] {onT1, failing},
            new UpdateTableRequest[] {failing, onT1},
            new UpdateTableRequest[] {onT1, onMissing},
            new UpdateTableRequest[] {onMissing, onT1},
            // Two changes to one table.
            new UpdateTableRequest[] {onT1, onT1});
    for (UpdateTableRequest[] changes : transactions) {
      String message =
          assertIrcError(IrcWrite.transaction(mvc, changes), 400, "BadRequestException").message();
      assertTrue(message.contains(ONE_TABLE), message);
      assertTrue(message.contains(changes.length + " table changes"), message);
      assertTrue(message.contains("irc_tx_m1.t"), message);
      assertTrue(message.contains("Nothing was committed"), message);
      assertEquals(
          before,
          Arrays.asList(state("irc_tx_m1", "t"), state("irc_tx_m1", "u"), state("irc_tx_m2", "t")));
    }
    assertFalse(tableOrFile("irc_tx_m2", "missing"));
    verify(tableAuditHandler, never())
        .audit(
            argThat(
                (TableAuditEvent event) ->
                    event != null
                        && event.getDatabaseName() != null
                        && event.getDatabaseName().startsWith("irc_tx_m")
                        && event.getOperationType() != OperationType.READ));

    // Each of those changes commits on its own, in a transaction of one table.
    assertCommitted(IrcWrite.transaction(mvc, onT1));
    assertCommitted(IrcWrite.transaction(mvc, onU1));
    assertCommitted(IrcWrite.transaction(mvc, onT2));
    for (String[] key :
        new String[][] {{"irc_tx_m1", "t"}, {"irc_tx_m1", "u"}, {"irc_tx_m2", "t"}}) {
      assertEquals("v", IrcWrite.load(mvc, key[0], key[1]).properties().get("user.k"));
    }
  }

  private boolean tableOrFile(String db, String table) throws Exception {
    boolean file = filesUnder(db).stream().anyMatch(path -> path.contains("/" + table + "-"));
    return file || CommitPathHttp.status(get(mvc, db, table)) != 404;
  }

  // ---------------------------------------------------------------- tables that are not there

  /**
   * The spec's 404: a transaction naming a table that does not exist is {@code
   * NoSuchTableException}, with the ordinary commit route's message, in a database that exists or
   * not, and in a namespace of more than one level (where no table can exist).
   */
  @Test
  void anUnknownTableIs404NoSuchTable() throws Exception {
    String db = "irc_tx_404";
    create(db);
    TableMetadata base = IrcWrite.load(mvc, db, "t");
    UpdateTableRequest request = setProperty(base, "user.k", "v");
    Set<String> files = filesUnder(db);
    for (String[] missing : new String[][] {{db, "nope"}, {"irc_tx_404_no_db", "t"}}) {
      ErrorResponse ordinary =
          assertIrcError(
              IrcWrite.commit(mvc, missing[0], missing[1], request), 404, "NoSuchTableException");
      assertEquals(
          ordinary.message(),
          assertIrcError(
                  IrcWrite.transaction(mvc, IrcWrite.change(missing[0], missing[1], request)),
                  404,
                  "NoSuchTableException")
              .message());
    }
    assertIrcError(
        IrcWrite.transaction(
            mvc,
            UpdateTableRequest.create(
                TableIdentifier.of(db, "t", "snapshots"),
                request.requirements(),
                request.updates())),
        404,
        "NoSuchTableException");
    assertEquals(files, filesUnder(db));
    assertFalse(tableOrFile(db, "nope"));
    assertFalse(filesUnder("irc_tx_404_no_db").iterator().hasNext());
  }

  /**
   * The transaction route does not create tables. The commit that completes a staged create on the
   * ordinary route ({@code assert-create}) names a table that does not exist: 404, and no table, no
   * row and no file is left. (Iceberg's own {@code TableCommit} is built from a base and its
   * update, so its client sends no create here.) On a table that exists, {@code assert-create}
   * fails as on the ordinary route (409).
   */
  @Test
  void aTransactionDoesNotCreateATable() throws Exception {
    String db = "irc_tx_create";
    create(db);
    TableMetadata staged =
        IrcWrite.parsed(
            IrcWrite.create(
                mvc,
                db,
                CreateTableRequest.builder()
                    .withName("staged")
                    .withSchema(IrcWrite.load(mvc, db, "t").schema())
                    .stageCreate()
                    .build()));
    UpdateTableRequest completion = IrcWrite.createCommit(staged, Collections.emptyList());
    Set<String> files = filesUnder(db);
    assertIrcError(
        IrcWrite.transaction(mvc, IrcWrite.change(db, "staged", completion)),
        404,
        "NoSuchTableException");
    assertEquals(files, filesUnder(db));
    assertEquals(404, CommitPathHttp.status(get(mvc, db, "staged")));
    assertFalse(
        houseTables
            .findEntityById(HouseTablePrimaryKey.builder().databaseId(db).tableId("staged").build())
            .isPresent());

    List<Object> before = state(db, "t");
    ErrorResponse ordinary =
        assertIrcError(IrcWrite.commit(mvc, db, "t", completion), 409, "CommitFailedException");
    assertEquals(
        ordinary.message(),
        assertIrcError(transaction(db, completion), 409, "CommitFailedException").message());
    assertEquals(before, state(db, "t"));
  }

  /**
   * A view is not a table: a transaction naming a view is 404 {@code NoSuchTableException}, as the
   * ordinary table commit route answers for a view's name, and the view is unchanged.
   */
  @Test
  void aViewIdentifierIs404NoSuchTableAndTheViewIsUnchanged() throws Exception {
    String db = "irc_tx_view";
    NativeViewHttp.enableViews(toggles, db);
    create(db);
    views.add(new String[] {db, "v"});
    MvcResult view =
        NativeViewHttp.post(
            mvc,
            NativeViewHttp.createBody(
                db,
                "v",
                NativeViewHttp.SCHEMA_ID_NAME,
                "SELECT id, name FROM " + db + ".t",
                Collections.singletonMap("owner", "o")));
    assertEquals(201, NativeViewHttp.code(view), NativeViewHttp.body(view));
    String before = StoredViewMetadata.normalized(houseTables, db, "v");
    Set<String> files = filesUnder(db);

    TableMetadata base = IrcWrite.load(mvc, db, "t");
    UpdateTableRequest request = setProperty(base, "user.k", "v");
    ErrorResponse ordinary =
        assertIrcError(IrcWrite.commit(mvc, db, "v", request), 404, "NoSuchTableException");
    assertEquals(
        ordinary.message(),
        assertIrcError(
                IrcWrite.transaction(mvc, IrcWrite.change(db, "v", request)),
                404,
                "NoSuchTableException")
            .message());
    // A view's own commit, addressed to the table transaction route, is no table commit either.
    assertIrcError(
        IrcWrite.transaction(
            mvc,
            IrcWrite.change(
                db,
                "v",
                new UpdateTableRequest(
                    Collections.singletonList(
                        new UpdateRequirement.AssertViewUUID(
                            "00000000-0000-0000-0000-000000000000")),
                    request.updates()))),
        404,
        "NoSuchTableException");
    assertEquals(before, StoredViewMetadata.normalized(houseTables, db, "v"));
    assertEquals(files, filesUnder(db));
  }

  // ---------------------------------------------------------------- malformed requests

  /**
   * The spec's 400: no body, no changes, a change without a table identifier, an update or a
   * requirement this server does not know. Nothing is written.
   */
  @Test
  void aMalformedTransactionIs400() throws Exception {
    String db = "irc_tx_bad";
    create(db);
    List<Object> before = state(db, "t");
    String identifier = "{\"namespace\":[\"" + db + "\"],\"name\":\"t\"}";
    assertIrcError(
        IrcHttp.perform(mvc, IrcHttp.request(HttpMethod.POST, IrcWrite.TRANSACTION)),
        400,
        "BadRequestException");
    for (String json :
        new String[] {
          "{}",
          "{\"table-changes\":[]}",
          "{\"table-changes\":[{\"requirements\":[],\"updates\":[]}]}",
          "{\"table-changes\":[{\"identifier\":"
              + identifier
              + ",\"requirements\":[],\"updates\":[{\"action\":\"no-such-update\"}]}]}",
          "{\"table-changes\":[{\"identifier\":"
              + identifier
              + ",\"requirements\":[{\"type\":\"no-such-requirement\"}],\"updates\":[]}]}",
          "{\"table-changes\":[{\"identifier\":{\"namespace\":[],\"name\":\"t\"},"
              + "\"requirements\":[],\"updates\":[]}]}"
        }) {
      MvcResult result = IrcWrite.transaction(mvc, json);
      assertIrcError(result, 400, "BadRequestException");
      assertFalse(body(result).contains(ONE_TABLE), json);
    }
    assertEquals(before, state(db, "t"));
  }
}
