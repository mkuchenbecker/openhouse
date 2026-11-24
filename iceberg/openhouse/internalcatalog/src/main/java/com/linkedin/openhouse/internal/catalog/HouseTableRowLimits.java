package com.linkedin.openhouse.internal.catalog;

import com.linkedin.openhouse.cluster.configs.ClusterProperties;
import com.linkedin.openhouse.common.exception.CommitRejectedException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * What a House Tables row can hold of the values a write sends it, and the refusal of a write whose
 * row would not fit.
 *
 * <p>A table or view write puts its metadata file on storage first and its House Tables row second,
 * so a row the store cannot take would fail after the file exists: the caller would be told the
 * commit's state is unknown, and the file would stay. Every write is therefore checked here before
 * anything is written.
 *
 * <p>The widths are the columns of {@code user_table_row}, and they are stated here and nowhere
 * else in this service. Two files declare that table, and they disagree:
 *
 * <ul>
 *   <li>{@code services/housetables/ddl/0000__baseline.sql}, the record of the production store:
 *       {@code metadata_location VARCHAR(255)}, {@code table_id VARCHAR(255)}.
 *   <li>{@code services/housetables/src/main/resources/schema.sql}, which the House Tables service
 *       runs at start-up where nobody else created its tables: {@code metadata_location
 *       VARCHAR(512)}, {@code table_id VARCHAR(128)}.
 * </ul>
 *
 * <p>Both widths are therefore the deployment's to state ({@code
 * cluster.housetables.metadata-location-max-length} and {@code
 * cluster.housetables.table-id-max-length}, {@link ClusterProperties}); each defaults to the
 * production store's. Only the columns a request can overflow are limited here: the database name
 * is at most 128 characters in every request that writes it, and the storage type, entity type,
 * version and creation time are the server's.
 */
@Component
public class HouseTableRowLimits {

  /**
   * {@code user_table_row.metadata_location VARCHAR(255)} in the production DDL: the default width
   * of the current metadata file's path, and the default of {@code
   * cluster.housetables.metadata-location-max-length}.
   */
  public static final int DEFAULT_METADATA_LOCATION_MAX_LENGTH = 255;

  /**
   * {@code user_table_row.table_id VARCHAR(255)} in the production DDL: the default width of the
   * table's or view's name, and the default of {@code cluster.housetables.table-id-max-length}. A
   * create's name is at most 128 characters on every store, so only a rename can exceed it.
   */
  public static final int DEFAULT_TABLE_ID_MAX_LENGTH = 255;

  /** The limits of a store with the production DDL's widths. */
  public static final HouseTableRowLimits DEFAULT =
      new HouseTableRowLimits(DEFAULT_METADATA_LOCATION_MAX_LENGTH, DEFAULT_TABLE_ID_MAX_LENGTH);

  private final int metadataLocationMaxLength;

  private final int tableIdMaxLength;

  /**
   * The limits of the cluster's House Tables store, as its configuration states them; the default
   * widths in a context that holds no cluster configuration.
   */
  @Autowired
  public HouseTableRowLimits(ObjectProvider<ClusterProperties> clusterProperties) {
    this(clusterProperties.stream().findFirst().orElse(null));
  }

  /** The limits {@code clusterProperties} states; the defaults for {@code null}. */
  public HouseTableRowLimits(ClusterProperties clusterProperties) {
    this(
        clusterProperties == null
            ? DEFAULT_METADATA_LOCATION_MAX_LENGTH
            : clusterProperties.getClusterHouseTablesMetadataLocationMaxLength(),
        clusterProperties == null
            ? DEFAULT_TABLE_ID_MAX_LENGTH
            : clusterProperties.getClusterHouseTablesTableIdMaxLength());
  }

  /**
   * @param metadataLocationMaxLength the width of {@code user_table_row.metadata_location}; a value
   *     that is not positive means the default
   */
  public HouseTableRowLimits(int metadataLocationMaxLength) {
    this(metadataLocationMaxLength, DEFAULT_TABLE_ID_MAX_LENGTH);
  }

  /**
   * @param metadataLocationMaxLength the width of {@code user_table_row.metadata_location}
   * @param tableIdMaxLength the width of {@code user_table_row.table_id}
   *     <p>A value that is not positive means that column's default.
   */
  public HouseTableRowLimits(int metadataLocationMaxLength, int tableIdMaxLength) {
    this.metadataLocationMaxLength =
        metadataLocationMaxLength > 0
            ? metadataLocationMaxLength
            : DEFAULT_METADATA_LOCATION_MAX_LENGTH;
    this.tableIdMaxLength = tableIdMaxLength > 0 ? tableIdMaxLength : DEFAULT_TABLE_ID_MAX_LENGTH;
  }

  public int getMetadataLocationMaxLength() {
    return metadataLocationMaxLength;
  }

  public int getTableIdMaxLength() {
    return tableIdMaxLength;
  }

  /**
   * @param entity what is written, as the message names it, for example {@code Table db.t}
   * @param metadataLocation the location the write would store in the row
   * @throws CommitRejectedException if the row cannot hold {@code metadataLocation}
   */
  public void requireMetadataLocationFits(String entity, String metadataLocation) {
    if (metadataLocation.length() > metadataLocationMaxLength) {
      throw new CommitRejectedException(
          String.format(
              "%s cannot be written: its metadata location would be %d characters long and House"
                  + " Tables holds at most %d (user_table_row.metadata_location). The location is"
                  + " made of the storage root, the database name, the name and UUID it was"
                  + " created with, and the metadata file's name.",
              entity, metadataLocation.length(), metadataLocationMaxLength));
    }
  }

  /**
   * The check of {@link #requireTableNameFits} against the production store's width, {@value
   * #DEFAULT_TABLE_ID_MAX_LENGTH}.
   *
   * @param entity what is written, as the message names it, for example {@code Table db.t}
   * @param tableId the name the write would store in the row
   * @throws CommitRejectedException if the row cannot hold {@code tableId}
   */
  public static void requireTableIdFits(String entity, String tableId) {
    DEFAULT.requireTableNameFits(entity, tableId);
  }

  /**
   * @param entity what is written, as the message names it, for example {@code Table db.t}
   * @param tableId the name the write would store in the row
   * @throws CommitRejectedException if this store's row cannot hold {@code tableId}
   */
  public void requireTableNameFits(String entity, String tableId) {
    if (tableId.length() > tableIdMaxLength) {
      throw new CommitRejectedException(
          String.format(
              "%s cannot be written under a name of %d characters: House Tables holds at most %d"
                  + " (user_table_row.table_id).",
              entity, tableId.length(), tableIdMaxLength));
    }
  }
}
