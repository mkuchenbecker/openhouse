package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.tables.api.spec.v0.request.CreateUpdateTableRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.LockState;
import com.linkedin.openhouse.tables.common.TableType;
import com.linkedin.openhouse.tables.mock.properties.AuthorizationPropertiesInitializer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

/**
 * State rules of the write path: renames, locks, table type, reserved properties and the version
 * chain that optimistic concurrency relies on. Each case pins the HTTP status, the stable leading
 * part of the message and what a follow-up GET returns.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(
    initializers = {
      PropertyOverrideContextInitializer.class,
      AuthorizationPropertiesInitializer.class
    })
public class CommitPathStateRulesTest {
  @Autowired MockMvc mvc;

  private final List<String[]> created = new ArrayList<>();

  @AfterEach
  void cleanUp() throws Exception {
    for (String[] key : created) {
      unlock(mvc, key[0], key[1]);
      delete(mvc, key[0], key[1]);
    }
    created.clear();
    HouseTablesH2Repository.softDeletedTables
        .keySet()
        .removeIf(k -> k.getDatabaseId().startsWith("st_"));
  }

  /** Creates the table and returns what a GET shows for it. */
  private String make(CreateUpdateTableRequestBody b) throws Exception {
    created.add(new String[] {b.getDatabaseId(), b.getTableId()});
    post(mvc, b).andExpect(status().isCreated());
    return json(get(mvc, b.getDatabaseId(), b.getTableId()));
  }

  private static String stripScheme(String location) {
    return location.replaceFirst("^file:", "");
  }

  // ---- rename ----

  @Test
  void renameToADifferentDatabaseIsRejected() throws Exception {
    String before = make(newTable("st_ren_a", "t"));

    expectError(
        rename(mvc, "st_ren_a", "t", "st_ren_b", "t"),
        400,
        "Rename table across databases is not supported");

    expectUnchanged(mvc, before, "st_ren_a", "t");
    get(mvc, "st_ren_b", "t").andExpect(status().isNotFound());
  }

  @Test
  void renameToTheSameNameDifferingOnlyInCaseIsRejected() throws Exception {
    String before = make(newTable("st_ren_case", "t"));

    expectError(
        rename(mvc, "st_ren_case", "t", "st_ren_case", "T"),
        400,
        "Cannot rename a table to the same current db name and table name");

    expectUnchanged(mvc, before, "st_ren_case", "t");
    get(mvc, "st_ren_case", "T").andExpect(status().isNotFound());
  }

  /**
   * Table ids are case sensitive in the catalog: renaming onto a name that differs from an existing
   * table only in case is accepted and leaves both tables in place.
   */
  @Test
  void renameOntoANameDifferingOnlyInCaseFromAnExistingTableSucceeds() throws Exception {
    String other = make(newTable("st_ren_exist_case", "other"));
    make(newTable("st_ren_exist_case", "u"));
    created.add(new String[] {"st_ren_exist_case", "OTHER"});

    rename(mvc, "st_ren_exist_case", "u", "st_ren_exist_case", "OTHER")
        .andExpect(status().isNoContent());

    get(mvc, "st_ren_exist_case", "u").andExpect(status().isNotFound());
    get(mvc, "st_ren_exist_case", "OTHER").andExpect(status().isOk());
    expectUnchanged(mvc, other, "st_ren_exist_case", "other");
  }

  @Test
  void renameOntoAnExistingTableIsAConflictAndBothNamesAreUnchanged() throws Exception {
    String target = make(newTable("st_ren_exist", "other"));
    String source = make(newTable("st_ren_exist", "u"));

    expectError(
        rename(mvc, "st_ren_exist", "u", "st_ren_exist", "other"),
        409,
        "Table local-cluster.st_ren_exist.other already exists");

    expectUnchanged(mvc, target, "st_ren_exist", "other");
    expectUnchanged(mvc, source, "st_ren_exist", "u");
  }

  @Test
  void renameOfAMissingTableIs404() throws Exception {
    expectError(
        rename(mvc, "st_ren_missing", "nope", "st_ren_missing", "nope2"),
        404,
        "User table st_ren_missing.nope cannot be found");
  }

  @Test
  void renameMovesTheTableAndCommitsANewMetadataVersion() throws Exception {
    String before = make(newTable("st_ren_ok", "t"));
    created.add(new String[] {"st_ren_ok", "t2"});

    rename(mvc, "st_ren_ok", "t", "st_ren_ok", "t2").andExpect(status().isNoContent());

    get(mvc, "st_ren_ok", "t").andExpect(status().isNotFound());
    String after = json(get(mvc, "st_ren_ok", "t2"));
    assertEquals(str(before, "$.tableUUID"), str(after, "$.tableUUID"));
    assertTrue(((String) read(after, "$.tableLocation")).contains("/00001-"));
    assertEquals(stripScheme(read(before, "$.tableLocation")), read(after, "$.tableVersion"));
  }

  // ---- lock ----

  @Test
  void lockedTableRejectsUpdateAndRename() throws Exception {
    make(newTable("st_lock", "t"));
    lock(mvc, "st_lock", "t").andExpect(status().isCreated());
    String locked = json(get(mvc, "st_lock", "t"));
    assertEquals(true, read(locked, "$.policies.lockState.locked"));
    CreateUpdateTableRequestBody b = bodyFromGet(locked);
    Map<String, String> props = new HashMap<>(b.getTableProperties());
    props.put("user.x", "y");

    expectError(
        put(mvc, b.toBuilder().tableProperties(props).build()),
        400,
        "Table st_lock.t is in locked state and cannot be updated.");
    expectUnchanged(mvc, locked, "st_lock", "t");

    // even a PUT that changes nothing is refused
    expectError(put(mvc, b), 400, "Table st_lock.t is in locked state and cannot be updated.");
    expectUnchanged(mvc, locked, "st_lock", "t");

    expectError(
        rename(mvc, "st_lock", "t", "st_lock", "t2"),
        400,
        "Table st_lock.t is in locked state and cannot be renamed.");
    expectUnchanged(mvc, locked, "st_lock", "t");
    get(mvc, "st_lock", "t2").andExpect(status().isNotFound());
  }

  @Test
  void putThatUnlocksThroughPoliciesIsRejected() throws Exception {
    make(newTable("st_lock_flip", "t"));
    lock(mvc, "st_lock_flip", "t").andExpect(status().isCreated());
    String locked = json(get(mvc, "st_lock_flip", "t"));
    CreateUpdateTableRequestBody b = bodyFromGet(locked);

    expectError(
        put(
            mvc,
            b.toBuilder()
                .policies(
                    b.getPolicies()
                        .toBuilder()
                        .lockState(LockState.builder().locked(false).build())
                        .build())
                .build()),
        400,
        "Lock state cannot be updated for Table st_lock_flip.t");
    expectUnchanged(mvc, locked, "st_lock_flip", "t");
  }

  /**
   * A table that carries no lock state object (never locked, or unlocked through the lock endpoint)
   * answers a PUT that supplies one with a server error, not a client error.
   */
  @Test
  void putThatSuppliesALockStateOnATableWithoutOneIsAServerError() throws Exception {
    String before = make(newTable("st_lock_npe", "t"));
    assertNull(read(before, "$.policies.lockState"));
    CreateUpdateTableRequestBody b = bodyFromGet(before);

    for (boolean locked : new boolean[] {true, false}) {
      expectError(
          put(
              mvc,
              b.toBuilder()
                  .policies(
                      b.getPolicies()
                          .toBuilder()
                          .lockState(LockState.builder().locked(locked).build())
                          .build())
                  .build()),
          500,
          "java.lang.NullPointerException");
      expectUnchanged(mvc, before, "st_lock_npe", "t");
    }
  }

  @Test
  void lockAndUnlockEachCommitANewMetadataVersion() throws Exception {
    String v0 = make(newTable("st_lock_ver", "t"));
    lock(mvc, "st_lock_ver", "t").andExpect(status().isCreated());
    String v1 = json(get(mvc, "st_lock_ver", "t"));
    unlock(mvc, "st_lock_ver", "t").andExpect(status().isNoContent());
    String v2 = json(get(mvc, "st_lock_ver", "t"));

    assertTrue(((String) read(v0, "$.tableLocation")).contains("/00000-"));
    assertTrue(((String) read(v1, "$.tableLocation")).contains("/00001-"));
    assertTrue(((String) read(v2, "$.tableLocation")).contains("/00002-"));
    assertNull(read(v2, "$.policies.lockState"));
  }

  // ---- table type and reserved properties ----

  @Test
  void putThatChangesTableTypeIsRejected() throws Exception {
    String before = make(newTable("st_type", "t"));
    assertEquals("PRIMARY_TABLE", read(before, "$.tableType"));

    expectError(
        put(mvc, bodyFromGet(before).toBuilder().tableType(TableType.REPLICA_TABLE).build()),
        400,
        "Bad tableType provided: Can't update tableType to [REPLICA_TABLE], existing table is of type [PRIMARY_TABLE]");

    expectUnchanged(mvc, before, "st_type", "t");
  }

  /**
   * Reserved property keys sent on create are accepted but never trusted: the follow-up GET shows
   * the server-set value, or nothing for an unknown reserved key.
   */
  @Test
  void createWithReservedPropertyKeysIsAcceptedAndServerValuesWin() throws Exception {
    Map<String, String> sent = new HashMap<>();
    sent.put("openhouse.custom", "mine");
    sent.put("openhouse.tableId", "other");
    sent.put("openhouse.clusterId", "elsewhere");
    sent.put("openhouse.tableType", "REPLICA_TABLE");
    sent.put("openhouse.tableLocation", "/nowhere");
    sent.put("openhouse.lastModifiedTime", "1");
    sent.put("policies", "{\"retention\":{\"count\":99,\"granularity\":\"DAY\"}}");

    String db = "st_reserved";
    Map<String, String> props = new HashMap<>(newTable(db, "t").getTableProperties());
    props.putAll(sent);
    created.add(new String[] {db, "t"});
    post(mvc, newTable(db, "t").toBuilder().tableProperties(props).build())
        .andExpect(status().isCreated());

    String got = json(get(mvc, db, "t"));
    Map<String, String> gotProps = read(got, "$.tableProperties");
    assertFalse(gotProps.containsKey("openhouse.custom"));
    assertEquals("t", gotProps.get("openhouse.tableId"));
    assertEquals(db, gotProps.get("openhouse.databaseId"));
    assertEquals("local-cluster", gotProps.get("openhouse.clusterId"));
    assertEquals("PRIMARY_TABLE", gotProps.get("openhouse.tableType"));
    assertEquals("PRIMARY_TABLE", read(got, "$.tableType"));
    assertEquals(
        stripScheme(read(got, "$.tableLocation")), gotProps.get("openhouse.tableLocation"));
    assertNotEquals("1", gotProps.get("openhouse.lastModifiedTime"));
    // the policies key is the server's rendering of the policies in the body, not the supplied one
    assertEquals(3, (int) read(got, "$.policies.retention.count"));
    assertFalse(gotProps.get("policies").contains("99"));
  }

  // ---- version chain ----

  @Test
  void tableLocationAndTableVersionAdvanceAsAChain() throws Exception {
    String v0 = make(newTable("st_chain", "t"));
    String uuid = read(v0, "$.tableUUID");
    CreateUpdateTableRequestBody b0 = bodyFromGet(v0);
    Map<String, String> p1 = new HashMap<>(b0.getTableProperties());
    p1.put("user.v", "1");
    put(mvc, b0.toBuilder().tableProperties(p1).build()).andExpect(status().isOk());
    String v1 = json(get(mvc, "st_chain", "t"));
    CreateUpdateTableRequestBody b1 = bodyFromGet(v1);
    Map<String, String> p2 = new HashMap<>(b1.getTableProperties());
    p2.put("user.v", "2");
    put(mvc, b1.toBuilder().tableProperties(p2).build()).andExpect(status().isOk());
    String v2 = json(get(mvc, "st_chain", "t"));

    String[] locations = {
      read(v0, "$.tableLocation"), read(v1, "$.tableLocation"), read(v2, "$.tableLocation")
    };
    for (int n = 0; n < locations.length; n++) {
      Matcher m =
          Pattern.compile(".*/0000" + n + "-[0-9a-f-]{36}\\.metadata\\.json").matcher(locations[n]);
      assertTrue(m.matches(), locations[n]);
      assertTrue(locations[n].contains("/t-" + uuid + "/"), locations[n]);
    }
    // the version a table reports is the location it was committed on top of, minus the scheme
    assertEquals("INITIAL_VERSION", read(v0, "$.tableVersion"));
    assertEquals(stripScheme(locations[0]), read(v1, "$.tableVersion"));
    assertEquals(stripScheme(locations[1]), read(v2, "$.tableVersion"));
  }

  @Test
  void putWithAStaleBaseVersionIsAConflictAndGetShowsTheWinner() throws Exception {
    String v0 = make(newTable("st_stale", "t"));
    CreateUpdateTableRequestBody b0 = bodyFromGet(v0);
    Map<String, String> winner = new HashMap<>(b0.getTableProperties());
    winner.put("user.v", "winner");
    put(mvc, b0.toBuilder().tableProperties(winner).build()).andExpect(status().isOk());
    String afterWinner = json(get(mvc, "st_stale", "t"));

    Map<String, String> loser = new HashMap<>(b0.getTableProperties());
    loser.put("user.v", "loser");
    expectError(
        put(mvc, b0.toBuilder().tableProperties(loser).build()),
        409,
        "Entity with key[local-cluster.st_stale.t] is modified by another process already");

    expectUnchanged(mvc, afterWinner, "st_stale", "t");
    Map<String, String> got = read(afterWinner, "$.tableProperties");
    assertEquals("winner", got.get("user.v"));

    // an unparseable base version is just another stale one
    expectError(
        put(mvc, b0.toBuilder().baseTableVersion("garbage").tableProperties(loser).build()),
        409,
        "Entity with key[local-cluster.st_stale.t] is modified by another process already");
    expectUnchanged(mvc, afterWinner, "st_stale", "t");
  }

  @Test
  void putThatChangesNothingIsOkAndDoesNotAdvanceTheVersion() throws Exception {
    String v0 = make(newTable("st_noop", "t"));
    CreateUpdateTableRequestBody b0 = bodyFromGet(v0);
    Map<String, String> p1 = new HashMap<>(b0.getTableProperties());
    p1.put("user.v", "1");
    put(mvc, b0.toBuilder().tableProperties(p1).build()).andExpect(status().isOk());
    String v1 = json(get(mvc, "st_noop", "t"));

    String noop = json(put(mvc, bodyFromGet(v1)).andExpect(status().isOk()));

    assertEquals(str(v1, "$.tableLocation"), str(noop, "$.tableLocation"));
    assertEquals(str(v1, "$.tableVersion"), str(noop, "$.tableVersion"));
    expectUnchanged(mvc, v1, "st_noop", "t");

    // the optimistic-concurrency check still applies to a PUT that would change nothing
    expectError(
        put(mvc, bodyFromGet(v1).toBuilder().baseTableVersion(read(v0, "$.tableLocation")).build()),
        409,
        "Entity with key[local-cluster.st_noop.t] is modified by another process already");
    expectUnchanged(mvc, v1, "st_noop", "t");
  }
}
