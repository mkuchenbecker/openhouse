package com.linkedin.openhouse.tables.e2e.h2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.linkedin.openhouse.common.security.DummyTokenInterceptor.DummySecurityJWT;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.tables.toggle.repository.ToggleStatusesRepository;
import java.util.HashMap;
import java.util.Map;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.exceptions.BadRequestException;
import org.apache.iceberg.exceptions.NoSuchNamespaceException;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.types.Types;
import org.apache.iceberg.view.BaseView;
import org.apache.iceberg.view.View;
import org.apache.iceberg.view.ViewMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;

/**
 * {@code registerView} through an unmodified Iceberg 1.12 {@link RESTCatalog}, over HTTP: the
 * client finds the route in the config's endpoint list (it refuses locally otherwise), and a view
 * it created and dropped comes back from its metadata file as it was. The refusals arrive as the
 * exceptions the client raises for them. (The route's every rule: IrcRegisterViewRouteTest.)
 */
@SpringBootTest(
    classes = SpringH2Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = {PropertyOverrideContextInitializer.class})
@Import(IrcReadRoutesTest.StubDefaults.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class IrcRestCatalogRegisterViewTest {
  private static final String DB = "irc_rest_regview";

  private static final Schema SCHEMA =
      new Schema(
          Types.NestedField.optional(1, "id", Types.LongType.get()),
          Types.NestedField.optional(2, "name", Types.StringType.get()));

  private static final TableIdentifier VIEW = TableIdentifier.of(DB, "v");

  private static final TableIdentifier TABLE = TableIdentifier.of(DB, "t");

  @LocalServerPort private int port;

  @Autowired ToggleStatusesRepository toggles;

  private RESTCatalog rest;

  @BeforeEach
  void start() throws Exception {
    Map<String, String> properties = new HashMap<>();
    properties.put("uri", "http://localhost:" + port + "/iceberg");
    properties.put("token", new DummySecurityJWT("irc_regview").buildNoopJWT());
    rest =
        (RESTCatalog)
            CatalogUtil.loadCatalog(
                RESTCatalog.class.getName(), "openhouse", properties, new Configuration());
    NativeViewHttp.enableViews(toggles, DB);
    rest.createTable(TABLE, SCHEMA, PartitionSpec.unpartitioned());
  }

  @AfterEach
  void stop() throws Exception {
    try {
      if (rest.viewExists(VIEW)) {
        rest.dropView(VIEW);
      }
      rest.dropTable(TABLE);
    } finally {
      rest.close();
    }
  }

  private View create() {
    return rest.buildView(VIEW)
        .withSchema(SCHEMA)
        .withDefaultNamespace(Namespace.of(DB))
        .withDefaultCatalog("openhouse")
        .withQuery("spark", "SELECT id, name FROM " + DB + ".t")
        .withProperty("owner", "o")
        .create();
  }

  private static ViewMetadata metadata(View view) {
    return ((BaseView) view).operations().current();
  }

  @Test
  void aCreatedAndDroppedViewRegistersAsItWas() {
    View original = create();
    ViewMetadata before = metadata(original);
    String location = before.metadataFileLocation();
    assertTrue(rest.dropView(VIEW));
    assertFalse(rest.viewExists(VIEW));

    View registered = rest.registerView(VIEW, location);

    assertEquals("openhouse." + VIEW, registered.name());
    assertTrue(rest.viewExists(VIEW));
    assertEquals(original.uuid(), registered.uuid());
    assertEquals(original.location(), registered.location());
    assertTrue(original.schema().sameSchema(registered.schema()));
    assertEquals(original.versions(), registered.versions());
    assertEquals(original.currentVersion(), registered.currentVersion());
    assertEquals(original.history(), registered.history());
    assertEquals(original.properties(), registered.properties());
    View loaded = rest.loadView(VIEW);
    assertEquals(location, metadata(loaded).metadataFileLocation());
    // The registered view is an ordinary view: it can be replaced.
    loaded.updateProperties().set("k", "v").commit();
    assertEquals("v", rest.loadView(VIEW).properties().get("k"));
    assertEquals(original.uuid(), rest.loadView(VIEW).uuid());
  }

  @Test
  void theRefusalsAreTheClientsExceptions() {
    View view = create();
    String location = metadata(view).metadataFileLocation();
    AlreadyExistsException asView =
        assertThrows(AlreadyExistsException.class, () -> rest.registerView(VIEW, location));
    assertEquals("View already exists: " + VIEW, asView.getMessage());
    rest.dropView(VIEW);
    AlreadyExistsException asTable =
        assertThrows(AlreadyExistsException.class, () -> rest.registerView(TABLE, location));
    assertEquals("Name already exists as a table: " + TABLE, asTable.getMessage());
    assertThrows(
        BadRequestException.class, () -> rest.registerView(TableIdentifier.of(DB, "w"), location));
    assertThrows(
        NoSuchNamespaceException.class,
        () -> rest.registerView(TableIdentifier.of(DB + "_none", "v"), location));
    assertFalse(rest.viewExists(VIEW));
    assertFalse(rest.viewExists(TableIdentifier.of(DB, "w")));
  }
}
