package com.linkedin.openhouse.internal.catalog.commit;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.SnapshotRef;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.UpdateRequirement;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.exceptions.ValidationException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class UpdateRequirementValidatorTest {

  /**
   * Every {@link UpdateRequirement} implementation Iceberg ships on our compile classpath. If an
   * Iceberg upgrade adds one, this test fails and forces a human to decide how the new precondition
   * should behave here, rather than letting it slip through unexamined.
   */
  private static final Set<String> KNOWN_REQUIREMENT_TYPES =
      Stream.of(
              "AssertCurrentSchemaID",
              "AssertDefaultSortOrderID",
              "AssertDefaultSpecID",
              "AssertLastAssignedFieldId",
              "AssertLastAssignedPartitionId",
              "AssertRefSnapshotID",
              "AssertTableDoesNotExist",
              "AssertTableUUID",
              "AssertViewUUID")
          .collect(Collectors.toSet());

  private TableMetadata base;

  @BeforeEach
  void setUp() {
    base = CommitTestFixtures.baseMetadata();
  }

  @Test
  public void testKnownRequirementTypesAreExhaustive() {
    Set<String> onClasspath =
        Arrays.stream(UpdateRequirement.class.getDeclaredClasses())
            .map(Class::getSimpleName)
            .collect(Collectors.toSet());
    Assertions.assertEquals(
        KNOWN_REQUIREMENT_TYPES,
        onClasspath,
        "Iceberg's UpdateRequirement types changed; review UpdateRequirementValidator");
  }

  @Test
  public void testEmptyRequirementsPass() {
    Assertions.assertDoesNotThrow(
        () -> UpdateRequirementValidator.validate(base, Collections.emptyList()));
    Assertions.assertDoesNotThrow(
        () -> UpdateRequirementValidator.validate(null, Collections.emptyList()));
  }

  @Test
  public void testNullRequirementListRejected() {
    Assertions.assertThrows(
        IllegalArgumentException.class, () -> UpdateRequirementValidator.validate(base, null));
  }

  @Test
  public void testNullRequirementElementRejected() {
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> UpdateRequirementValidator.validate(base, Collections.singletonList(null)));
  }

  @Test
  public void testAssertTableUuid() {
    assertPasses(new UpdateRequirement.AssertTableUUID(base.uuid()));
    CommitFailedException e =
        assertFails(new UpdateRequirement.AssertTableUUID("00000000-0000-0000-0000-000000000000"));
    Assertions.assertTrue(e.getMessage().contains("UUID does not match"), e.getMessage());
  }

  @Test
  public void testAssertTableDoesNotExist() {
    // Passes only when there is no current metadata.
    Assertions.assertDoesNotThrow(
        () ->
            UpdateRequirementValidator.validate(
                null, Collections.singletonList(new UpdateRequirement.AssertTableDoesNotExist())));
    CommitFailedException e = assertFails(new UpdateRequirement.AssertTableDoesNotExist());
    Assertions.assertTrue(e.getMessage().contains("table already exists"), e.getMessage());
  }

  @Test
  public void testAssertCurrentSchemaId() {
    assertPasses(new UpdateRequirement.AssertCurrentSchemaID(base.currentSchemaId()));
    assertFails(new UpdateRequirement.AssertCurrentSchemaID(base.currentSchemaId() + 1));
  }

  @Test
  public void testAssertDefaultSpecId() {
    assertPasses(new UpdateRequirement.AssertDefaultSpecID(base.defaultSpecId()));
    assertFails(new UpdateRequirement.AssertDefaultSpecID(base.defaultSpecId() + 1));
  }

  @Test
  public void testAssertDefaultSortOrderId() {
    assertPasses(new UpdateRequirement.AssertDefaultSortOrderID(base.defaultSortOrderId()));
    assertFails(new UpdateRequirement.AssertDefaultSortOrderID(base.defaultSortOrderId() + 1));
  }

  @Test
  public void testAssertLastAssignedFieldId() {
    assertPasses(new UpdateRequirement.AssertLastAssignedFieldId(base.lastColumnId()));
    assertFails(new UpdateRequirement.AssertLastAssignedFieldId(base.lastColumnId() + 1));
  }

  @Test
  public void testAssertLastAssignedPartitionId() {
    assertPasses(
        new UpdateRequirement.AssertLastAssignedPartitionId(base.lastAssignedPartitionId()));
    assertFails(
        new UpdateRequirement.AssertLastAssignedPartitionId(base.lastAssignedPartitionId() + 1));
  }

  @Test
  public void testAssertRefSnapshotIdOnMissingRef() {
    // Asserting that a ref is absent holds when it is absent...
    assertPasses(new UpdateRequirement.AssertRefSnapshotID(SnapshotRef.MAIN_BRANCH, null));
    // ...and expecting a specific snapshot on a missing ref is a precondition failure.
    CommitFailedException e =
        assertFails(new UpdateRequirement.AssertRefSnapshotID(SnapshotRef.MAIN_BRANCH, 42L));
    Assertions.assertTrue(e.getMessage().contains("is missing"), e.getMessage());
  }

  @Test
  public void testAssertRefSnapshotIdOnExistingRef() {
    Snapshot snapshot = CommitTestFixtures.snapshot(1L, 1L);
    TableMetadata withRef =
        TableMetadata.buildFrom(base).setBranchSnapshot(snapshot, SnapshotRef.MAIN_BRANCH).build();

    Assertions.assertDoesNotThrow(
        () ->
            UpdateRequirementValidator.validate(
                withRef,
                Collections.singletonList(
                    new UpdateRequirement.AssertRefSnapshotID(
                        SnapshotRef.MAIN_BRANCH, snapshot.snapshotId()))));

    CommitFailedException changed =
        Assertions.assertThrows(
            CommitFailedException.class,
            () ->
                UpdateRequirementValidator.validate(
                    withRef,
                    Collections.singletonList(
                        new UpdateRequirement.AssertRefSnapshotID(SnapshotRef.MAIN_BRANCH, 99L))));
    Assertions.assertTrue(changed.getMessage().contains("has changed"), changed.getMessage());

    CommitFailedException concurrent =
        Assertions.assertThrows(
            CommitFailedException.class,
            () ->
                UpdateRequirementValidator.validate(
                    withRef,
                    Collections.singletonList(
                        new UpdateRequirement.AssertRefSnapshotID(SnapshotRef.MAIN_BRANCH, null))));
    Assertions.assertTrue(
        concurrent.getMessage().contains("created concurrently"), concurrent.getMessage());
  }

  @Test
  public void testAssertViewUuidIsRejectedForTables() {
    for (TableMetadata metadata : Arrays.asList(base, null)) {
      ValidationException e =
          Assertions.assertThrows(
              ValidationException.class,
              () ->
                  UpdateRequirementValidator.validate(
                      metadata,
                      Collections.singletonList(
                          new UpdateRequirement.AssertViewUUID("some-uuid"))));
      Assertions.assertTrue(e.getMessage().contains("against a table"), e.getMessage());
    }
  }

  @Test
  public void testTableRequirementsAgainstMissingTableFailLoudly() {
    // Every requirement other than AssertTableDoesNotExist dereferences the current metadata.
    // A missing table must surface as a precondition failure, never as an NPE.
    for (UpdateRequirement requirement :
        Arrays.asList(
            new UpdateRequirement.AssertTableUUID("some-uuid"),
            new UpdateRequirement.AssertCurrentSchemaID(0),
            new UpdateRequirement.AssertDefaultSpecID(0),
            new UpdateRequirement.AssertDefaultSortOrderID(0),
            new UpdateRequirement.AssertLastAssignedFieldId(2),
            new UpdateRequirement.AssertLastAssignedPartitionId(1000),
            new UpdateRequirement.AssertRefSnapshotID(SnapshotRef.MAIN_BRANCH, 1L))) {
      CommitFailedException e =
          Assertions.assertThrows(
              CommitFailedException.class,
              () ->
                  UpdateRequirementValidator.validate(
                      null, Collections.singletonList(requirement)));
      Assertions.assertTrue(e.getMessage().contains("table does not exist"), e.getMessage());
    }
  }

  @Test
  public void testFirstFailingRequirementStopsValidation() {
    CommitFailedException e =
        Assertions.assertThrows(
            CommitFailedException.class,
            () ->
                UpdateRequirementValidator.validate(
                    base,
                    Arrays.asList(
                        new UpdateRequirement.AssertTableUUID(base.uuid()),
                        new UpdateRequirement.AssertCurrentSchemaID(base.currentSchemaId() + 1),
                        new UpdateRequirement.AssertDefaultSpecID(base.defaultSpecId() + 1))));
    Assertions.assertTrue(e.getMessage().contains("current schema changed"), e.getMessage());
  }

  private void assertPasses(UpdateRequirement requirement) {
    Assertions.assertDoesNotThrow(
        () -> UpdateRequirementValidator.validate(base, Collections.singletonList(requirement)));
  }

  private CommitFailedException assertFails(UpdateRequirement requirement) {
    return Assertions.assertThrows(
        CommitFailedException.class,
        () -> UpdateRequirementValidator.validate(base, Collections.singletonList(requirement)));
  }
}
