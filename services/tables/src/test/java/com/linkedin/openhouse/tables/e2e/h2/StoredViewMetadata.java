package com.linkedin.openhouse.tables.e2e.h2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linkedin.openhouse.internal.catalog.model.HouseTable;
import com.linkedin.openhouse.internal.catalog.model.HouseTablePrimaryKey;
import com.linkedin.openhouse.internal.catalog.repository.HouseTableRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * What a view's writes left: its House Tables row (the pointer) and every metadata file under the
 * view's location, oldest first, as written. Normalized with {@link StoredMetadata}'s rules (UUIDs,
 * {@code timestamp-ms}, {@code openhouse.creationTime} and {@code openhouse.lastModifiedTime}) and
 * nothing else. Shared by the view golden test (native view writes against recorded files) and the
 * IRC view tests (an IRC view write against the equivalent native one).
 *
 * <p>A view's metadata does not log its previous files, so the files are found by listing the
 * directory the engine lays them out in: flat under the view's location, numbered by version.
 */
final class StoredViewMetadata {
  private StoredViewMetadata() {}

  /** The normalized row and stored versions of {@code db.view}, as indented JSON. */
  static String normalized(HouseTableRepository repository, String db, String view)
      throws Exception {
    return StoredMetadata.MAPPER.writeValueAsString(normalize(stored(repository, db, view)));
  }

  /** The normalized form of {@link #stored}, with {@code db} written as {@code <db>}. */
  static String normalizedWithoutDb(HouseTableRepository repository, String db, String view)
      throws Exception {
    return normalized(repository, db, view).replace(db, "<db>");
  }

  static ObjectNode stored(HouseTableRepository repository, String db, String view)
      throws Exception {
    HouseTable row =
        repository
            .findViewById(HouseTablePrimaryKey.builder().databaseId(db).tableId(view).build())
            .orElseThrow(() -> new AssertionError("no view row for " + db + "." + view));
    ObjectNode stored = StoredMetadata.MAPPER.createObjectNode();
    ObjectNode pointer = stored.putObject("row");
    pointer.put("entityType", row.getEntityType());
    pointer.put("tableUUID", row.getTableUUID());
    pointer.put("tableLocation", fileName(row.getTableLocation()));
    pointer.put("tableVersion", fileName(row.getTableVersion()));
    pointer.put("tableCreator", row.getTableCreator());
    pointer.put("storageType", row.getStorageType());
    ArrayNode versions = stored.putArray("versions");
    for (Path file : files(row.getTableLocation())) {
      ObjectNode version = versions.addObject();
      version.put("file", file.getFileName().toString());
      version.set(
          "metadata",
          StoredMetadata.MAPPER.readTree(
              new String(Files.readAllBytes(file), StandardCharsets.UTF_8)));
    }
    return stored;
  }

  static JsonNode normalize(ObjectNode stored) {
    ArrayNode wrapper = StoredMetadata.MAPPER.createArrayNode();
    wrapper.add(stored);
    return StoredMetadata.normalize(wrapper).get(0);
  }

  /** The metadata files next to {@code pointer}, in version order. */
  private static List<Path> files(String pointer) throws IOException {
    Path dir = path(pointer).getParent();
    try (Stream<Path> listed = Files.list(dir)) {
      return listed
          .filter(p -> p.getFileName().toString().endsWith(".metadata.json"))
          .sorted()
          .collect(Collectors.toList());
    }
  }

  private static Path path(String location) {
    return Paths.get(
        location.startsWith("file:") ? location.substring("file:".length()) : location);
  }

  private static String fileName(String location) {
    if (location == null) {
      return null;
    }
    return location.substring(location.lastIndexOf('/') + 1);
  }
}
