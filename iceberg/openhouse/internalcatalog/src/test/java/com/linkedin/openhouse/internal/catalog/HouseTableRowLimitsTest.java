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

  /** By default nothing is checked: the store decides, as before the check existed. */
  @Test
  void byDefaultNoLengthIsChecked() {
    assertEquals(0, HouseTableRowLimits.DEFAULT.getMetadataLocationMaxLength());
    assertEquals(0, HouseTableRowLimits.DEFAULT.getTableIdMaxLength());
    assertDoesNotThrow(
        () -> HouseTableRowLimits.DEFAULT.requireMetadataLocationFits("Table db.t", ofLength(600)));
    assertDoesNotThrow(
        () -> HouseTableRowLimits.DEFAULT.requireTableNameFits("Table db.t", ofLength(600)));
  }

  @Test
  void theMetadataLocationFitsAt255AndIsRejectedAt256WithBothLengthsNamed() {
    HouseTableRowLimits production = new HouseTableRowLimits(255, 255);
    assertEquals(255, production.getMetadataLocationMaxLength());
    assertDoesNotThrow(() -> production.requireMetadataLocationFits("Table db.t", ofLength(255)));
    CommitRejectedException rejected =
        assertThrows(
            CommitRejectedException.class,
            () -> production.requireMetadataLocationFits("Table db.t", ofLength(256)));
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
  void aLimitThatIsNotPositiveMeansNoCheck() {
    for (int none : new int[] {0, -1}) {
      HouseTableRowLimits unchecked = new HouseTableRowLimits(none, none);
      assertEquals(0, unchecked.getMetadataLocationMaxLength());
      assertEquals(0, unchecked.getTableIdMaxLength());
      assertDoesNotThrow(() -> unchecked.requireMetadataLocationFits("Table db.t", ofLength(600)));
      assertDoesNotThrow(() -> unchecked.requireTableNameFits("Table db.t", ofLength(600)));
    }
  }

  @Test
  void theNameFitsAt255AndIsRejectedAt256WithBothLengthsNamed() {
    HouseTableRowLimits production = new HouseTableRowLimits(255, 255);
    assertDoesNotThrow(() -> production.requireTableNameFits("Table db.t", ofLength(255)));
    CommitRejectedException rejected =
        assertThrows(
            CommitRejectedException.class,
            () -> production.requireTableNameFits("Table db.t", ofLength(256)));
    assertEquals(
        "Table db.t cannot be written under a name of 256 characters: House Tables holds at most"
            + " 255 (user_table_row.table_id).",
        rejected.getMessage());
  }

  /** A store whose name column is narrower states it, and the check uses it. */
  @Test
  void aConfiguredNameWidthIsTheLimitAndNotPositiveMeansNoCheck() {
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

    assertEquals(0, new HouseTableRowLimits(512).getTableIdMaxLength());
    assertEquals(0, new HouseTableRowLimits(255, 0).getTableIdMaxLength());
    assertEquals(0, new HouseTableRowLimits(255, -1).getTableIdMaxLength());
  }
}
