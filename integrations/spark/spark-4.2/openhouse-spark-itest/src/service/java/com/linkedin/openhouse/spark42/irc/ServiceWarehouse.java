package com.linkedin.openhouse.spark42.irc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Where a test service keeps its tables: a new directory directly under the system's temporary
 * directory, removed when the service's JVM exits.
 *
 * <p>Not under the service's work directory, because that is inside the checkout's build directory
 * and the length of a table's metadata location is limited. The House Tables stand-in these
 * services run on keeps the location in a 255-character column, as the real store's baseline DDL
 * does ({@code metadata_location VARCHAR(255)}), and a commit whose location is longer fails: the
 * native PUT answers 503 ("isn't ack'ed by server about the commit status ... DataException") and
 * the client raises {@code CommitStateUnknownException}. A location is the warehouse path plus
 * {@code /<database>/<table>-<uuid>/<version>-<uuid>.metadata.json}: 96 characters and the two
 * names. Under the work directory the warehouse path was the checkout's path plus about 100
 * characters, so whether a table could be created depended on where the repository was checked out
 * and on the table's name: under {@code /home/runner/work/openhouse/openhouse} (a GitHub runner)
 * {@code legacy_lock_by_reason} could not be created, while under a shorter path it could.
 *
 * <p>{@link #create()} refuses a temporary directory so deep that the same would happen again.
 */
final class ServiceWarehouse {

  /**
   * The longest warehouse path accepted. It leaves 255 - 90 - 96 = 69 characters for the database
   * and table names together.
   */
  static final int MAX_PATH_LENGTH = 90;

  private ServiceWarehouse() {}

  /** A new, empty warehouse directory, deleted with everything in it when this JVM exits. */
  static Path create() throws IOException {
    Path warehouse = Files.createTempDirectory("oh42-").toAbsolutePath();
    Runtime.getRuntime().addShutdownHook(new Thread(() -> delete(warehouse), "warehouse-delete"));
    if (warehouse.toString().length() > MAX_PATH_LENGTH) {
      throw new IllegalStateException(
          "The temporary directory is too deep for a test warehouse: "
              + warehouse
              + " is "
              + warehouse.toString().length()
              + " characters, more than "
              + MAX_PATH_LENGTH
              + ". House Tables keeps a table's metadata location in 255 characters."
              + " Set java.io.tmpdir to a shorter path.");
    }
    return warehouse;
  }

  private static void delete(Path warehouse) {
    try (Stream<Path> paths = Files.walk(warehouse)) {
      paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
    } catch (IOException | UncheckedIOException e) {
      // Best effort: the directory is under the system's temporary directory.
    }
  }
}
