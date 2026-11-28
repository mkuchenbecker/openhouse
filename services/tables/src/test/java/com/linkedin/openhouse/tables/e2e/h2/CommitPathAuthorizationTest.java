package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.*;
import static com.linkedin.openhouse.tables.model.IcebergSnapshotsModelTestUtilities.createDummyDataFile;
import static com.linkedin.openhouse.tables.model.IcebergSnapshotsModelTestUtilities.getPartitionSpec;
import static com.linkedin.openhouse.tables.model.IcebergSnapshotsModelTestUtilities.preparePutSnapshotsWithAppendRequest;
import static com.linkedin.openhouse.tables.model.TableModelConstants.buildGetTableResponseBody;
import static com.linkedin.openhouse.tables.model.TableModelConstants.buildGetTableResponseBodyWithDbTbl;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.internal.catalog.model.HouseTablePrimaryKey;
import com.linkedin.openhouse.internal.catalog.repository.HouseTableRepository;
import com.linkedin.openhouse.tables.api.spec.v0.request.CreateUpdateTableRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.request.IcebergSnapshotsRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.response.GetTableResponseBody;
import com.linkedin.openhouse.tables.authorization.AuthorizationHandler;
import com.linkedin.openhouse.tables.authorization.Privileges;
import com.linkedin.openhouse.tables.common.TableType;
import com.linkedin.openhouse.tables.mock.properties.AuthorizationPropertiesInitializer;
import com.linkedin.openhouse.tables.model.DatabaseDto;
import com.linkedin.openhouse.tables.model.TableDto;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.iceberg.catalog.Catalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Black-box authorization behaviour of every write and read endpoint of the tables service. The
 * full Spring context runs with the real internal catalog; only the {@link AuthorizationHandler} is
 * replaced so that a decision can be switched between allow and deny per test. Every test pins what
 * the HTTP caller sees when denied and that a follow-up GET, with access allowed again, shows
 * nothing changed.
 */
@ExtendWith(LinkedInMainAnswers.class)
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(
    initializers = {
      PropertyOverrideContextInitializer.class,
      AuthorizationPropertiesInitializer.class
    })
public class CommitPathAuthorizationTest {

  @Autowired MockMvc mvc;
  @Autowired Catalog catalog;
  @Autowired HouseTableRepository houseTables;
  @MockBean AuthorizationHandler authorizationHandler;

  private final List<String[]> created = new ArrayList<>();

  @BeforeEach
  void allowEverything() {
    reset(authorizationHandler);
    stubDecision(true);
  }

  @AfterEach
  void cleanUp() throws Exception {
    stubDecision(true);
    for (String[] key : created) {
      delete(mvc, key[0], key[1]);
    }
    created.clear();
    HouseTablesH2Repository.softDeletedTables
        .keySet()
        .removeIf(k -> k.getDatabaseId().startsWith("auth_"));
  }

  private void stubDecision(boolean allowed) {
    when(authorizationHandler.checkAccessDecision(
            any(), any(TableDto.class), any(Privileges.class)))
        .thenReturn(allowed);
    when(authorizationHandler.checkAccessDecision(
            any(), any(DatabaseDto.class), any(Privileges.class)))
        .thenReturn(allowed);
    when(authorizationHandler.checkSystemOnlyLockAccess(any(), any(), any())).thenCallRealMethod();
  }

  /**
   * DELETE over HTTP is a hard delete (the tables service purges), so a soft-deleted row can only
   * be made in the store, the way the house tables service does it for a non-purge delete.
   */
  private void softDelete(String db, String table) {
    houseTables.deleteById(
        HouseTablePrimaryKey.builder().databaseId(db).tableId(table).build(), false);
  }

  private String create(String db, String table) throws Exception {
    created.add(new String[] {db, table});
    post(mvc, newTable(db, table)).andExpect(status().isCreated());
    return json(get(mvc, db, table));
  }

  @Test
  void deniedGetTableIs403() throws Exception {
    create("auth_get", "t");
    stubDecision(false);

    get(mvc, "auth_get", "t")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message", containsString("unauthorized")));
  }

  @Test
  void deniedCreateIs403AndNothingIsCreated() throws Exception {
    stubDecision(false);
    created.add(new String[] {"auth_create", "t"});

    post(mvc, newTable("auth_create", "t"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message", containsString("unauthorized")));

    stubDecision(true);
    get(mvc, "auth_create", "t").andExpect(status().isNotFound());
  }

  @Test
  void deniedUpdateIs403AndTableIsUnchanged() throws Exception {
    String before = create("auth_put", "t");
    Map<String, String> props = new HashMap<>(bodyFromGet(before).getTableProperties());
    props.put("user.denied", "yes");
    CreateUpdateTableRequestBody update =
        bodyFromGet(before).toBuilder().tableProperties(props).build();
    stubDecision(false);

    put(mvc, update)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message", containsString("unauthorized")));

    stubDecision(true);
    assertEquals(before, json(get(mvc, "auth_put", "t")));
  }

  @Test
  void deniedSnapshotsPutIs403AndTableIsUnchanged() throws Exception {
    GetTableResponseBody base = buildGetTableResponseBodyWithDbTbl("auth_snap", "t");
    created.add(new String[] {"auth_snap", "t"});
    MvcResult createResult =
        post(mvc, newTable("auth_snap", "t")).andExpect(status().isCreated()).andReturn();
    GetTableResponseBody created = buildGetTableResponseBody(createResult);
    String before = json(get(mvc, "auth_snap", "t"));
    IcebergSnapshotsRequestBody snapshots =
        preparePutSnapshotsWithAppendRequest(
            createResult,
            created,
            created.getTableLocation(),
            catalog,
            Collections.singletonList(
                createDummyDataFile("/tmp/auth_snap_data.orc", getPartitionSpec(base))));
    stubDecision(false);

    putSnapshots(mvc, "auth_snap", "t", snapshots)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message", containsString("unauthorized")));

    stubDecision(true);
    assertEquals(before, json(get(mvc, "auth_snap", "t")));
  }

  @Test
  void deniedDeleteIs403AndTableStillExists() throws Exception {
    String before = create("auth_del", "t");
    stubDecision(false);

    delete(mvc, "auth_del", "t")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message", containsString("unauthorized")));

    stubDecision(true);
    assertEquals(before, json(get(mvc, "auth_del", "t")));
  }

  @Test
  void deniedRenameIs403AndBothNamesAreUnchanged() throws Exception {
    String before = create("auth_ren", "t");
    stubDecision(false);

    rename(mvc, "auth_ren", "t", "auth_ren", "t_new")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message", containsString("unauthorized")));

    stubDecision(true);
    assertEquals(before, json(get(mvc, "auth_ren", "t")));
    get(mvc, "auth_ren", "t_new").andExpect(status().isNotFound());
  }

  @Test
  void deniedRestoreIs403AndTableStaysDeleted() throws Exception {
    create("auth_rst", "t");
    softDelete("auth_rst", "t");
    long deletedAtMs = deletedAtMsOf("auth_rst", "t");
    stubDecision(false);

    restore(mvc, "auth_rst", "t", deletedAtMs)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message", containsString("unauthorized")));

    stubDecision(true);
    get(mvc, "auth_rst", "t").andExpect(status().isNotFound());
    assertEquals(deletedAtMs, deletedAtMsOf("auth_rst", "t"));
    assertEquals(1, softDeletedListingCount("auth_rst", "t"));
  }

  @Test
  void deniedPurgeIs403AndSoftDeletedTableRemains() throws Exception {
    create("auth_prg", "t");
    softDelete("auth_prg", "t");
    long deletedAtMs = deletedAtMsOf("auth_prg", "t");
    stubDecision(false);

    purge(mvc, "auth_prg", "t", System.currentTimeMillis() + 60_000)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message", containsString("unauthorized")));

    stubDecision(true);
    assertEquals(deletedAtMs, deletedAtMsOf("auth_prg", "t"));
  }

  /**
   * A replica table is written with SYSTEM_ADMIN rather than UPDATE_TABLE_METADATA. A principal who
   * holds every other privilege but not SYSTEM_ADMIN is denied on update, snapshots and rename, and
   * can still read it.
   */
  @Test
  void replicaTableWritesRequireSystemAdmin() throws Exception {
    String db = "auth_replica";
    created.add(new String[] {db, "t"});
    String uuid = "cee3c6a3-a824-443a-832a-d4a1271e1e3e";
    Map<String, String> props = new HashMap<>(newTable(db, "t").getTableProperties());
    props.put("openhouse.tableUUID", uuid);
    props.put("openhouse.databaseId", db);
    props.put("openhouse.tableId", "t");
    props.put("openhouse.tableType", "REPLICA_TABLE");
    props.put("openhouse.tableLocation", "/tmp/unused/" + db + "/t-" + uuid + "/metadata.json");
    post(
            mvc,
            newTable(db, "t")
                .toBuilder()
                .tableType(TableType.REPLICA_TABLE)
                .tableProperties(props)
                .build())
        .andExpect(status().isCreated());
    String before = json(get(mvc, db, "t"));
    assertEquals("REPLICA_TABLE", read(before, "$.tableType"));

    when(authorizationHandler.checkAccessDecision(
            any(), any(TableDto.class), eq(Privileges.SYSTEM_ADMIN)))
        .thenReturn(false);

    Map<String, String> updated = new HashMap<>(bodyFromGet(before).getTableProperties());
    updated.put("user.replica", "yes");
    put(mvc, bodyFromGet(before).toBuilder().tableProperties(updated).build())
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message", containsString("unauthorized")));
    rename(mvc, db, "t", db, "t_new").andExpect(status().isForbidden());
    get(mvc, db, "t").andExpect(status().isOk());
    assertEquals(before, json(get(mvc, db, "t")));
    get(mvc, db, "t_new").andExpect(status().isNotFound());

    // With SYSTEM_ADMIN granted the same update goes through.
    when(authorizationHandler.checkAccessDecision(
            any(), any(TableDto.class), eq(Privileges.SYSTEM_ADMIN)))
        .thenReturn(true);
    put(mvc, bodyFromGet(before).toBuilder().tableProperties(updated).build())
        .andExpect(status().isOk());
    assertTrue(json(get(mvc, db, "t")).contains("user.replica"));
  }

  /** A primary table is unaffected by a missing SYSTEM_ADMIN: UPDATE_TABLE_METADATA is enough. */
  @Test
  void primaryTableUpdateDoesNotNeedSystemAdmin() throws Exception {
    String before = create("auth_primary", "t");
    when(authorizationHandler.checkAccessDecision(
            any(), any(TableDto.class), eq(Privileges.SYSTEM_ADMIN)))
        .thenReturn(false);
    Map<String, String> props = new HashMap<>(bodyFromGet(before).getTableProperties());
    props.put("user.primary", "yes");

    put(mvc, bodyFromGet(before).toBuilder().tableProperties(props).build())
        .andExpect(status().isOk());
    assertFalse(before.contains("user.primary"));
    assertTrue(json(get(mvc, "auth_primary", "t")).contains("user.primary"));
  }

  /**
   * PINNED DEFECT of the native API (work item N5; see {@link
   * NativeCreateDirectoryReusePinnedDefectTest}, which shows what the drop of the second table then
   * destroys). This asserts what the native API does today, which is wrong.
   *
   * <p>Who can put a second table in a live table's directory: anyone who may create tables in the
   * database. The create that names the renamed table's UUID is asked for {@code CREATE_TABLE} on
   * the database and for nothing on the table whose directory it takes; here every decision on a
   * table is a denial, and the create still answers 201 in that directory.
   */
  @Test
  void pinnedDefectCreateInARenamedTablesDirectoryNeedsOnlyTheCreatePrivilegeOnTheDatabase()
      throws Exception {
    String db = "auth_reuse";
    String first = create(db, "a");
    rename(mvc, db, "a", db, "b").andExpect(status().isNoContent());
    created.add(new String[] {db, "b"});
    String firstLocation = str(json(get(mvc, db, "b")), "$.tableLocation");
    String firstDirectory = firstLocation.substring(0, firstLocation.lastIndexOf('/'));
    assertTrue(firstDirectory.endsWith("/" + db + "/a-" + str(first, "$.tableUUID")));

    org.mockito.Mockito.clearInvocations(authorizationHandler);
    when(authorizationHandler.checkAccessDecision(
            any(), any(TableDto.class), any(Privileges.class)))
        .thenReturn(false);
    Map<String, String> props = new HashMap<>(newTable(db, "a").getTableProperties());
    props.put("openhouse.tableUUID", str(first, "$.tableUUID"));
    props.put("openhouse.databaseId", db);
    props.put("openhouse.tableId", "a");
    props.put("openhouse.tableLocation", firstLocation.replaceFirst("^file:", ""));
    String second =
        json(
            post(mvc, newTable(db, "a").toBuilder().tableProperties(props).build())
                .andExpect(status().isCreated()));

    assertEquals(str(first, "$.tableUUID"), str(second, "$.tableUUID"));
    String secondLocation = str(second, "$.tableLocation");
    assertEquals(firstDirectory, secondLocation.substring(0, secondLocation.lastIndexOf('/')));
    org.mockito.Mockito.verify(authorizationHandler)
        .checkAccessDecision(
            any(),
            org.mockito.ArgumentMatchers.argThat(
                (DatabaseDto database) -> database != null && db.equals(database.getDatabaseId())),
            eq(Privileges.CREATE_TABLE));
    org.mockito.Mockito.verify(authorizationHandler, org.mockito.Mockito.never())
        .checkAccessDecision(any(), any(TableDto.class), any(Privileges.class));
  }

  /** The deletion timestamp the soft-deleted listing reports for the table. */
  private long deletedAtMsOf(String db, String table) throws Exception {
    assertEquals(1, softDeletedListingCount(db, table));
    List<Number> deletedAt =
        read(
            json(softDeleted(mvc, db)),
            "$.pageResults.content[?(@.tableId=='" + table + "')].deletedAtMs");
    return deletedAt.get(0).longValue();
  }

  private int softDeletedListingCount(String db, String table) throws Exception {
    String body = json(softDeleted(mvc, db));
    List<Object> rows = read(body, "$.pageResults.content[?(@.tableId=='" + table + "')]");
    return rows.size();
  }
}
