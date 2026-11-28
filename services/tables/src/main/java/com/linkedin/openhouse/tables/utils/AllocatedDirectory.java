package com.linkedin.openhouse.tables.utils;

import java.util.Optional;
import java.util.UUID;

/**
 * The UUID a table or view carries, read from where its House Tables row has it: the name of the
 * directory its metadata file lies in. OpenHouse allocates a table or view the directory {@code
 * <database>/<name>-<uuid>} and writes its metadata files directly in it, and a rename keeps the
 * directory; House Tables keeps the location, not the UUID. Nothing but the location is read.
 *
 * <p>A name that ends in {@code -} and a UUID is taken as such, whatever comes before it, so a row
 * whose location OpenHouse did not allocate (metadata in a subdirectory, a storage that names
 * directories another way) yields no UUID or a UUID it does not carry; it never fails.
 */
public final class AllocatedDirectory {

  private static final int UUID_LENGTH = 36;

  private AllocatedDirectory() {}

  /** The UUID the directory of {@code metadataLocation} is named for, if it is named for one. */
  public static Optional<UUID> uuidOf(String metadataLocation) {
    if (metadataLocation == null) {
      return Optional.empty();
    }
    int fileAt = metadataLocation.lastIndexOf('/');
    if (fileAt <= 0) {
      return Optional.empty();
    }
    String directory = metadataLocation.substring(0, fileAt);
    String name = directory.substring(directory.lastIndexOf('/') + 1);
    if (name.length() <= UUID_LENGTH || name.charAt(name.length() - UUID_LENGTH - 1) != '-') {
      return Optional.empty();
    }
    try {
      return Optional.of(UUID.fromString(name.substring(name.length() - UUID_LENGTH)));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
