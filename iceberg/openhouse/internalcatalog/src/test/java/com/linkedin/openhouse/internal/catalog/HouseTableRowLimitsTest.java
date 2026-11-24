package com.linkedin.openhouse.internal.catalog;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.linkedin.openhouse.common.exception.CommitRejectedException;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class HouseTableRowLimitsTest {

  private static String ofLength(int length) {
    return String.join("", Collections.nCopies(length, "x"));
  }

  @Test
  void theMetadataLocationFitsAt255AndIsRejectedAt256WithBothLengthsNamed() {
    assertEquals(255, HouseTableRowLimits.DEFAULT.getMetadataLocationMaxLength());
    assertDoesNotThrow(
        () -> HouseTableRowLimits.DEFAULT.requireMetadataLocationFits("Table db.t", ofLength(255)));
    CommitRejectedException rejected =
        assertThrows(
            CommitRejectedException.class,
            () ->
                HouseTableRowLimits.DEFAULT.requireMetadataLocationFits(
                    "Table db.t", ofLength(256)));
    assertEquals(
        "Table db.t cannot be written: its metadata location would be 256 characters long and"
            + " House Tables holds at most 255 (user_table_row.metadata_location). The location is"
            + " made of the storage root, the database name, the name and UUID it was created"
            + " with, and the metadata file's name.",
        rejected.getMessage());
  }

  /** A store created from the bootstrap schema holds 512, and its deployment says so. */
  @Test
  void aConfiguredLimitIsTheOneEnforcedAndNamed() {
    HouseTableRowLimits wider = new HouseTableRowLimits(512);
    assertDoesNotThrow(() -> wider.requireMetadataLocationFits("View db.v", ofLength(256)));
    assertDoesNotThrow(() -> wider.requireMetadataLocationFits("View db.v", ofLength(512)));
    CommitRejectedException rejected =
        assertThrows(
            CommitRejectedException.class,
            () -> wider.requireMetadataLocationFits("View db.v", ofLength(513)));
    assertEquals(
        "View db.v cannot be written: its metadata location would be 513 characters long and"
            + " House Tables holds at most 512 (user_table_row.metadata_location). The location is"
            + " made of the storage root, the database name, the name and UUID it was created"
            + " with, and the metadata file's name.",
        rejected.getMessage());
  }

  @Test
  void aLimitThatIsNotPositiveMeansTheDefault() {
    assertEquals(255, new HouseTableRowLimits(0).getMetadataLocationMaxLength());
    assertEquals(255, new HouseTableRowLimits(-1).getMetadataLocationMaxLength());
  }

  @Test
  void theNameFitsAt255AndIsRejectedAt256WithBothLengthsNamed() {
    assertDoesNotThrow(() -> HouseTableRowLimits.requireTableIdFits("Table db.t", ofLength(255)));
    CommitRejectedException rejected =
        assertThrows(
            CommitRejectedException.class,
            () -> HouseTableRowLimits.requireTableIdFits("Table db.t", ofLength(256)));
    assertEquals(
        "Table db.t cannot be written under a name of 256 characters: House Tables holds at most"
            + " 255 (user_table_row.table_id).",
        rejected.getMessage());
  }

  /** A store whose name column is narrower states it, and the check uses it (work item V19). */
  @Test
  void aConfiguredNameWidthIsTheLimitAndNotPositiveMeansTheDefault() {
    HouseTableRowLimits narrower = new HouseTableRowLimits(255, 128);
    assertEquals(128, narrower.getTableIdMaxLength());
    assertEquals(255, narrower.getMetadataLocationMaxLength());
    assertDoesNotThrow(() -> narrower.requireTableNameFits("Table db.t", ofLength(128)));
    CommitRejectedException rejected =
        assertThrows(
            CommitRejectedException.class,
            () -> narrower.requireTableNameFits("Table db.t", ofLength(129)));
    assertEquals(
        "Table db.t cannot be written under a name of 129 characters: House Tables holds at most"
            + " 128 (user_table_row.table_id).",
        rejected.getMessage());

    assertEquals(255, HouseTableRowLimits.DEFAULT.getTableIdMaxLength());
    assertEquals(255, new HouseTableRowLimits(512).getTableIdMaxLength());
    assertEquals(255, new HouseTableRowLimits(255, 0).getTableIdMaxLength());
    assertEquals(255, new HouseTableRowLimits(255, -1).getTableIdMaxLength());
  }
}
