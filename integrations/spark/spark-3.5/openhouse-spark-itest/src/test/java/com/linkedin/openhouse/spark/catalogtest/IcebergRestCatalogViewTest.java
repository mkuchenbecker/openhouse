package com.linkedin.openhouse.spark.catalogtest;

import com.linkedin.openhouse.javaclient.IcebergLine;
import com.linkedin.openhouse.tablestest.OpenHouseSparkITest;
import java.util.Collections;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.Schema;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.catalog.ViewCatalog;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.exceptions.ForbiddenException;
import org.apache.iceberg.exceptions.NoSuchNamespaceException;
import org.apache.iceberg.exceptions.NoSuchViewException;
import org.apache.iceberg.exceptions.RESTException;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.types.Types;
import org.apache.iceberg.view.BaseView;
import org.apache.iceberg.view.SQLViewRepresentation;
import org.apache.iceberg.view.View;
import org.apache.iceberg.view.ViewMetadata;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.function.Executable;

/**
 * Black box: an unmodified Iceberg 1.5.2 {@link RESTCatalog} (a {@link ViewCatalog}) works with
 * OpenHouse views over real HTTP against an embedded tables service with views enabled, and after
 * every step the OpenHouse Java client's view of the view equals the {@code RESTCatalog}'s: the
 * same metadata file, and the same metadata.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class IcebergRestCatalogViewTest extends OpenHouseSparkITest {
  private static final String DB = "irc_views";
  private static final String OFF_DB = "irc_views_off";
  private static final String NODB = "irc_views_nodb";
  private static final Namespace NS = Namespace.of(DB);

  private static final Schema SCHEMA =
      new Schema(Types.NestedField.optional(1, "id", Types.LongType.get()));
  private static final Schema SCHEMA_EXTRA =
      new Schema(
          Types.NestedField.optional(1, "id", Types.LongType.get()),
          Types.NestedField.optional(2, "extra", Types.IntegerType.get()));

  private IrcViewsServer server;
  private Configuration conf;
  private RESTCatalog rest;
  private RESTCatalog rest2;
  private Catalog openhouse;

  @BeforeAll
  void start() throws Exception {
    conf = getSparkSession().sparkContext().hadoopConfiguration();
    server = new IrcViewsServer(DB, NODB);
    rest = server.rest(IrcViewsServer.OWNER, conf);
    rest2 = server.rest(IrcViewsServer.OWNER, conf);
    openhouse = server.openhouse(IrcViewsServer.OWNER, conf);
    // A database exists once it holds a table.
    openhouse.createTable(TableIdentifier.of(DB, "base"), SCHEMA);
    openhouse.createTable(TableIdentifier.of(OFF_DB, "base"), SCHEMA);
  }

  @AfterAll
  void stop() throws Exception {
    try {
      rest.close();
      rest2.close();
    } finally {
      server.close();
    }
  }

  private static TableIdentifier id(String view) {
    return TableIdentifier.of(DB, view);
  }

  private static ViewMetadata metadata(View view) {
    return ((BaseView) view).operations().current();
  }

  /** The OpenHouse client and a fresh RESTCatalog load show the same view. */
  private void assertSameView(String view) {
    ViewMetadata viaRest = metadata(rest.loadView(id(view)));
    ViewMetadata viaOpenHouse = metadata(((ViewCatalog) openhouse).loadView(id(view)));
    Assertions.assertEquals(viaOpenHouse.metadataFileLocation(), viaRest.metadataFileLocation());
    // Field by field: the JSON text differs only in the order of the properties map.
    Assertions.assertEquals(viaOpenHouse.uuid(), viaRest.uuid());
    Assertions.assertEquals(viaOpenHouse.location(), viaRest.location());
    Assertions.assertEquals(viaOpenHouse.formatVersion(), viaRest.formatVersion());
    Assertions.assertEquals(viaOpenHouse.properties(), viaRest.properties());
    Assertions.assertEquals(viaOpenHouse.currentVersionId(), viaRest.currentVersionId());
    Assertions.assertEquals(viaOpenHouse.versions(), viaRest.versions());
    Assertions.assertEquals(viaOpenHouse.history(), viaRest.history());
    Assertions.assertEquals(
        viaOpenHouse.schemas().stream()
            .map(IcebergRestCatalogViewTest::schemaJson)
            .collect(java.util.stream.Collectors.toList()),
        viaRest.schemas().stream()
            .map(IcebergRestCatalogViewTest::schemaJson)
            .collect(java.util.stream.Collectors.toList()));
  }

  private static String schemaJson(Schema schema) {
    return org.apache.iceberg.SchemaParser.toJson(schema);
  }

  private static Throwable thrown(Executable executable) {
    try {
      executable.execute();
    } catch (Throwable t) {
      return t;
    }
    throw new AssertionError("expected a failure");
  }

  private static void assertThrown(Class<?> type, String text, Throwable thrown) {
    Assertions.assertEquals(type, thrown.getClass(), String.valueOf(thrown));
    Assertions.assertTrue(
        String.valueOf(thrown.getMessage()).contains(text), String.valueOf(thrown.getMessage()));
  }

  @Test
  void createLoadListReplacePropertiesRenameDrop() {
    View created =
        rest.buildView(id("v"))
            .withSchema(SCHEMA)
            .withQuery("spark", "SELECT id FROM base")
            .withDefaultNamespace(NS)
            .withDefaultCatalog("openhouse")
            .withProperty("owner", "irc")
            .create();
    Assertions.assertEquals("spark", created.properties().get("openhouse.view.sourceDialect"));
    Assertions.assertEquals("irc", created.properties().get("owner"));
    Assertions.assertTrue(SCHEMA.sameSchema(created.schema()));
    assertSameView("v");

    View loaded = rest.loadView(id("v"));
    Assertions.assertEquals("SELECT id FROM base", loaded.sqlFor("spark").sql());
    Assertions.assertEquals(NS, loaded.currentVersion().defaultNamespace());
    Assertions.assertEquals("openhouse", loaded.currentVersion().defaultCatalog());
    Assertions.assertTrue(rest.viewExists(id("v")));
    // Views and tables are listed apart.
    Assertions.assertTrue(rest.listViews(NS).contains(id("v")), rest.listViews(NS).toString());
    Assertions.assertEquals(Collections.singletonList(id("base")), rest.listTables(NS));
    Assertions.assertFalse(rest.tableExists(id("v")));

    // A create of an existing view is refused.
    assertThrown(
        AlreadyExistsException.class,
        "View already exists",
        thrown(
            () ->
                rest.buildView(id("v"))
                    .withSchema(SCHEMA)
                    .withQuery("spark", "SELECT 1")
                    .withDefaultNamespace(NS)
                    .create()));

    // Replace with new SQL and a new schema.
    View replaced =
        rest.buildView(id("v"))
            .withSchema(SCHEMA_EXTRA)
            .withQuery("spark", "SELECT id, 1 AS extra FROM base")
            .withDefaultNamespace(NS)
            .withDefaultCatalog("openhouse")
            .replace();
    Assertions.assertEquals(2, replaced.currentVersion().versionId());
    Assertions.assertTrue(SCHEMA_EXTRA.sameSchema(replaced.schema()));
    Assertions.assertEquals(
        "SELECT id, 1 AS extra FROM base",
        ((SQLViewRepresentation) replaced.currentVersion().representations().get(0)).sql());
    assertSameView("v");

    // Set and remove properties.
    rest.loadView(id("v")).updateProperties().set("user.k", "1").commit();
    Assertions.assertEquals("1", rest.loadView(id("v")).properties().get("user.k"));
    assertSameView("v");
    rest.loadView(id("v")).updateProperties().remove("user.k").commit();
    Assertions.assertNull(rest.loadView(id("v")).properties().get("user.k"));
    assertSameView("v");
    // System properties are the server's.
    Throwable system =
        thrown(
            () ->
                rest.loadView(id("v"))
                    .updateProperties()
                    .set("openhouse.tableId", "other")
                    .commit());
    assertThrown(
        org.apache.iceberg.exceptions.BadRequestException.class,
        "System view properties cannot be added, changed or removed",
        system);
    assertSameView("v");

    // The native view API has no rename. The route answers 406, which 1.5.2 raises as
    // RESTException; 1.12 reads the config's endpoint list, which omits the route, and refuses
    // before sending. The view is unchanged.
    if (IcebergLine.current() == IcebergLine.LEGACY) {
      assertThrown(
          RESTException.class,
          "do not support rename",
          thrown(() -> rest.renameView(id("v"), id("v_renamed"))));
    } else {
      assertThrown(
          UnsupportedOperationException.class,
          "Server does not support endpoint: POST /v1/{prefix}/views/rename",
          thrown(() -> rest.renameView(id("v"), id("v_renamed"))));
    }
    Assertions.assertTrue(rest.viewExists(id("v")));
    Assertions.assertFalse(rest.viewExists(id("v_renamed")));
    assertSameView("v");

    Assertions.assertTrue(rest.dropView(id("v")));
    Assertions.assertFalse(rest.viewExists(id("v")));
    Assertions.assertFalse(((ViewCatalog) openhouse).viewExists(id("v")));
    Assertions.assertFalse(rest.dropView(id("v")));
    Assertions.assertFalse(rest.listViews(NS).contains(id("v")));
  }

  @Test
  void aMissingViewIsNoSuchView() {
    assertThrown(
        NoSuchViewException.class,
        "View does not exist: " + id("missing"),
        thrown(() -> rest.loadView(id("missing"))));
    Assertions.assertFalse(rest.viewExists(id("missing")));
    Assertions.assertFalse(rest.dropView(id("missing")));
    // A table is not a view.
    Assertions.assertFalse(rest.viewExists(id("base")));
    assertThrown(
        NoSuchNamespaceException.class,
        "Database not found",
        thrown(
            () ->
                rest.buildView(TableIdentifier.of(NODB, "v"))
                    .withSchema(SCHEMA)
                    .withQuery("spark", "SELECT 1")
                    .withDefaultNamespace(NS)
                    .create()));
  }

  /**
   * With views off for a database (the default), each route answers the native 404, so the client
   * behaves as against a catalog without views: no view to load or drop, no namespace to create a
   * view in, and no views to list.
   */
  @Test
  void withViewsDisabledTheClientSeesNoViews() {
    TableIdentifier off = TableIdentifier.of(OFF_DB, "v");
    assertThrown(NoSuchViewException.class, "Views are disabled", thrown(() -> rest.loadView(off)));
    Assertions.assertFalse(rest.viewExists(off));
    Assertions.assertFalse(rest.dropView(off));
    // A database whose views are disabled holds no views: the list is empty (not the native 404).
    Assertions.assertEquals(Collections.emptyList(), rest.listViews(Namespace.of(OFF_DB)));
    assertThrown(
        NoSuchNamespaceException.class,
        "Views are disabled",
        thrown(
            () ->
                rest.buildView(off)
                    .withSchema(SCHEMA)
                    .withQuery("spark", "SELECT 1")
                    .withDefaultNamespace(Namespace.of(OFF_DB))
                    .create()));
    // Tables are unaffected.
    Assertions.assertEquals(
        Collections.singletonList(TableIdentifier.of(OFF_DB, "base")),
        rest.listTables(Namespace.of(OFF_DB)));
  }

  /**
   * A principal the service denies cannot load the view: the load hands out the definition the
   * service reads, so it needs the read privilege, as a table load does. Nor can it drop or create
   * one. (A client replaces a view through its load, so a denied replace is not reachable from the
   * client here; the service's route tests cover it.) The list of names makes no decision, as
   * natively. (Whether the client then says the view exists is the client's: Iceberg 1.5 answers it
   * with a load, so for this principal it says no; the service's exists route makes no decision.)
   */
  @Test
  void aDeniedPrincipalIsForbiddenToReadAndWrite() throws Exception {
    rest.buildView(id("guarded"))
        .withSchema(SCHEMA)
        .withQuery("spark", "SELECT id FROM base")
        .withDefaultNamespace(NS)
        .create();
    try (RESTCatalog stranger = server.rest(IrcViewsServer.STRANGER, conf)) {
      // The client raises the 403 as it is, or (Iceberg 1.5) wrapped in "Unable to load view".
      Throwable denied = thrown(() -> stranger.loadView(id("guarded")));
      Throwable forbidden = denied;
      while (forbidden != null && !(forbidden instanceof ForbiddenException)) {
        forbidden = forbidden.getCause();
      }
      Assertions.assertNotNull(forbidden, String.valueOf(denied));
      assertThrown(ForbiddenException.class, "irc_stranger", forbidden);
      Assertions.assertFalse(String.valueOf(denied.getMessage()).contains("SELECT"));
      Assertions.assertTrue(stranger.listViews(NS).contains(id("guarded")));
      assertThrown(
          ForbiddenException.class, "irc_stranger", thrown(() -> stranger.dropView(id("guarded"))));
      assertThrown(
          ForbiddenException.class,
          "irc_stranger",
          thrown(
              () ->
                  stranger
                      .buildView(id("other"))
                      .withSchema(SCHEMA)
                      .withQuery("spark", "SELECT 1")
                      .withDefaultNamespace(NS)
                      .create()));
    }
    Assertions.assertFalse(rest.viewExists(id("other")));
    Assertions.assertNull(rest.loadView(id("guarded")).properties().get("k"));
    assertSameView("guarded");
    rest.dropView(id("guarded"));
  }

  /**
   * A principal who may read the view but not write it loads it, and its replace through the client
   * is the native PUT's 403.
   */
  @Test
  void aReaderMayLoadAndIsForbiddenToReplace() throws Exception {
    rest.buildView(id("readable"))
        .withSchema(SCHEMA)
        .withQuery("spark", "SELECT id FROM base")
        .withDefaultNamespace(NS)
        .create();
    try (RESTCatalog reader = server.rest(IcebergRestCatalogReadTest.READER, conf)) {
      Assertions.assertEquals(
          metadata(rest.loadView(id("readable"))).metadataFileLocation(),
          metadata(reader.loadView(id("readable"))).metadataFileLocation());
      assertThrown(
          ForbiddenException.class,
          "irc_reader",
          thrown(() -> reader.loadView(id("readable")).updateProperties().set("k", "v").commit()));
    }
    Assertions.assertNull(rest.loadView(id("readable")).properties().get("k"));
    rest.dropView(id("readable"));
  }

  /**
   * A principal who may replace a view but not read it cannot replace it through an Iceberg client
   * any more: the client loads the view first, and the load needs the read privilege. Pinned as
   * what it is. On Iceberg 1.12 the load raises the 403; on 1.5 the client wraps it in "Unable to
   * load view" (a NoSuchViewException). A create-or-replace is refused 403 on both lines.
   */
  @Test
  void aWriterThatMayNotReadCannotReplaceThroughTheClient() throws Exception {
    rest.buildView(id("writable"))
        .withSchema(SCHEMA)
        .withQuery("spark", "SELECT id FROM base")
        .withDefaultNamespace(NS)
        .create();
    try (RESTCatalog writer = server.rest(IcebergRestCatalogReadTest.WRITER, conf)) {
      Throwable load = thrown(() -> writer.loadView(id("writable")));
      Throwable forbidden = load;
      while (forbidden != null && !(forbidden instanceof ForbiddenException)) {
        forbidden = forbidden.getCause();
      }
      Assertions.assertNotNull(forbidden, String.valueOf(load));
      // Whether the view exists: 1.12 asks the exists route, which makes no decision; 1.5 loads it,
      // and reports a view it may not read as absent.
      Assertions.assertEquals(
          IcebergLine.current() != IcebergLine.LEGACY, writer.viewExists(id("writable")));
      Throwable replace =
          thrown(
              () ->
                  writer
                      .buildView(id("writable"))
                      .withSchema(SCHEMA)
                      .withQuery("spark", "SELECT 2")
                      .withDefaultNamespace(NS)
                      .createOrReplace());
      // Run on both lines: the create-or-replace is refused 403 on 1.12 and on 1.5 alike.
      Assertions.assertEquals(
          ForbiddenException.class, replace.getClass(), String.valueOf(replace));
      assertThrown(ForbiddenException.class, "irc_writer", replace);
    }
    assertSameView("writable");
    rest.dropView(id("writable"));
  }

  /**
   * Two writers on one view: each loads, then both change properties. A view commit asserts only
   * the view's UUID, and the server applies each commit to the stored view, so neither change is
   * lost; both clients then see the same view.
   */
  @Test
  void twoWritersRacingOnOneView() {
    rest.buildView(id("race"))
        .withSchema(SCHEMA)
        .withQuery("spark", "SELECT id FROM base")
        .withDefaultNamespace(NS)
        .create();
    View one = rest.loadView(id("race"));
    View two = rest2.loadView(id("race"));
    one.updateProperties().set("a", "1").commit();
    two.updateProperties().set("b", "2").commit();
    View after = rest.loadView(id("race"));
    Assertions.assertEquals("1", after.properties().get("a"));
    Assertions.assertEquals("2", after.properties().get("b"));
    assertSameView("race");
    // Two replaces from stale handles: the later one is the current version.
    rest.buildView(id("race"))
        .withSchema(SCHEMA)
        .withQuery("spark", "SELECT id + 1 AS id FROM base")
        .withDefaultNamespace(NS)
        .replace();
    rest2
        .buildView(id("race"))
        .withSchema(SCHEMA)
        .withQuery("spark", "SELECT id + 2 AS id FROM base")
        .withDefaultNamespace(NS)
        .replace();
    Assertions.assertEquals(
        "SELECT id + 2 AS id FROM base", rest.loadView(id("race")).sqlFor("spark").sql());
    assertSameView("race");
    rest.dropView(id("race"));
  }
}
