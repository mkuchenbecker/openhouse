package com.linkedin.openhouse.spark42.extensions;

import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The SQL-extensions tests' service stores a table wherever the repository is checked out.
 *
 * <p>House Tables keeps a table's metadata location in 255 characters, and a create whose location
 * is longer fails with a 503 that the client raises as {@code CommitStateUnknownException}. The
 * service's warehouse used to be under the build directory, where a location was the checkout's
 * path, about 200 characters more and the table's name: on a GitHub runner ({@code
 * /home/runner/work/openhouse/openhouse}) {@code SparkIrcGrantsUnlockTest} could not create {@code
 * legacy_lock_by_reason}, while in a shorter checkout it could. The warehouse is now a short path
 * of its own ({@code ServiceWarehouse} in the service source set).
 */
public class ExtensionsServiceWarehouseTest {

  /**
   * 60 characters. Under the build directory this table's metadata location was the checkout's path
   * plus about 260 characters, so it could not be created in any checkout.
   */
  private static final String LONG_NAME =
      "a_table_whose_name_is_sixty_characters_long_for_the_location";

  @Test
  void aTableWithASixtyCharacterNameIsCreatedAndItsLocationFitsHouseTables() throws Exception {
    Assertions.assertEquals(60, LONG_NAME.length());
    ExtensionsTestService service = ExtensionsTestService.get();
    service.create(LONG_NAME, false);
    Map<String, String> properties = service.properties(LONG_NAME);
    String location = properties.get("openhouse.tableLocation");
    Assertions.assertNotNull(location, properties.toString());
    Assertions.assertTrue(location.contains("/" + LONG_NAME + "-"), location);
    Assertions.assertTrue(location.length() <= 255, location.length() + ": " + location);
  }
}
