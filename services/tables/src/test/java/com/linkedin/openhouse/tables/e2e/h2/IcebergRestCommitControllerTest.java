package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.model.IcebergSnapshotsModelTestUtilities.*;
import static com.linkedin.openhouse.tables.model.TableModelConstants.*;

import com.jayway.jsonpath.JsonPath;
import com.linkedin.openhouse.cluster.storage.StorageManager;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.internal.catalog.model.HouseTable;
import com.linkedin.openhouse.internal.catalog.model.HouseTablePrimaryKey;
import com.linkedin.openhouse.internal.catalog.repository.HouseTableRepository;
import com.linkedin.openhouse.internal.catalog.repository.exception.HouseTableConcurrentUpdateException;
import com.linkedin.openhouse.internal.catalog.repository.exception.HouseTableRepositoryStateUnknownException;
import com.linkedin.openhouse.tables.api.spec.v0.response.GetTableResponseBody;
import com.linkedin.openhouse.tables.resthandler.IcebergRestSerde;
import com.linkedin.openhouse.tables.resthandler.RestUpdateValidator;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import org.apache.iceberg.BaseTable;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.MetadataUpdate;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.SnapshotRef;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.UpdateRequirement;
import org.apache.iceberg.UpdateRequirements;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableList;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableSet;
import org.apache.iceberg.rest.requests.UpdateTableRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * E2E tests for the Iceberg REST-native commit prototype ({@code POST
 * /v1/rest/namespaces/{ns}/tables/{t}/commit}) against the H2-backed HTS repository and local-FS
 * storage — the full stack from spec-JSON request through {@code IcebergRestCommitService}'s retry
 * loop into the unmodified {@code OpenHouseInternalTableOperations} commit primitive.
 *
 * <p>Covers the prototype test matrix from the REST-native migration design (Appendix D §6):
 * happy-path append, same-ref conflict → 409, independent concurrent commits via server-side
 * re-apply, requirement failure → 409 with no metadata write, ambiguous HTS failure →
 * CommitStateUnknown with orphan-only residue, the #612-mirror no-snapshot-loss regression,
 * explicit-only snapshot expiry, and preserved-key rejection.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(initializers = PropertyOverrideContextInitializer.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
@WithMockUser(username = "testUser")
public class IcebergRestCommitControllerTest {

  private static final String DATABASE_ID = "d1";
  private static final String MAIN = SnapshotRef.MAIN_BRANCH;

  @Autowired MockMvc mvc;

  @Autowired Catalog catalog;

  @Autowired StorageManager storageManager;

  @Autowired HouseTableRepository houseTableRepository;

  @SpyBean @Autowired RestUpdateValidator restUpdateValidator;

  @Autowired IcebergRestSerde icebergRestSerde;

  @AfterEach
  public void clearFaultInjection() {
    HouseTablesH2Repository.clearSaveHooks();
  }

  /** Everything a "writer" derived from one observed table state needs to submit a commit. */
  @AllArgsConstructor
  private static final class PreparedCommit {
    private final TableMetadata base;
    private final Snapshot snapshot;
    private final UpdateTableRequest request;
  }

  // ---------------------------------------------------------------------------------------------
  // (a) Snapshot-append happy path
  // ---------------------------------------------------------------------------------------------

  @Test
  public void testSnapshotAppendHappyPath() throws Exception {
    String tableId = "restappend";
    createTestTable(tableId);

    String priorHtsLocation = htsRow(tableId).getTableLocation();
    long metadataFilesBefore = countMetadataFiles(tableId);

    PreparedCommit commit = prepareAppendCommit(tableId, "rest_happy.orc");
    MvcResult result = postCommit(tableId, commit.request);

    Assertions.assertEquals(200, result.getResponse().getStatus());
    String responseMetadataLocation =
        JsonPath.read(result.getResponse().getContentAsString(), "$.metadata-location");

    // The response points at exactly the metadata.json the HTS row was CASed to.
    Assertions.assertEquals(htsRow(tableId).getTableLocation(), responseMetadataLocation);
    Assertions.assertNotEquals(priorHtsLocation, responseMetadataLocation);

    // Server re-derived metadata: the appended snapshot landed and main points at it.
    TableMetadata current = currentMetadata(tableId);
    Assertions.assertEquals(responseMetadataLocation, current.metadataFileLocation());
    Assertions.assertEquals(ImmutableSet.of(commit.snapshot.snapshotId()), snapshotIds(current));
    Assertions.assertEquals(commit.snapshot.snapshotId(), current.ref(MAIN).snapshotId());

    // Catalog-owned bookkeeping is stamped server-side: tableVersion == prior location.
    Assertions.assertEquals(priorHtsLocation, current.properties().get("openhouse.tableVersion"));
    Assertions.assertEquals(
        responseMetadataLocation, current.properties().get("openhouse.tableLocation"));

    // Exactly one new metadata.json was written.
    Assertions.assertEquals(metadataFilesBefore + 1, countMetadataFiles(tableId));
  }

  // ---------------------------------------------------------------------------------------------
  // (b) Concurrent-commit conflict on the same ref → 409
  // ---------------------------------------------------------------------------------------------

  @Test
  public void testConcurrentSameRefCommitConflict() throws Exception {
    String tableId = "restconflict";
    createTestTable(tableId);
    commitAppend(tableId, "rest_conflict_base.orc");

    // Two writers derive their commits from the same observed state T1.
    PreparedCommit winner = prepareAppendCommit(tableId, "rest_conflict_w.orc");
    PreparedCommit loser = prepareAppendCommit(tableId, "rest_conflict_l.orc");

    Assertions.assertEquals(200, postCommit(tableId, winner.request).getResponse().getStatus());

    String htsLocationAfterWinner = htsRow(tableId).getTableLocation();
    long metadataFilesAfterWinner = countMetadataFiles(tableId);

    MvcResult loserResult = postCommit(tableId, loser.request);
    Assertions.assertEquals(409, loserResult.getResponse().getStatus());
    Assertions.assertEquals(
        "CommitFailedException",
        JsonPath.read(loserResult.getResponse().getContentAsString(), "$.error.type"));

    // The loser wrote nothing: no metadata.json, and the HTS pointer is untouched.
    Assertions.assertEquals(htsLocationAfterWinner, htsRow(tableId).getTableLocation());
    Assertions.assertEquals(metadataFilesAfterWinner, countMetadataFiles(tableId));

    TableMetadata current = currentMetadata(tableId);
    Assertions.assertTrue(snapshotIds(current).contains(winner.snapshot.snapshotId()));
    Assertions.assertFalse(snapshotIds(current).contains(loser.snapshot.snapshotId()));
  }

  // ---------------------------------------------------------------------------------------------
  // (c) Logically-independent concurrent commits succeed via server-side re-apply
  // ---------------------------------------------------------------------------------------------

  @Test
  public void testIndependentCommitReappliedOnAdvancedBase() throws Exception {
    String tableId = "restreapply";
    createTestTable(tableId);
    commitAppend(tableId, "rest_reapply_base.orc");

    // A property-only writer derives its request at T1: assert-table-uuid only — no ref
    // assertion, because it does not touch refs.
    TableMetadata baseAtDerivation = currentMetadata(tableId);
    List<MetadataUpdate> propertyUpdates =
        ImmutableList.of(
            new MetadataUpdate.SetProperties(ImmutableMap.of("user.independent", "yes")));
    UpdateTableRequest propertyRequest =
        new UpdateTableRequest(
            UpdateRequirements.forUpdateTable(baseAtDerivation, propertyUpdates), propertyUpdates);

    // A snapshot commit lands first, advancing the table to T2.
    Snapshot racingSnapshot = commitAppend(tableId, "rest_reapply_racer.orc");

    // The property commit still succeeds: the server re-applies it onto the fresh base T2.
    MvcResult result = postCommit(tableId, propertyRequest);
    Assertions.assertEquals(200, result.getResponse().getStatus());

    TableMetadata current = currentMetadata(tableId);
    Assertions.assertEquals("yes", current.properties().get("user.independent"));
    Assertions.assertTrue(snapshotIds(current).contains(racingSnapshot.snapshotId()));
    Assertions.assertEquals(racingSnapshot.snapshotId(), current.ref(MAIN).snapshotId());
  }

  @Test
  public void testStoreLevelRaceRetriedServerSideInvisibleToClient() throws Exception {
    String tableId = "reststorerace";
    createTestTable(tableId);
    Snapshot existing = commitAppend(tableId, "rest_storerace_base.orc");

    TableMetadata base = currentMetadata(tableId);
    List<MetadataUpdate> updates =
        ImmutableList.of(new MetadataUpdate.SetProperties(ImmutableMap.of("user.raced", "yes")));
    UpdateTableRequest request =
        new UpdateTableRequest(UpdateRequirements.forUpdateTable(base, updates), updates);

    // Simulate losing the HTS @Version CAS on the first save attempt only — the arbiter's
    // concurrent-update signal — while requirements still hold on refresh.
    Mockito.clearInvocations(spyOf(restUpdateValidator));
    HouseTablesH2Repository.clearSaveHooks();
    HouseTablesH2Repository.SAVE_FAILURES.add(
        new HouseTableConcurrentUpdateException(tableId, null));

    MvcResult result = postCommit(tableId, request);

    // The client saw a single 200; the server retried the store-level race internally: two save
    // attempts, two full validate-rebuild attempts.
    Assertions.assertEquals(200, result.getResponse().getStatus());
    Assertions.assertEquals(2, HouseTablesH2Repository.SAVE_ATTEMPTS.get());
    Mockito.verify(spyOf(restUpdateValidator), Mockito.times(2))
        .validateAgainstBase(Mockito.any(), Mockito.any());

    TableMetadata current = currentMetadata(tableId);
    Assertions.assertEquals("yes", current.properties().get("user.raced"));
    Assertions.assertTrue(snapshotIds(current).contains(existing.snapshotId()));
  }

  // ---------------------------------------------------------------------------------------------
  // (d) Requirement failure → 409, no metadata write, no server-side retry
  // ---------------------------------------------------------------------------------------------

  @Test
  public void testRequirementFailureIs409WithoutWriteOrRetry() throws Exception {
    String tableId = "restreqfail";
    createTestTable(tableId);
    commitAppend(tableId, "rest_reqfail_base.orc");

    String htsLocationBefore = htsRow(tableId).getTableLocation();
    long metadataFilesBefore = countMetadataFiles(tableId);

    PreparedCommit commit = prepareAppendCommit(tableId, "rest_reqfail.orc");
    TableMetadata base = currentMetadata(tableId);
    // Requirements assert a snapshot id that was never on main.
    UpdateTableRequest badRequest =
        new UpdateTableRequest(
            ImmutableList.of(
                new UpdateRequirement.AssertTableUUID(base.uuid()),
                new UpdateRequirement.AssertRefSnapshotID(MAIN, 424242L)),
            commit.request.updates());

    Mockito.clearInvocations(spyOf(restUpdateValidator));
    HouseTablesH2Repository.clearSaveHooks();
    MvcResult result = postCommit(tableId, badRequest);

    Assertions.assertEquals(409, result.getResponse().getStatus());
    Assertions.assertEquals(
        "CommitFailedException",
        JsonPath.read(result.getResponse().getContentAsString(), "$.error.type"));

    // No metadata.json write was attempted and the HTS row is untouched.
    Assertions.assertEquals(metadataFilesBefore, countMetadataFiles(tableId));
    Assertions.assertEquals(htsLocationBefore, htsRow(tableId).getTableLocation());
    Assertions.assertEquals(0, HouseTablesH2Repository.SAVE_ATTEMPTS.get());

    // Requirement failures are excluded from server-side retry: exactly one attempt ran.
    Mockito.verify(spyOf(restUpdateValidator), Mockito.times(1))
        .validateAgainstBase(Mockito.any(), Mockito.any());
  }

  // ---------------------------------------------------------------------------------------------
  // (e) Ambiguous HTS failure → CommitStateUnknown, orphan-file-only residue
  // ---------------------------------------------------------------------------------------------

  @Test
  public void testAmbiguousHtsFailureIsCommitStateUnknownWithOrphanOnly() throws Exception {
    String tableId = "reststateunknown";
    createTestTable(tableId);
    Snapshot existing = commitAppend(tableId, "rest_unknown_base.orc");

    String htsLocationBefore = htsRow(tableId).getTableLocation();
    long metadataFilesBefore = countMetadataFiles(tableId);

    PreparedCommit commit = prepareAppendCommit(tableId, "rest_unknown.orc");
    // Also shrink the commit-status probe so the ambiguity check does not slow the suite.
    List<MetadataUpdate> updates =
        ImmutableList.<MetadataUpdate>builder()
            .addAll(commit.request.updates())
            .add(
                new MetadataUpdate.SetProperties(
                    ImmutableMap.of(
                        "commit.status-check.num-retries", "1",
                        "commit.status-check.min-wait-ms", "10",
                        "commit.status-check.max-wait-ms", "20",
                        "commit.status-check.total-timeout-ms", "100")))
            .build();
    UpdateTableRequest request = new UpdateTableRequest(commit.request.requirements(), updates);

    HouseTablesH2Repository.clearSaveHooks();
    HouseTablesH2Repository.SAVE_FAILURES.add(
        new HouseTableRepositoryStateUnknownException(tableId, null));

    MvcResult result = postCommit(tableId, request);

    Assertions.assertEquals(500, result.getResponse().getStatus());
    Assertions.assertEquals(
        "CommitStateUnknownException",
        JsonPath.read(result.getResponse().getContentAsString(), "$.error.type"));

    // Residue is exactly one unreferenced metadata.json orphan; the HTS pointer never moved.
    Assertions.assertEquals(metadataFilesBefore + 1, countMetadataFiles(tableId));
    Assertions.assertEquals(htsLocationBefore, htsRow(tableId).getTableLocation());

    // The ambiguity is terminal for the request: exactly one save attempt, no blind retry.
    Assertions.assertEquals(1, HouseTablesH2Repository.SAVE_ATTEMPTS.get());

    // A follow-up load still serves the pre-commit metadata.
    TableMetadata current = currentMetadata(tableId);
    Assertions.assertEquals(htsLocationBefore, current.metadataFileLocation());
    Assertions.assertEquals(ImmutableSet.of(existing.snapshotId()), snapshotIds(current));
    Assertions.assertFalse(snapshotIds(current).contains(commit.snapshot.snapshotId()));
  }

  // ---------------------------------------------------------------------------------------------
  // (f) #612 mirror: a stale writer cannot expire a racing snapshot
  // ---------------------------------------------------------------------------------------------

  @Test
  public void testStaleWriterCannotExpireRacingSnapshot() throws Exception {
    String tableId = "restnoloss";
    createTestTable(tableId);
    Snapshot s1 = commitAppend(tableId, "rest_noloss_s1.orc");

    // The writer derives its commit at T_X (main → S1)...
    PreparedCommit staleWriter = prepareAppendCommit(tableId, "rest_noloss_w.orc");

    // ...then a racing commit lands S_r, advancing the table to T_Y.
    Snapshot racing = commitAppend(tableId, "rest_noloss_r.orc");

    // The stale writer's assert-ref-snapshot-id(main, S1) fails against T_Y: 409, not a silent
    // rebase. An append has no vocabulary to delete S_r in the first place.
    MvcResult staleResult = postCommit(tableId, staleWriter.request);
    Assertions.assertEquals(409, staleResult.getResponse().getStatus());

    TableMetadata afterConflict = currentMetadata(tableId);
    Assertions.assertTrue(
        snapshotIds(afterConflict).contains(racing.snapshotId()),
        "The racing snapshot must survive the stale writer's rejected commit");
    Assertions.assertTrue(snapshotIds(afterConflict).contains(s1.snapshotId()));
    Assertions.assertFalse(snapshotIds(afterConflict).contains(staleWriter.snapshot.snapshotId()));

    // The writer refreshes, re-derives at T_Y, and the recomputed commit keeps every snapshot.
    PreparedCommit recomputed = prepareAppendCommit(tableId, "rest_noloss_w2.orc");
    Assertions.assertEquals(200, postCommit(tableId, recomputed.request).getResponse().getStatus());

    TableMetadata finalMetadata = currentMetadata(tableId);
    Assertions.assertEquals(
        ImmutableSet.of(s1.snapshotId(), racing.snapshotId(), recomputed.snapshot.snapshotId()),
        snapshotIds(finalMetadata));
    Assertions.assertEquals(recomputed.snapshot.snapshotId(), finalMetadata.ref(MAIN).snapshotId());
  }

  // ---------------------------------------------------------------------------------------------
  // (g) Snapshot expiry only via explicit remove-snapshots
  // ---------------------------------------------------------------------------------------------

  @Test
  public void testSnapshotRemovalRequiresExplicitRemoveSnapshotsUpdate() throws Exception {
    String tableId = "restexpiry";
    createTestTable(tableId);
    Snapshot s1 = commitAppend(tableId, "rest_expiry_s1.orc");
    Snapshot s2 = commitAppend(tableId, "rest_expiry_s2.orc");

    TableMetadata beforeExpiry = currentMetadata(tableId);
    Assertions.assertEquals(
        ImmutableSet.of(s1.snapshotId(), s2.snapshotId()), snapshotIds(beforeExpiry));

    // Deletion must name its victim: an explicit remove-snapshots update for S1 only.
    List<MetadataUpdate> updates =
        ImmutableList.of(new MetadataUpdate.RemoveSnapshot(s1.snapshotId()));
    UpdateTableRequest request =
        new UpdateTableRequest(UpdateRequirements.forUpdateTable(beforeExpiry, updates), updates);

    MvcResult result = postCommit(tableId, request);
    Assertions.assertEquals(200, result.getResponse().getStatus());

    TableMetadata current = currentMetadata(tableId);
    Assertions.assertEquals(ImmutableSet.of(s2.snapshotId()), snapshotIds(current));
    Assertions.assertEquals(s2.snapshotId(), current.ref(MAIN).snapshotId());
  }

  // ---------------------------------------------------------------------------------------------
  // (h) Preserved-key mutation rejected
  // ---------------------------------------------------------------------------------------------

  @Test
  public void testPreservedKeyMutationRejected() throws Exception {
    String tableId = "restpreserved";
    createTestTable(tableId);

    String htsLocationBefore = htsRow(tableId).getTableLocation();
    long metadataFilesBefore = countMetadataFiles(tableId);
    TableMetadata base = currentMetadata(tableId);

    List<List<MetadataUpdate>> rejectedUpdateLists =
        ImmutableList.of(
            ImmutableList.of(
                new MetadataUpdate.SetProperties(
                    ImmutableMap.of("openhouse.tableLocation", "/malicious/location"))),
            ImmutableList.of(new MetadataUpdate.SetProperties(ImmutableMap.of("policies", "{}"))),
            ImmutableList.of(
                new MetadataUpdate.RemoveProperties(ImmutableSet.of("openhouse.tableUUID"))));

    for (List<MetadataUpdate> updates : rejectedUpdateLists) {
      UpdateTableRequest request =
          new UpdateTableRequest(UpdateRequirements.forUpdateTable(base, updates), updates);
      MvcResult result = postCommit(tableId, request);
      Assertions.assertEquals(
          400, result.getResponse().getStatus(), "Expected 400 for updates: " + updates);
      Assertions.assertEquals(
          "BadRequestException",
          JsonPath.read(result.getResponse().getContentAsString(), "$.error.type"));
    }

    // Nothing was written by any of the rejected requests.
    Assertions.assertEquals(metadataFilesBefore, countMetadataFiles(tableId));
    Assertions.assertEquals(htsLocationBefore, htsRow(tableId).getTableLocation());
  }

  // ---------------------------------------------------------------------------------------------
  // Additional policy / protocol edges
  // ---------------------------------------------------------------------------------------------

  @Test
  public void testLockedTableCommitRejected() throws Exception {
    String tableId = "restlocked";
    createTestTable(tableId);
    PreparedCommit commit = prepareAppendCommit(tableId, "rest_locked.orc");

    // Lock the table by updating the policies property through the internal catalog (the same
    // metadata state the policies endpoint produces).
    Table table = catalog.loadTable(TableIdentifier.of(DATABASE_ID, tableId));
    table.updateProperties().set("policies", "{\"lockState\":{\"locked\":true}}").commit();

    MvcResult result = postCommit(tableId, commit.request);
    Assertions.assertEquals(400, result.getResponse().getStatus());
    Assertions.assertTrue(
        result.getResponse().getContentAsString().contains("locked"),
        "Expected lock rejection, got: " + result.getResponse().getContentAsString());
  }

  @Test
  public void testAssignUuidRejected() throws Exception {
    String tableId = "restassignuuid";
    createTestTable(tableId);
    TableMetadata base = currentMetadata(tableId);

    List<MetadataUpdate> updates =
        ImmutableList.of(
            new MetadataUpdate.AssignUUID(java.util.UUID.randomUUID().toString()),
            new MetadataUpdate.SetProperties(ImmutableMap.of("user.k", "v")));
    UpdateTableRequest request =
        new UpdateTableRequest(
            ImmutableList.of(new UpdateRequirement.AssertTableUUID(base.uuid())), updates);

    MvcResult result = postCommit(tableId, request);
    Assertions.assertEquals(400, result.getResponse().getStatus());
    Assertions.assertEquals(
        "BadRequestException",
        JsonPath.read(result.getResponse().getContentAsString(), "$.error.type"));
  }

  @Test
  public void testUnknownTableIs404() throws Exception {
    List<MetadataUpdate> updates =
        ImmutableList.of(new MetadataUpdate.SetProperties(ImmutableMap.of("user.k", "v")));
    UpdateTableRequest request = new UpdateTableRequest(ImmutableList.of(), updates);

    MvcResult result = postCommit("nosuchtable", request);
    Assertions.assertEquals(404, result.getResponse().getStatus());
    Assertions.assertEquals(
        "NoSuchTableException",
        JsonPath.read(result.getResponse().getContentAsString(), "$.error.type"));
  }

  @Test
  public void testMultiLevelNamespaceRejected() throws Exception {
    UpdateTableRequest request =
        new UpdateTableRequest(
            ImmutableList.of(),
            ImmutableList.of(new MetadataUpdate.SetProperties(ImmutableMap.of("user.k", "v"))));
    MvcResult result =
        mvc.perform(
                MockMvcRequestBuilders.post(
                        String.format("/v1/rest/namespaces/%s/tables/%s/commit", "a\u001Fb", "t1"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(icebergRestSerde.toJson(request))
                    .accept(MediaType.APPLICATION_JSON))
            .andReturn();
    Assertions.assertEquals(400, result.getResponse().getStatus());
  }

  @Test
  public void testMalformedRequestBodyIs400() throws Exception {
    String tableId = "restmalformed";
    createTestTable(tableId);
    MvcResult result =
        mvc.perform(
                MockMvcRequestBuilders.post(
                        String.format(
                            "/v1/rest/namespaces/%s/tables/%s/commit", DATABASE_ID, tableId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"updates\": \"not-an-array\"}")
                    .accept(MediaType.APPLICATION_JSON))
            .andReturn();
    Assertions.assertEquals(400, result.getResponse().getStatus());
  }

  @Test
  public void testNoopUpdateDoesNotAdvanceTable() throws Exception {
    String tableId = "restnoop";
    createTestTable(tableId);
    TableMetadata base = currentMetadata(tableId);
    String htsLocationBefore = htsRow(tableId).getTableLocation();
    long metadataFilesBefore = countMetadataFiles(tableId);

    // Empty update list with a holding requirement: nothing changes, nothing is written.
    UpdateTableRequest request =
        new UpdateTableRequest(
            ImmutableList.of(new UpdateRequirement.AssertTableUUID(base.uuid())),
            ImmutableList.of());

    MvcResult result = postCommit(tableId, request);
    Assertions.assertEquals(200, result.getResponse().getStatus());
    Assertions.assertEquals(htsLocationBefore, htsRow(tableId).getTableLocation());
    Assertions.assertEquals(metadataFilesBefore, countMetadataFiles(tableId));
  }

  // ---------------------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------------------

  private void createTestTable(String tableId) throws Exception {
    GetTableResponseBody responseBody =
        GET_TABLE_RESPONSE_BODY
            .toBuilder()
            .tableId(tableId)
            .tableUri(CLUSTER_NAME + "." + DATABASE_ID + "." + tableId)
            .build();
    RequestAndValidateHelper.createTableAndValidateResponse(responseBody, mvc, storageManager);
  }

  /**
   * Derives an append commit the way a stock REST client would: apply an append against the
   * currently observed state, express it as typed {@code (requirements, updates)} via {@code
   * TableMetadata.Builder} change tracking and {@link UpdateRequirements#forUpdateTable}.
   */
  private PreparedCommit prepareAppendCommit(String tableId, String dataFileName) throws Exception {
    Table table = catalog.loadTable(TableIdentifier.of(DATABASE_ID, tableId));
    TableMetadata base = ((BaseTable) table).operations().current();

    String dataFilePath =
        storageManager.getDefaultStorage().getClient().getRootPrefix()
            + "/"
            + tableId
            + "_"
            + dataFileName;
    DataFile dataFile = createDummyDataFile(dataFilePath, table.spec());
    Snapshot snapshot = table.newAppend().appendFile(dataFile).apply();

    TableMetadata derived = TableMetadata.buildFrom(base).setBranchSnapshot(snapshot, MAIN).build();
    List<MetadataUpdate> updates = derived.changes();
    List<UpdateRequirement> requirements = UpdateRequirements.forUpdateTable(base, updates);
    return new PreparedCommit(base, snapshot, new UpdateTableRequest(requirements, updates));
  }

  /** Prepares and submits an append commit, asserting success; returns the appended snapshot. */
  private Snapshot commitAppend(String tableId, String dataFileName) throws Exception {
    PreparedCommit commit = prepareAppendCommit(tableId, dataFileName);
    MvcResult result = postCommit(tableId, commit.request);
    Assertions.assertEquals(
        200,
        result.getResponse().getStatus(),
        "Append setup commit failed: " + result.getResponse().getContentAsString());
    return commit.snapshot;
  }

  private MvcResult postCommit(String tableId, UpdateTableRequest request) throws Exception {
    return mvc.perform(
            MockMvcRequestBuilders.post(
                    String.format("/v1/rest/namespaces/%s/tables/%s/commit", DATABASE_ID, tableId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(icebergRestSerde.toJson(request))
                .accept(MediaType.APPLICATION_JSON))
        .andReturn();
  }

  /**
   * Unwraps the Mockito spy from the Spring AOP proxy layers that {@code @SpyBean} leaves on
   * interface-based beans (e.g. JPA repositories), so stubbing and verification target the actual
   * mock. {@code AopTestUtils.getUltimateTargetObject} cannot be used directly: the spy mocks the
   * repository proxy's {@code Advised} interface too, so a full unwrap tunnels PAST the spy down to
   * the raw {@code SimpleJpaRepository}.
   */
  @SuppressWarnings("unchecked")
  private static <T> T spyOf(T bean) {
    Object candidate = bean;
    try {
      while (!Mockito.mockingDetails(candidate).isMock()
          && candidate instanceof org.springframework.aop.framework.Advised) {
        candidate =
            ((org.springframework.aop.framework.Advised) candidate).getTargetSource().getTarget();
      }
    } catch (Exception e) {
      throw new IllegalStateException("Could not unwrap spy from bean: " + bean, e);
    }
    return (T) candidate;
  }

  private HouseTable htsRow(String tableId) {
    return houseTableRepository
        .findById(HouseTablePrimaryKey.builder().databaseId(DATABASE_ID).tableId(tableId).build())
        .orElseThrow(() -> new IllegalStateException("No HTS row for " + tableId));
  }

  /** Loads the table through a fresh {@link org.apache.iceberg.TableOperations} instance. */
  private TableMetadata currentMetadata(String tableId) {
    return ((BaseTable) catalog.loadTable(TableIdentifier.of(DATABASE_ID, tableId)))
        .operations()
        .current();
  }

  private Set<Long> snapshotIds(TableMetadata metadata) {
    return metadata.snapshots().stream().map(Snapshot::snapshotId).collect(Collectors.toSet());
  }

  /**
   * Counts metadata.json files in the table root — OpenHouse writes them beside /data,/metadata.
   */
  private long countMetadataFiles(String tableId) {
    TableMetadata current = currentMetadata(tableId);
    String location = current.location().replaceFirst("^file:", "");
    File[] files = new File(location).listFiles();
    return Arrays.stream(Objects.requireNonNull(files))
        .filter(f -> f.getName().endsWith(".metadata.json"))
        .count();
  }
}
