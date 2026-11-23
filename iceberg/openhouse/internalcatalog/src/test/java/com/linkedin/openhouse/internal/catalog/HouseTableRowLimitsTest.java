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
    assertDoesNotThrow(
        () -> HouseTableRowLimits.requireMetadataLocationFits("Table db.t", ofLength(255)));
    CommitRejectedException rejected =
        assertThrows(
            CommitRejectedException.class,
            () -> HouseTableRowLimits.requireMetadataLocationFits("Table db.t", ofLength(256)));
    assertEquals(
        "Table db.t cannot be written: its metadata location would be 256 characters long and"
            + " House Tables holds at most 255 (user_table_row.metadata_location). The location is"
            + " made of the storage root, the database name, the name and UUID it was created"
            + " with, and the metadata file's name.",
        rejected.getMessage());
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
}
