package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.NativeViewHttp.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.internal.catalog.model.HouseTablePrimaryKey;
import com.linkedin.openhouse.internal.catalog.repository.HouseTableRepository;
import com.linkedin.openhouse.tables.toggle.repository.ToggleStatusesRepository;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The view counterpart of {@link StoredMetadataGoldenTest}: pins what native view writes store, the
 * House Tables pointer row and every metadata file, in order, as written, after normalizing only
 * what is time- or random-based ({@link StoredViewMetadata}). The expected files under {@code
 * stored-metadata/views/} were produced by this test before the IRC view routes existed, so a
 * change in what a native view write stores, or in how many metadata files it writes, fails here.
 *
 * <p>Same Spring context as the IRC view tests. To rewrite the expected files, run with {@code
 * STORED_METADATA_GOLDEN_DIR} set to {@code services/tables/src/test/resources/stored-metadata}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({IrcReadRoutesTest.StubDefaults.class, IrcViewRoutesTest.ViewAudit.class})
@ContextConfiguration(initializers = {PropertyOverrideContextInitializer.class})
public class StoredViewMetadataGoldenTest {
  @Autowired MockMvc mvc;
  @Autowired HouseTableRepository houseTables;
  @Autowired ToggleStatusesRepository toggles;

  private final List<String> databases = new ArrayList<>();

  @AfterEach
  void cleanUp() throws Exception {
    for (String db : databases) {
      delete(mvc, db, "v");
      CommitPathHttp.delete(mvc, db, "t");
    }
    databases.clear();
  }

  /** A database with views enabled, made to exist by a table, as the native API requires. */
  private void database(String db) throws Exception {
    databases.add(db);
    enableViews(toggles, db);
    CommitPathHttp.post(mvc, CommitPathHttp.newTable(db, "t")).andExpect(status().isCreated());
  }

  private void create(String db) throws Exception {
    database(db);
    Assertions.assertEquals(
        201,
        code(
            post(
                mvc,
                createBody(
                    db,
                    "v",
                    SCHEMA_ID_NAME,
                    "SELECT id, name FROM " + db + ".t",
                    Collections.singletonMap("owner", "gold")))));
  }

  /** The stored properties of the current version, which a native replace echoes in full. */
  static Map<String, String> storedProperties(HouseTableRepository houseTables, String db)
      throws Exception {
    String pointer =
        houseTables
            .findViewById(HouseTablePrimaryKey.builder().databaseId(db).tableId("v").build())
            .get()
            .getTableLocation();
    JsonNode metadata =
        StoredMetadata.MAPPER.readTree(
            Files.readAllBytes(
                Paths.get(
                    pointer.startsWith("file:") ? pointer.substring("file:".length()) : pointer)));
    Map<String, String> properties = new LinkedHashMap<>();
    metadata
        .path("properties")
        .fields()
        .forEachRemaining(e -> properties.put(e.getKey(), e.getValue().asText()));
    return properties;
  }

  private void replace(String db, String schema, String sql, Map<String, String> properties)
      throws Exception {
    Assertions.assertEquals(
        200,
        code(
            put(
                mvc,
                replaceBody(
                    db,
                    "v",
                    pointer(mvc, db, "v"),
                    schema,
                    Collections.singletonList(spark(sql)),
                    properties))));
  }

  @Test
  void viewCreate() throws Exception {
    create("gold_view_create");
    expectStored("gold_view_create");
  }

  /** A replace with new SQL and a new schema, as {@code CREATE OR REPLACE VIEW} makes one. */
  @Test
  void viewReplace() throws Exception {
    String db = "gold_view_replace";
    create(db);
    replace(
        db,
        SCHEMA_ID_NAME_EXTRA,
        "SELECT id, name, 1 AS extra FROM " + db + ".t",
        storedProperties(houseTables, db));
    expectStored(db);
  }

  /** A user property set, then removed while another is set, definition unchanged. */
  @Test
  void viewProperties() throws Exception {
    String db = "gold_view_props";
    create(db);
    String sql = "SELECT id, name FROM " + db + ".t";
    Map<String, String> properties = storedProperties(houseTables, db);
    properties.put("user.key", "v1");
    replace(db, SCHEMA_ID_NAME, sql, properties);
    properties = storedProperties(houseTables, db);
    properties.remove("user.key");
    properties.put("user.other", "v2");
    replace(db, SCHEMA_ID_NAME, sql, properties);
    expectStored(db);
  }

  /** A replace that changes nothing writes no metadata file and leaves the pointer. */
  @Test
  void viewNoOpReplace() throws Exception {
    String db = "gold_view_noop";
    create(db);
    replace(
        db, SCHEMA_ID_NAME, "SELECT id, name FROM " + db + ".t", storedProperties(houseTables, db));
    expectStored(db);
  }

  private void expectStored(String db) throws Exception {
    String actual = StoredViewMetadata.normalized(houseTables, db, "v");
    String name = "views/" + db + ".json";
    String target = System.getenv("STORED_METADATA_GOLDEN_DIR");
    if (target != null) {
      Path file = Paths.get(target).resolve(name);
      Files.createDirectories(file.getParent());
      Files.write(file, (actual + "\n").getBytes(StandardCharsets.UTF_8));
      return;
    }
    try (InputStream in = getClass().getResourceAsStream("/stored-metadata/" + name)) {
      Assertions.assertNotNull(in, "missing expected file stored-metadata/" + name);
      String expected = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
      Assertions.assertEquals(expected, actual, "stored view metadata differs for " + db);
    }
  }
}
