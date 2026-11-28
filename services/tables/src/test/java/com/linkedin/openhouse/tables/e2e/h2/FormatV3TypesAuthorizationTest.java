package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.common.api.validator.ValidatorConstants.INITIAL_TABLE_VERSION;
import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.*;
import static com.linkedin.openhouse.tables.e2e.h2.FormatV3.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.linkedin.openhouse.cluster.configs.ClusterProperties;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.tables.api.spec.v0.request.CreateUpdateTableRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.request.IcebergSnapshotsRequestBody;
import com.linkedin.openhouse.tables.authorization.AuthorizationHandler;
import com.linkedin.openhouse.tables.authorization.Privileges;
import com.linkedin.openhouse.tables.formatversion.IcebergV3;
import com.linkedin.openhouse.tables.mock.properties.AuthorizationPropertiesInitializer;
import com.linkedin.openhouse.tables.model.DatabaseDto;
import com.linkedin.openhouse.tables.model.TableDto;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.iceberg.catalog.Catalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * With the format v3 switch off, whether a write carrying a v3-only column type gets the request
 * validation's old 400 depends on the stored table's format version (a table already at v3 keeps
 * v3's rules). That is decided after the write's privilege check, from the table the write loads
 * for that check, so a caller without access cannot learn from the answer whether a table is at v3,
 * and nothing is read for it before the privilege is decided.
 */
@SpringBootTest(properties = SWITCH_ON)
@AutoConfigureMockMvc
@Import({IrcReadRoutesTest.StubDefaults.class, IrcHouseTableReadsTest.CountingStandIn.class})
@ContextConfiguration(
    initializers = {
      PropertyOverrideContextInitializer.class,
      AuthorizationPropertiesInitializer.class
    })
public class FormatV3TypesAuthorizationTest {

  @Autowired MockMvc mvc;
  @Autowired Catalog catalog;
  @Autowired ClusterProperties clusterProperties;
  @MockBean AuthorizationHandler authorizationHandler;

  private final List<String> created = new ArrayList<>();

  /** The House Tables reads made before each privilege decision of the last request. */
  private final List<Integer> readsAtDecision = new ArrayList<>();

  @BeforeEach
  void allow() {
    reset(authorizationHandler);
    decide(true);
  }

  @AfterEach
  void cleanUp() throws Exception {
    ReflectionTestUtils.setField(clusterProperties, "clusterIcebergFormatV3Enabled", true);
    decide(true);
    for (String db : created) {
      delete(mvc, db, "t");
    }
    created.clear();
  }

  private void decide(boolean allowed) {
    when(authorizationHandler.checkAccessDecision(
            any(), any(TableDto.class), any(Privileges.class)))
        .thenAnswer(
            invocation -> {
              readsAtDecision.add(IrcHouseTableReadsTest.READS.size());
              return allowed;
            });
    when(authorizationHandler.checkAccessDecision(
            any(), any(DatabaseDto.class), any(Privileges.class)))
        .thenAnswer(
            invocation -> {
              readsAtDecision.add(IrcHouseTableReadsTest.READS.size());
              return allowed;
            });
    when(authorizationHandler.checkSystemOnlyLockAccess(any(), any(), any())).thenCallRealMethod();
  }

  private String make(CreateUpdateTableRequestBody body) throws Exception {
    created.add(body.getDatabaseId());
    assertEquals(201, status(post(mvc, body)));
    return json(get(mvc, body.getDatabaseId(), "t"));
  }

  private static CreateUpdateTableRequestBody withUnknown(CreateUpdateTableRequestBody body)
      throws Exception {
    return body.toBuilder().schema(addColumn(body.getSchema(), "u", "unknown", null, null)).build();
  }

  /** Sends {@code request} afresh and returns the House Tables reads before its first decision. */
  private int readsBeforeDecision(Request request) throws Exception {
    IrcHouseTableReadsTest.READS.clear();
    readsAtDecision.clear();
    request.send();
    return readsAtDecision.isEmpty() ? -1 : readsAtDecision.get(0);
  }

  @FunctionalInterface
  private interface Request {
    ResultActions send() throws Exception;
  }

  private static IcebergSnapshotsRequestBody snapshots(
      String base, CreateUpdateTableRequestBody body) {
    return IcebergSnapshotsRequestBody.builder()
        .baseTableVersion(base)
        .jsonSnapshots(Collections.emptyList())
        .snapshotRefs(Collections.emptyMap())
        .createUpdateTableRequestBody(body)
        .build();
  }

  @Test
  void aCallerWithoutAccessCannotTellAV3TableFromAV2Table() throws Exception {
    assumeTrue(IcebergV3.supported());
    String v2 = make(plain("fv3auth_v2"));
    String v3 = make(asking(plain("fv3auth_v3"), "3"));
    created.add("fv3auth_none");
    ReflectionTestUtils.setField(clusterProperties, "clusterIcebergFormatV3Enabled", false);
    decide(false);

    for (String stored : new String[] {v2, v3}) {
      CreateUpdateTableRequestBody plainBody = bodyFromGet(stored);
      CreateUpdateTableRequestBody typed = withUnknown(plainBody);
      String db = typed.getDatabaseId();
      ResultActions[] answer = new ResultActions[1];
      // A native PUT: 403, after the same reads as the same PUT without the v3-only type.
      int control = readsBeforeDecision(() -> put(mvc, plainBody));
      int reads = readsBeforeDecision(() -> answer[0] = put(mvc, typed));
      assertEquals(403, status(answer[0]), db + ": " + json(answer[0]));
      assertEquals(control, reads, db + ": " + IrcHouseTableReadsTest.READS);
      // A snapshots PUT on the table alike.
      String base = str(stored, "$.tableLocation");
      int snapshotsControl =
          readsBeforeDecision(() -> putSnapshots(mvc, db, "t", snapshots(base, plainBody)));
      int snapshotsReads =
          readsBeforeDecision(() -> answer[0] = putSnapshots(mvc, db, "t", snapshots(base, typed)));
      assertEquals(403, status(answer[0]), db + ": " + json(answer[0]));
      assertEquals(snapshotsControl, snapshotsReads, db + ": " + IrcHouseTableReadsTest.READS);
    }

    // A PUT that would create the table: 403 too, after the same reads.
    CreateUpdateTableRequestBody none = plain("fv3auth_none");
    ResultActions[] answer = new ResultActions[1];
    int control = readsBeforeDecision(() -> put(mvc, none));
    int reads = readsBeforeDecision(() -> answer[0] = put(mvc, withUnknown(none)));
    assertEquals(403, status(answer[0]), json(answer[0]));
    assertEquals(control, reads, IrcHouseTableReadsTest.READS.toString());
    // A snapshots PUT that would create it is a create, which the request validation answers from
    // the request alone, as before format v3: the same 400 whatever is stored, and nothing read.
    IrcHouseTableReadsTest.READS.clear();
    CreateUpdateTableRequestBody creating =
        withUnknown(none).toBuilder().baseTableVersion(INITIAL_TABLE_VERSION).build();
    ResultActions create =
        putSnapshots(mvc, "fv3auth_none", "t", snapshots(INITIAL_TABLE_VERSION, creating));
    assertEquals(400, status(create), json(create));
    assertEquals(
        "schema : Cannot parse type string to primitive: unknown; sortOrder : provided "
            + creating.getSortOrder()
            + " is not a valid sort order",
        message(create));
    assertEquals(Collections.emptyList(), IrcHouseTableReadsTest.READS);
  }

  @Test
  void aCallerWithAccessGetsTheOld400ForAV2TableAndAV3TableTakesTheType() throws Exception {
    assumeTrue(IcebergV3.supported());
    String v2 = make(plain("fv3auth_ok_v2"));
    String v3 = make(asking(plain("fv3auth_ok_v3"), "3"));
    ReflectionTestUtils.setField(clusterProperties, "clusterIcebergFormatV3Enabled", false);

    CreateUpdateTableRequestBody onV2 = withUnknown(bodyFromGet(v2));
    ResultActions refused = put(mvc, onV2);
    assertEquals(400, status(refused), json(refused));
    assertEquals(
        "schema : Cannot parse type string to primitive: unknown; sortOrder : provided "
            + onV2.getSortOrder()
            + " is not a valid sort order",
        message(refused));
    assertEquals(v2, json(get(mvc, "fv3auth_ok_v2", "t")));

    ResultActions taken = put(mvc, withUnknown(bodyFromGet(v3)));
    assertEquals(200, status(taken), json(taken));
    assertEquals(
        "unknown",
        FormatV3.metadata(catalog, "fv3auth_ok_v3", "t").schema().findField("u").type().toString());
  }
}
