package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.*;
import static com.linkedin.openhouse.tables.model.IcebergSnapshotsModelTestUtilities.createDummyDataFile;
import static com.linkedin.openhouse.tables.model.IcebergSnapshotsModelTestUtilities.getPartitionSpec;
import static com.linkedin.openhouse.tables.model.IcebergSnapshotsModelTestUtilities.preparePutSnapshotsWithAppendRequest;
import static com.linkedin.openhouse.tables.model.TableModelConstants.buildGetTableResponseBody;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.tables.api.spec.v0.request.IcebergSnapshotsRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.response.GetTableResponseBody;
import com.linkedin.openhouse.tables.mock.properties.AuthorizationPropertiesInitializer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.apache.iceberg.SnapshotParser;
import org.apache.iceberg.SnapshotRef;
import org.apache.iceberg.SnapshotRefParser;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The snapshots endpoint: requests it rejects and the effect of a refs-only change. Every case uses
 * its own table because a base version that has reached the commit once is remembered by the
 * catalog; the retry cache itself is out of scope here. The Iceberg catalog bean is read only to
 * see branch refs, which a GET does not expose.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(
    initializers = {
      PropertyOverrideContextInitializer.class,
      AuthorizationPropertiesInitializer.class
    })
public class CommitPathSnapshotsTest {
  @Autowired MockMvc mvc;
  @Autowired Catalog catalog;

  private final List<String[]> created = new ArrayList<>();

  @AfterEach
  void cleanUp() throws Exception {
    for (String[] key : created) {
      delete(mvc, key[0], key[1]);
    }
    created.clear();
  }

  /** Creates {@code db.t} and returns an append request that is valid for it. */
  private IcebergSnapshotsRequestBody appendRequest(String db) throws Exception {
    created.add(new String[] {db, "t"});
    post(mvc, newTable(db, "t")).andExpect(status().isCreated());
    MvcResult current = get(mvc, db, "t").andReturn();
    GetTableResponseBody body = buildGetTableResponseBody(current);
    return preparePutSnapshotsWithAppendRequest(
        current,
        body,
        body.getTableLocation(),
        catalog,
        Collections.singletonList(
            createDummyDataFile("/tmp/" + db + "_data.orc", getPartitionSpec(body))));
  }

  @Test
  void mismatchedInnerBaseVersionIsRejected() throws Exception {
    String db = "rv_snap_base";
    IcebergSnapshotsRequestBody request = appendRequest(db);
    String before = json(get(mvc, db, "t"));

    expectError(
        putSnapshots(
            mvc,
            db,
            "t",
            copy(request)
                .createUpdateTableRequestBody(
                    request
                        .getCreateUpdateTableRequestBody()
                        .toBuilder()
                        .baseTableVersion("other")
                        .build())
                .build()),
        400,
        "Base version provided in metadata and data request body is inconsistent.");

    expectUnchanged(mvc, before, db, "t");
  }

  @Test
  void snapshotThatIsNotJsonIsAServerErrorAndTableIsUnchanged() throws Exception {
    String db = "rv_snap_garbage";
    IcebergSnapshotsRequestBody request = appendRequest(db);
    String before = json(get(mvc, db, "t"));

    expectError(
        putSnapshots(
            mvc,
            db,
            "t",
            copy(request).jsonSnapshots(Collections.singletonList("not a snapshot")).build()),
        500,
        "org.apache.iceberg.exceptions.CommitStateUnknownException");

    expectUnchanged(mvc, before, db, "t");
  }

  @Test
  void snapshotJsonMissingRequiredFieldsIsRejected() throws Exception {
    String empty = "rv_snap_empty";
    IcebergSnapshotsRequestBody request = appendRequest(empty);
    String before = json(get(mvc, empty, "t"));
    expectError(
        putSnapshots(
            mvc, empty, "t", copy(request).jsonSnapshots(Collections.singletonList("{}")).build()),
        400,
        "Cannot parse missing long: snapshot-id");
    expectUnchanged(mvc, before, empty, "t");

    String partial = "rv_snap_partial";
    request = appendRequest(partial);
    before = json(get(mvc, partial, "t"));
    expectError(
        putSnapshots(
            mvc,
            partial,
            "t",
            copy(request).jsonSnapshots(Collections.singletonList("{\"snapshot-id\":1}")).build()),
        400,
        "Cannot parse missing long: timestamp-ms");
    expectUnchanged(mvc, before, partial, "t");
  }

  @Test
  void stagedFlagsAndMissingMetadataBodyAreRejected() throws Exception {
    String create = "rv_snap_stage_create";
    IcebergSnapshotsRequestBody request = appendRequest(create);
    String before = json(get(mvc, create, "t"));
    expectError(
        putSnapshots(
            mvc,
            create,
            "t",
            copy(request)
                .createUpdateTableRequestBody(
                    request.getCreateUpdateTableRequestBody().toBuilder().stageCreate(true).build())
                .build()),
        400,
        "Staged table rv_snap_stage_create.t cannot be created with PUT request, please use the POST apis");
    expectUnchanged(mvc, before, create, "t");

    String replace = "rv_snap_stage_replace";
    request = appendRequest(replace);
    before = json(get(mvc, replace, "t"));
    expectError(
        putSnapshots(
            mvc,
            replace,
            "t",
            copy(request)
                .createUpdateTableRequestBody(
                    request
                        .getCreateUpdateTableRequestBody()
                        .toBuilder()
                        .stageReplace(true)
                        .build())
                .build()),
        400,
        "Replacing staged table rv_snap_stage_replace.t with putSnapshot is not supported");
    expectUnchanged(mvc, before, replace, "t");

    String missing = "rv_snap_no_body";
    request = appendRequest(missing);
    before = json(get(mvc, missing, "t"));
    expectError(
        putSnapshots(mvc, missing, "t", copy(request).createUpdateTableRequestBody(null).build()),
        500,
        "java.lang.NullPointerException");
    expectUnchanged(mvc, before, missing, "t");
  }

  @Test
  void requestWithoutSnapshotsOrRefsChangesNothing() throws Exception {
    String db = "rv_snap_none";
    IcebergSnapshotsRequestBody request = appendRequest(db);
    String before = json(get(mvc, db, "t"));

    putSnapshots(mvc, db, "t", copy(request).jsonSnapshots(null).snapshotRefs(null).build())
        .andExpect(status().isOk());
    expectUnchanged(mvc, before, db, "t");

    putSnapshots(
            mvc,
            db,
            "t",
            copy(request)
                .jsonSnapshots(Collections.emptyList())
                .snapshotRefs(Collections.emptyMap())
                .build())
        .andExpect(status().isOk());
    expectUnchanged(mvc, before, db, "t");
  }

  private HashSet<String> refsOf(String db) {
    Table table = catalog.loadTable(TableIdentifier.of(db, "t"));
    return new HashSet<>(table.refs().keySet());
  }

  /**
   * After one real append, a PUT that carries the existing snapshots and a changed set of refs adds
   * a branch, and the next one removes it. Every one of them commits a new metadata version,
   * including a repeat that changes nothing.
   */
  @Test
  void refsOnlyPutAddsThenRemovesABranch() throws Exception {
    String db = "rv_snap_refs";
    IcebergSnapshotsRequestBody append = appendRequest(db);
    putSnapshots(mvc, db, "t", append).andExpect(status().isOk());
    String afterAppend = json(get(mvc, db, "t"));
    assertEquals(new HashSet<>(Arrays.asList("main")), refsOf(db));
    assertTrue(((String) read(afterAppend, "$.tableLocation")).contains("/00001-"));

    Table table = catalog.loadTable(TableIdentifier.of(db, "t"));
    List<String> snapshots = new ArrayList<>();
    table.snapshots().forEach(s -> snapshots.add(SnapshotParser.toJson(s)));
    String branch =
        SnapshotRefParser.toJson(
            SnapshotRef.branchBuilder(table.currentSnapshot().snapshotId()).build());
    Map<String, String> withFeature = new HashMap<>();
    withFeature.put("main", branch);
    withFeature.put("feature", branch);
    Map<String, String> mainOnly = Collections.singletonMap("main", branch);

    // add the branch
    putSnapshots(mvc, db, "t", refsRequest(afterAppend, snapshots, withFeature))
        .andExpect(status().isOk());
    String afterAdd = json(get(mvc, db, "t"));
    assertEquals(new HashSet<>(Arrays.asList("main", "feature")), refsOf(db));
    assertTrue(((String) read(afterAdd, "$.tableLocation")).contains("/00002-"));
    assertEquals(
        str(afterAppend, "$.tableLocation").replaceFirst("^file:", ""),
        str(afterAdd, "$.tableVersion"));

    // remove it again
    putSnapshots(mvc, db, "t", refsRequest(afterAdd, snapshots, mainOnly))
        .andExpect(status().isOk());
    String afterRemove = json(get(mvc, db, "t"));
    assertEquals(new HashSet<>(Arrays.asList("main")), refsOf(db));
    assertTrue(((String) read(afterRemove, "$.tableLocation")).contains("/00003-"));

    // repeating the removal changes nothing but still commits a new metadata version
    putSnapshots(mvc, db, "t", refsRequest(afterRemove, snapshots, mainOnly))
        .andExpect(status().isOk());
    String afterRepeat = json(get(mvc, db, "t"));
    assertEquals(new HashSet<>(Arrays.asList("main")), refsOf(db));
    assertTrue(((String) read(afterRepeat, "$.tableLocation")).contains("/00004-"));
  }

  private static IcebergSnapshotsRequestBody refsRequest(
      String currentGet, List<String> snapshots, Map<String, String> refs) {
    return IcebergSnapshotsRequestBody.builder()
        .baseTableVersion(str(currentGet, "$.tableLocation"))
        .createUpdateTableRequestBody(bodyFromGet(currentGet))
        .jsonSnapshots(snapshots)
        .snapshotRefs(refs)
        .build();
  }
}
