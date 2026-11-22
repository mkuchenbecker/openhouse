package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.*;
import static com.linkedin.openhouse.tables.model.IcebergSnapshotsModelTestUtilities.createDummyDataFile;
import static com.linkedin.openhouse.tables.model.IcebergSnapshotsModelTestUtilities.getPartitionSpec;
import static com.linkedin.openhouse.tables.model.IcebergSnapshotsModelTestUtilities.preparePutSnapshotsWithAppendRequest;
import static com.linkedin.openhouse.tables.model.TableModelConstants.buildGetTableResponseBody;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.tables.api.spec.v0.request.CreateUpdateTableRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.request.IcebergSnapshotsRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.LockReason;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.LockState;
import com.linkedin.openhouse.tables.api.spec.v0.response.GetTableResponseBody;
import com.linkedin.openhouse.tables.config.TablesMvcConstants;
import com.linkedin.openhouse.tables.mock.properties.AuthorizationPropertiesInitializer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.iceberg.catalog.Catalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Rules of an update commit on an existing table that had no pin at the HTTP boundary before work
 * item V1: reserved properties (including the raw {@code policies} key), the snapshots route's
 * locked-table wording, and the SYSTEM_ONLY lock that only the lock API may change. Each case pins
 * the status, the stable part of the message and that a follow-up GET shows nothing changed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(
    initializers = {
      PropertyOverrideContextInitializer.class,
      AuthorizationPropertiesInitializer.class
    })
public class CommitPathUpdateRulesTest {
  private static final String RESERVED = "Bad tblproperties provided";

  @Autowired MockMvc mvc;
  @Autowired Catalog catalog;

  private final List<String> created = new ArrayList<>();

  @AfterEach
  void cleanUp() throws Exception {
    for (String db : created) {
      unlock(mvc, db, "t");
      mvc.perform(
          MockMvcRequestBuilders.delete(tableUrl(db, "t") + "/lock/SYSTEM_ONLY")
              .header(TablesMvcConstants.HTTP_HEADER_ACTION_TYPE, "SYSTEM"));
      delete(mvc, db, "t");
    }
    created.clear();
  }

  private String make(String db) throws Exception {
    created.add(db);
    post(mvc, newTable(db, "t")).andExpect(status().isCreated());
    return json(get(mvc, db, "t"));
  }

  private static CreateUpdateTableRequestBody withProp(String getJson, String key, String value) {
    CreateUpdateTableRequestBody b = bodyFromGet(getJson);
    Map<String, String> props = new HashMap<>(b.getTableProperties());
    props.put(key, value);
    return b.toBuilder().tableProperties(props).build();
  }

  @Test
  void putThatChangesAReservedPropertyIsRejected() throws Exception {
    String before = make("ur_reserved");
    expectError(
        put(mvc, withProp(before, "openhouse.tableCreator", "someone-else")), 400, RESERVED);
    expectUnchanged(mvc, before, "ur_reserved", "t");

    expectError(put(mvc, withProp(before, "openhouse.newKey", "x")), 400, RESERVED);
    expectUnchanged(mvc, before, "ur_reserved", "t");

    CreateUpdateTableRequestBody b = bodyFromGet(before);
    Map<String, String> props = new HashMap<>(b.getTableProperties());
    props.remove("openhouse.tableUUID");
    expectError(put(mvc, b.toBuilder().tableProperties(props).build()), 400, RESERVED);
    expectUnchanged(mvc, before, "ur_reserved", "t");
  }

  /** The raw {@code policies} property is reserved; policies change only through the field. */
  @Test
  void putThatEditsTheRawPoliciesPropertyIsRejected() throws Exception {
    String before = make("ur_rawpolicies");
    expectError(put(mvc, withProp(before, "policies", "{}")), 400, RESERVED);
    expectUnchanged(mvc, before, "ur_rawpolicies", "t");
  }

  @Test
  void snapshotsPutOnALockedTableIsRejectedWithTheSnapshotsWording() throws Exception {
    make("ur_lock_snap");
    MvcResult current = get(mvc, "ur_lock_snap", "t").andReturn();
    GetTableResponseBody body = buildGetTableResponseBody(current);
    // Built before the lock; the lock check runs before the base-version check.
    IcebergSnapshotsRequestBody append =
        preparePutSnapshotsWithAppendRequest(
            current,
            body,
            body.getTableLocation(),
            catalog,
            Collections.singletonList(
                createDummyDataFile("/tmp/ur_lock_snap.orc", getPartitionSpec(body))));
    lock(mvc, "ur_lock_snap", "t").andExpect(status().isCreated());
    String locked = json(get(mvc, "ur_lock_snap", "t"));
    expectError(
        putSnapshots(mvc, "ur_lock_snap", "t", append),
        400,
        "Table ur_lock_snap.t is in locked state and cannot be written to");
    expectUnchanged(mvc, locked, "ur_lock_snap", "t");
  }

  private ResultActions systemPut(CreateUpdateTableRequestBody body) throws Exception {
    return mvc.perform(
        MockMvcRequestBuilders.put(tableUrl(body.getDatabaseId(), body.getTableId()))
            .header(TablesMvcConstants.HTTP_HEADER_ACTION_TYPE, "SYSTEM")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body.toJson())
            .accept(MediaType.APPLICATION_JSON));
  }

  private String systemGet(String db) throws Exception {
    return json(
        mvc.perform(
            MockMvcRequestBuilders.get(tableUrl(db, "t"))
                .header(TablesMvcConstants.HTTP_HEADER_ACTION_TYPE, "SYSTEM")
                .accept(MediaType.APPLICATION_JSON)));
  }

  @Test
  void putThatChangesASystemOnlyLockIsRejectedAndOmittingItKeepsIt() throws Exception {
    make("ur_sysonly");
    mvc.perform(
            MockMvcRequestBuilders.post(tableUrl("ur_sysonly", "t") + "/lock")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"locked\":true,\"reason\":\"SYSTEM_ONLY\"}"))
        .andExpect(status().isCreated());
    String before = systemGet("ur_sysonly");
    CreateUpdateTableRequestBody b = bodyFromGet(before);

    expectError(
        systemPut(
            b.toBuilder()
                .policies(
                    b.getPolicies()
                        .toBuilder()
                        .lockState(
                            LockState.builder().locked(true).reason(LockReason.LEGACY).build())
                        .build())
                .build()),
        400,
        "SYSTEM_ONLY lock state can only be changed through the lock lifecycle API.");
    assertEquals(before, systemGet("ur_sysonly"));

    // A PUT that omits the lock state keeps the SYSTEM_ONLY lock.
    Map<String, String> props = new HashMap<>(b.getTableProperties());
    props.put("user.k", "v");
    systemPut(
            b.toBuilder()
                .tableProperties(props)
                .policies(b.getPolicies().toBuilder().lockState(null).build())
                .build())
        .andExpect(status().isOk());
    String after = systemGet("ur_sysonly");
    assertEquals("SYSTEM_ONLY", read(after, "$.policies.lockState.reason"));
    assertEquals("v", read(after, "$.tableProperties['user.k']"));
  }
}
