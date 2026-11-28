package com.linkedin.openhouse.internal.catalog;

import com.linkedin.openhouse.cluster.configs.ClusterProperties;
import com.linkedin.openhouse.common.exception.CommitRejectedException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * What a House Tables row can hold of the values a write sends it, and the refusal of a write whose
 * row would not fit, where the deployment states its store's widths.
 *
 * <p>A table or view write puts its metadata file on storage first and its House Tables row second,
 * so a row the store cannot take fails after the file exists: the caller is told the commit's state
 * is unknown, and the file stays. A deployment that states its store's widths has every write
 * checked here before anything is written instead.
 *
 * <p>The widths are the columns of {@code user_table_row}. Two files declare that table, and they
 * disagree:
 *
 * <ul>
 *   <li>{@code services/housetables/ddl/0000__baseline.sql}, the record of the production store:
 *       {@code metadata_location VARCHAR(255)}, {@code table_id VARCHAR(255)} (and {@code
 *       soft_deleted_user_table_row.table_id VARCHAR(128)}).
 *   <li>{@code services/housetables/src/main/resources/schema.sql}, which the House Tables service
 *       runs at start-up where nobody else created its tables: {@code metadata_location
 *       VARCHAR(512)}, {@code table_id VARCHAR(128)}.
 * </ul>
 *
 * <p>Both widths are therefore the deployment's to state ({@code
 * cluster.housetables.metadata-location-max-length} and {@code
 * cluster.housetables.table-id-max-length}, {@link ClusterProperties}). Neither is checked unless
 * stated: by default every write goes to House Tables as it always did, and the store decides. Only
 * the columns a request can overflow are limited here: the database name is at most 128 characters
 * in every request that writes it, and the storage type, entity type, version and creation time are
 * the server's.
 */
@Component
public class HouseTableRowLimits {

  /** The value of a limit that is not checked: the default of both properties. */
  public static final int NO_LIMIT = 0;

  /** No limit checked: what a deployment that states no width gets. */
  public static final HouseTableRowLimits DEFAULT = new HouseTableRowLimits(NO_LIMIT, NO_LIMIT);

  private final int metadataLocationMaxLength;

  private final int tableIdMaxLength;

  /**
   * The limits of the cluster's House Tables store, as its configuration states them; none in a
   * context that holds no cluster configuration.
   */
  @Autowired
  public HouseTableRowLimits(ObjectProvider<ClusterProperties> clusterProperties) {
    this(clusterProperties.stream().findFirst().orElse(null));
  }

  /** The limits {@code clusterProperties} states; none for {@code null}. */
  public HouseTableRowLimits(ClusterProperties clusterProperties) {
    this(
        clusterProperties == null
            ? NO_LIMIT
            : clusterProperties.getClusterHouseTablesMetadataLocationMaxLength(),
        clusterProperties == null
            ? NO_LIMIT
            : clusterProperties.getClusterHouseTablesTableIdMaxLength());
  }

  /**
   * @param metadataLocationMaxLength the width of {@code user_table_row.metadata_location}; a value
   *     that is not positive means it is not checked
   */
  public HouseTableRowLimits(int metadataLocationMaxLength) {
    this(metadataLocationMaxLength, NO_LIMIT);
  }

  /**
   * @param metadataLocationMaxLength the width of {@code user_table_row.metadata_location}
   * @param tableIdMaxLength the width of {@code user_table_row.table_id}
   *     <p>A value that is not positive means that column is not checked.
   */
  public HouseTableRowLimits(int metadataLocationMaxLength, int tableIdMaxLength) {
    this.metadataLocationMaxLength = Math.max(NO_LIMIT, metadataLocationMaxLength);
    this.tableIdMaxLength = Math.max(NO_LIMIT, tableIdMaxLength);
  }

  /** The width checked for a metadata location; {@value #NO_LIMIT} when none is. */
  public int getMetadataLocationMaxLength() {
    return metadataLocationMaxLength;
  }

  /** The width checked for a name; {@value #NO_LIMIT} when none is. */
  public int getTableIdMaxLength() {
    return tableIdMaxLength;
  }

  /**
   * @param entity what is written, as the message names it, for example {@code Table db.t}
   * @param metadataLocation the location the write would store in the row
   * @throws CommitRejectedException if a width is stated and the row cannot hold {@code
   *     metadataLocation}
   */
  public void requireMetadataLocationFits(String entity, String metadataLocation) {
    if (metadataLocationMaxLength > NO_LIMIT
        && metadataLocation.length() > metadataLocationMaxLength) {
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
   * @param entity what is written, as the message names it, for example {@code Table db.t}
   * @param tableId the name the write would store in the row
   * @throws CommitRejectedException if a width is stated and this store's row cannot hold {@code
   *     tableId}
   */
  public void requireTableNameFits(String entity, String tableId) {
    if (tableIdMaxLength > NO_LIMIT && tableId.length() > tableIdMaxLength) {
      throw new CommitRejectedException(
          String.format(
              "%s cannot be written under a name of %d characters: House Tables holds at most %d"
                  + " (user_table_row.table_id).",
              entity, tableId.length(), tableIdMaxLength));
    }
  }
}
