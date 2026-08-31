package com.linkedin.openhouse.housetables.e2e.database;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.google.common.collect.Lists;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.housetables.api.spec.model.Database;
import com.linkedin.openhouse.housetables.api.spec.model.DatabaseBackfillStatus;
import com.linkedin.openhouse.housetables.model.DatabaseRow;
import com.linkedin.openhouse.housetables.model.UserTableRow;
import com.linkedin.openhouse.housetables.repository.impl.jdbc.DatabaseBackfillHtsJdbcRepository;
import com.linkedin.openhouse.housetables.repository.impl.jdbc.DatabaseHtsJdbcRepository;
import com.linkedin.openhouse.housetables.repository.impl.jdbc.UserTableHtsJdbcRepository;
import com.linkedin.openhouse.housetables.services.DatabaseBackfillService;
import com.linkedin.openhouse.housetables.services.DatabasesService;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * End-to-end coverage for the backfill that gives every database in the table store a row in {@code
 * database_row}.
 *
 * <p>The store under test is the real H2-backed one, because what is being asserted is what the
 * rows look like afterwards, not which calls were made.
 */
@SpringBootTest
@ContextConfiguration(initializers = PropertyOverrideContextInitializer.class)
@AutoConfigureMockMvc
public class DatabaseBackfillTest {

  private static final String BACKFILL_ENDPOINT = "/hts/databases/backfill";
  private static final String BACKFILL_VERIFY_ENDPOINT = "/hts/databases/backfill/verify";

  @Autowired DatabaseBackfillService backfillService;

  @Autowired UserTableHtsJdbcRepository userTableRepository;

  @Autowired DatabaseHtsJdbcRepository databaseRepository;

  @Autowired DatabaseBackfillHtsJdbcRepository backfillStateRepository;

  /**
   * Spied, not mocked: the real registration runs, and the spy is only there to interrupt one run
   * partway and to prove a resumed run does not touch what the first run already did.
   */
  @SpyBean DatabasesService databasesService;

  @Autowired MockMvc mvc;

  @AfterEach
  public void tearDown() {
    Mockito.reset(databasesService);
    userTableRepository.deleteAll();
    databaseRepository.deleteAll();
    backfillStateRepository.deleteAll();
  }

  @Test
  public void backfillRegistersEveryDatabaseInTheTableStoreExactlyOnce() {
    givenTable("alpha", "t1");
    givenTable("alpha", "t2");
    givenTable("beta", "t1");
    givenTable("gamma", "t1");

    DatabaseBackfillStatus status = backfillService.backfill(2);

    Assertions.assertEquals(
        Lists.newArrayList("alpha", "beta", "gamma"),
        storedDatabaseIds(),
        "every database the table store knows about, and nothing else, has a row");
    Assertions.assertEquals(
        3, status.getDatabasesScanned(), "one read per database, not per table");
    Assertions.assertEquals(3, status.getDatabasesRegistered());
    Assertions.assertEquals(0, status.getDatabasesAlreadyRegistered());
  }

  @Test
  public void backfillLeavesAnAlreadyRegisteredDatabaseUntouched() {
    givenTable("alpha", "t1");
    givenTable("beta", "t1");
    databasesService.putDatabase(
        Database.builder()
            .databaseId("beta")
            .properties(Collections.singletonMap("owner", "someone"))
            .build());
    Long versionBefore = databaseRepository.findById("beta").get().getVersion();

    DatabaseBackfillStatus status = backfillService.backfill(10);

    Assertions.assertEquals(1, status.getDatabasesRegistered());
    Assertions.assertEquals(1, status.getDatabasesAlreadyRegistered());
    DatabaseRow beta = databaseRepository.findById("beta").get();
    Assertions.assertEquals(
        "someone", beta.getProperties().get("owner"), "properties survive a backfill");
    Assertions.assertEquals(versionBefore, beta.getVersion(), "an existing row is not rewritten");
  }

  @Test
  public void rerunningACompleteBackfillWritesNothing() {
    givenTable("alpha", "t1");
    givenTable("beta", "t1");
    backfillService.backfill(10);
    List<Long> versionsBefore = storedVersions();
    Mockito.clearInvocations(databasesService);

    DatabaseBackfillStatus rerun = backfillService.backfill(10);

    Assertions.assertEquals(0, rerun.getDatabasesRegistered(), "a re-run registers nothing");
    Assertions.assertEquals(2, rerun.getDatabasesAlreadyRegistered());
    Assertions.assertEquals(
        Lists.newArrayList("alpha", "beta"), storedDatabaseIds(), "and creates no duplicates");
    Assertions.assertEquals(versionsBefore, storedVersions(), "and rewrites no row");
    Mockito.verify(databasesService, Mockito.never()).putDatabase(Mockito.any());
  }

  @Test
  public void anInterruptedRunResumesRatherThanRestarting() {
    givenTable("alpha", "t1");
    givenTable("beta", "t1");
    givenTable("gamma", "t1");
    givenTable("delta", "t1");
    // Sorted: alpha, beta, delta, gamma. The store goes down on the third.
    Mockito.doThrow(new RuntimeException("store unavailable"))
        .when(databasesService)
        .putDatabase(Mockito.argThat(database -> "delta".equals(database.getDatabaseId())));

    Assertions.assertThrows(RuntimeException.class, () -> backfillService.backfill(1));
    Assertions.assertEquals(
        Lists.newArrayList("alpha", "beta"),
        storedDatabaseIds(),
        "the run stopped where it failed");
    Assertions.assertEquals(
        "beta", backfillService.status().getWatermark(), "and left the resume point behind");

    Mockito.reset(databasesService);
    DatabaseBackfillStatus resumed = backfillService.backfill(1);

    Assertions.assertEquals("beta", resumed.getResumedFrom());
    Assertions.assertEquals(
        2, resumed.getDatabasesScanned(), "the resumed run reads only what is left");
    Assertions.assertEquals(2, resumed.getDatabasesRegistered());
    Mockito.verify(databasesService, Mockito.never())
        .putDatabase(
            Mockito.argThat(
                database ->
                    "alpha".equals(database.getDatabaseId())
                        || "beta".equals(database.getDatabaseId())));
    Assertions.assertEquals(
        Lists.newArrayList("alpha", "beta", "delta", "gamma"), storedDatabaseIds());
    Assertions.assertNull(
        backfillService.status().getWatermark(), "a run that reached the end clears the watermark");
  }

  @Test
  public void aScanThatRanIsNotAScanThatVerified() {
    givenTable("alpha", "t1");

    DatabaseBackfillStatus afterScan = backfillService.backfill(10);

    Assertions.assertNotNull(afterScan.getScanCompleteTimeMs(), "the scan ran");
    Assertions.assertNull(
        afterScan.getVerifiedCompleteTimeMs(),
        "but running is not verifying: only a pass that read the store back may claim that");
  }

  @Test
  public void verificationRefusesToMarkCompleteWhileADatabaseHasNoRow() {
    givenTable("alpha", "t1");
    givenTable("beta", "t1");
    databasesService.putDatabase(Database.builder().databaseId("alpha").build());

    DatabaseBackfillStatus incomplete = backfillService.verify(10);

    Assertions.assertNull(
        incomplete.getVerifiedCompleteTimeMs(), "one missing row is not completeness");
    Assertions.assertNotNull(incomplete.getLastVerifyTimeMs(), "the pass still ran");
    Assertions.assertEquals(1L, incomplete.getMissingCount());
    Assertions.assertEquals(Lists.newArrayList("beta"), incomplete.getMissingSample());

    databasesService.putDatabase(Database.builder().databaseId("beta").build());
    DatabaseBackfillStatus complete = backfillService.verify(10);

    Assertions.assertNotNull(
        complete.getVerifiedCompleteTimeMs(), "nothing missing is what completeness means");
    Assertions.assertEquals(0L, complete.getMissingCount());
    Assertions.assertEquals(Collections.emptyList(), complete.getMissingSample());
    Assertions.assertNotNull(
        backfillService.status().getVerifiedCompleteTimeMs(), "and it is durable");
  }

  /**
   * The gap that matters is the one a verification cannot see. A pass that read only its first page
   * would report the store complete while a database on the second has no row, and the marker it
   * left behind would be the exact failure the marker exists to prevent.
   */
  @Test
  public void verificationSeesAGapPastTheFirstPage() {
    givenTable("alpha", "t1");
    givenTable("beta", "t1");
    givenTable("gamma", "t1");
    databasesService.putDatabase(Database.builder().databaseId("alpha").build());
    databasesService.putDatabase(Database.builder().databaseId("beta").build());

    DatabaseBackfillStatus status = backfillService.verify(1);

    Assertions.assertEquals(3, status.getDatabasesScanned(), "every page was read");
    Assertions.assertEquals(1L, status.getMissingCount());
    Assertions.assertEquals(Lists.newArrayList("gamma"), status.getMissingSample());
    Assertions.assertNull(status.getVerifiedCompleteTimeMs());
  }

  @Test
  public void verificationWithdrawsACompletenessItCanNoLongerSee() {
    givenTable("alpha", "t1");
    backfillService.backfill(10);
    Assertions.assertNotNull(backfillService.verify(10).getVerifiedCompleteTimeMs());

    // A database registration failed somewhere, which is exactly what the drift counter reports.
    givenTable("beta", "t1");

    Assertions.assertNull(
        backfillService.verify(10).getVerifiedCompleteTimeMs(),
        "a marker that outlives the fact it asserts is worse than no marker");
    Assertions.assertNull(backfillService.status().getVerifiedCompleteTimeMs());
  }

  @Test
  public void aDatabaseRegisteredUnderAnotherSpellingIsNotRegisteredTwice() {
    givenTable("Alpha", "t1");
    databasesService.putDatabase(Database.builder().databaseId("alpha").build());

    DatabaseBackfillStatus status = backfillService.backfill(10);

    Assertions.assertEquals(0, status.getDatabasesRegistered());
    Assertions.assertEquals(1, status.getDatabasesAlreadyRegistered());
    Assertions.assertEquals(Lists.newArrayList("alpha"), storedDatabaseIds());
    Assertions.assertNotNull(
        backfillService.verify(10).getVerifiedCompleteTimeMs(),
        "the store lookup folds case, so verification has to fold it the same way");
  }

  @Test
  public void theTriggerIsARouteAndNothingRunsWithoutBeingAsked() throws Exception {
    givenTable("alpha", "t1");

    mvc.perform(MockMvcRequestBuilders.get(BACKFILL_ENDPOINT))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.scanCompleteTimeMs").doesNotExist())
        .andExpect(jsonPath("$.verifiedCompleteTimeMs").doesNotExist());
    Assertions.assertEquals(
        Collections.emptyList(), storedDatabaseIds(), "reading the status runs nothing");
    Assertions.assertEquals(
        0, backfillStateRepository.count(), "and leaves behind no state saying it did");

    mvc.perform(MockMvcRequestBuilders.post(BACKFILL_ENDPOINT).param("pageSize", "100"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.databasesRegistered").value(1))
        .andExpect(jsonPath("$.scanCompleteTimeMs").isNumber())
        .andExpect(jsonPath("$.verifiedCompleteTimeMs").doesNotExist());
    Assertions.assertEquals(Lists.newArrayList("alpha"), storedDatabaseIds());

    mvc.perform(MockMvcRequestBuilders.post(BACKFILL_VERIFY_ENDPOINT))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.missingCount").value(0))
        .andExpect(jsonPath("$.verifiedCompleteTimeMs").isNumber());
  }

  @Test
  public void anUnusablePageSizeIsARejectedRequestRatherThanAnEmptyRun() throws Exception {
    givenTable("alpha", "t1");

    mvc.perform(MockMvcRequestBuilders.post(BACKFILL_ENDPOINT).param("pageSize", "0"))
        .andExpect(status().isBadRequest());

    Assertions.assertEquals(Collections.emptyList(), storedDatabaseIds());
  }

  private void givenTable(String databaseId, String tableId) {
    userTableRepository.save(
        UserTableRow.builder()
            .databaseId(databaseId)
            .tableId(tableId)
            .metadataLocation(
                String.format("/openhouse/%s/%s/v0_metadata.json", databaseId, tableId))
            .storageType("hdfs")
            .creationTime(123L)
            .build());
  }

  private List<String> storedDatabaseIds() {
    return StreamSupport.stream(databaseRepository.findAll().spliterator(), false)
        .map(DatabaseRow::getDatabaseId)
        .sorted()
        .collect(Collectors.toList());
  }

  private List<Long> storedVersions() {
    return StreamSupport.stream(databaseRepository.findAll().spliterator(), false)
        .sorted((a, b) -> a.getDatabaseId().compareTo(b.getDatabaseId()))
        .map(DatabaseRow::getVersion)
        .collect(Collectors.toList());
  }
}
