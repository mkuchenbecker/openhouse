package com.linkedin.openhouse.internal.catalog;

import com.linkedin.openhouse.common.exception.CommitRejectedException;

/**
 * What a House Tables row can hold of the values a write sends it, and the refusal of a write whose
 * row would not fit.
 *
 * <p>A table or view write puts its metadata file on storage first and its House Tables row second,
 * so a row the store cannot take would fail after the file exists: the caller would be told the
 * commit's state is unknown, and the file would stay. Every write is therefore checked here before
 * anything is written.
 *
 * <p>The widths are the columns of {@code user_table_row} as the store's DDL declares them ({@code
 * services/housetables/ddl/0000__baseline.sql}); they are stated here and nowhere else in this
 * service. Only the columns a request can overflow are listed: the database name is at most 128
 * characters in every request that writes it, and the storage type, entity type, version and
 * creation time are the server's.
 */
public final class HouseTableRowLimits {

  /** {@code user_table_row.metadata_location VARCHAR(255)}: the current metadata file's path. */
  public static final int METADATA_LOCATION_MAX_LENGTH = 255;

  /** {@code user_table_row.table_id VARCHAR(255)}: the table's or view's name. */
  public static final int TABLE_ID_MAX_LENGTH = 255;

  private HouseTableRowLimits() {}

  /**
   * @param entity what is written, as the message names it, for example {@code Table db.t}
   * @param metadataLocation the location the write would store in the row
   * @throws CommitRejectedException if the row cannot hold {@code metadataLocation}
   */
  public static void requireMetadataLocationFits(String entity, String metadataLocation) {
    if (metadataLocation.length() > METADATA_LOCATION_MAX_LENGTH) {
      throw new CommitRejectedException(
          String.format(
              "%s cannot be written: its metadata location would be %d characters long and House"
                  + " Tables holds at most %d (user_table_row.metadata_location). The location is"
                  + " made of the storage root, the database name, the name and UUID it was"
                  + " created with, and the metadata file's name.",
              entity, metadataLocation.length(), METADATA_LOCATION_MAX_LENGTH));
    }
  }

  /**
   * @param entity what is written, as the message names it, for example {@code Table db.t}
   * @param tableId the name the write would store in the row
   * @throws CommitRejectedException if the row cannot hold {@code tableId}
   */
  public static void requireTableIdFits(String entity, String tableId) {
    if (tableId.length() > TABLE_ID_MAX_LENGTH) {
      throw new CommitRejectedException(
          String.format(
              "%s cannot be written under a name of %d characters: House Tables holds at most %d"
                  + " (user_table_row.table_id).",
              entity, tableId.length(), TABLE_ID_MAX_LENGTH));
    }
  }
}
