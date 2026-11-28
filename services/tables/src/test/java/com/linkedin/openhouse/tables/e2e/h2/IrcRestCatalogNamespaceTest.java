package com.linkedin.openhouse.tables.e2e.h2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.linkedin.openhouse.common.security.DummyTokenInterceptor.DummySecurityJWT;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.exceptions.NamespaceNotEmptyException;
import org.apache.iceberg.exceptions.NoSuchNamespaceException;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;

/**
 * An unmodified Iceberg {@link RESTCatalog}, over HTTP, on the namespace routes: it creates a
 * namespace, loads it, sets and removes its properties and drops it, and gets the spec's exceptions
 * for a duplicate, a missing namespace and a namespace that holds a table.
 */
@SpringBootTest(
    classes = SpringH2Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = {PropertyOverrideContextInitializer.class})
@Import(IrcReadRoutesTest.StubDefaults.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class IrcRestCatalogNamespaceTest {

  private static final Namespace NS = Namespace.of("irc_rest_ns");

  @LocalServerPort private int port;

  private RESTCatalog rest;

  @BeforeEach
  void start() throws Exception {
    Map<String, String> properties = new HashMap<>();
    properties.put("uri", "http://localhost:" + port + "/iceberg");
    properties.put("token", new DummySecurityJWT("irc_ns_user").buildNoopJWT());
    rest =
        (RESTCatalog)
            CatalogUtil.loadCatalog(
                RESTCatalog.class.getName(), "openhouse", properties, new Configuration());
  }

  @AfterEach
  void stop() throws Exception {
    rest.close();
  }

  @Test
  void createLoadSetRemoveAndDrop() {
    assertFalse(rest.namespaceExists(NS));
    rest.createNamespace(NS, Collections.singletonMap("owner", "o"));
    assertTrue(rest.namespaceExists(NS));
    assertEquals(Collections.singletonMap("owner", "o"), rest.loadNamespaceMetadata(NS));

    assertTrue(rest.setProperties(NS, Collections.singletonMap("k", "v")));
    Map<String, String> both = new HashMap<>();
    both.put("owner", "o");
    both.put("k", "v");
    assertEquals(both, rest.loadNamespaceMetadata(NS));
    // Iceberg's client answers whether the server reports a key updated (RESTSessionCatalog), so a
    // removal alone answers false; the server reports the key removed (IrcNamespaceRoutesTest).
    assertFalse(rest.removeProperties(NS, Collections.singleton("owner")));
    assertEquals(Collections.singletonMap("k", "v"), rest.loadNamespaceMetadata(NS));

    AlreadyExistsException duplicate =
        assertThrows(AlreadyExistsException.class, () -> rest.createNamespace(NS));
    assertTrue(
        duplicate.getMessage().contains("Namespace already exists: irc_rest_ns"),
        duplicate.getMessage());

    assertTrue(rest.dropNamespace(NS));
    assertFalse(rest.namespaceExists(NS));
    assertFalse(rest.dropNamespace(NS));
    NoSuchNamespaceException missing =
        assertThrows(NoSuchNamespaceException.class, () -> rest.loadNamespaceMetadata(NS));
    assertTrue(
        missing.getMessage().startsWith("Namespace does not exist: irc_rest_ns"),
        missing.getMessage());
    assertThrows(
        NoSuchNamespaceException.class,
        () -> rest.setProperties(NS, Collections.singletonMap("k", "v")));
    assertThrows(
        NoSuchNamespaceException.class,
        () -> rest.removeProperties(NS, Collections.singleton("k")));
  }

  /** Whether this Iceberg line's client has an error handler of its own for a namespace drop. */
  private static boolean clientHasDropNamespaceErrors() {
    try {
      Class.forName("org.apache.iceberg.rest.ErrorHandlers").getMethod("dropNamespaceErrorHandler");
      return true;
    } catch (ReflectiveOperationException e) {
      return false;
    }
  }

  @Test
  void aNamespaceThatHoldsATableIsNotDroppedAndCannotBeCreated() {
    Namespace tabled = Namespace.of("irc_rest_ns_tabled");
    TableIdentifier table = TableIdentifier.of(tabled, "t");
    rest.createTable(
        table,
        new Schema(Types.NestedField.required(1, "id", Types.LongType.get())),
        PartitionSpec.unpartitioned());
    try {
      assertThrows(AlreadyExistsException.class, () -> rest.createNamespace(tabled));
      // The service answers 409 NamespaceNotEmptyException. Iceberg 1.12's client raises that
      // type; 1.5.2's maps every 409 of a namespace route to AlreadyExistsException.
      Class<? extends RuntimeException> expected =
          clientHasDropNamespaceErrors()
              ? NamespaceNotEmptyException.class
              : AlreadyExistsException.class;
      RuntimeException notEmpty = assertThrows(expected, () -> rest.dropNamespace(tabled));
      assertEquals("Namespace irc_rest_ns_tabled is not empty", notEmpty.getMessage());
      assertTrue(rest.tableExists(table));
    } finally {
      rest.dropTable(table);
    }
    assertFalse(rest.dropNamespace(tabled));
  }
}
