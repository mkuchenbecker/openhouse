package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.model.TableModelConstants.HEALTH_SCHEMA_LITERAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.linkedin.openhouse.common.schema.IcebergSchemaHelper;
import com.linkedin.openhouse.common.security.DummyTokenInterceptor.DummySecurityJWT;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import java.util.HashMap;
import java.util.Map;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.BadRequestException;
import org.apache.iceberg.rest.RESTCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;

/**
 * An unmodified Iceberg {@link RESTCatalog}, over HTTP, setting {@code updated.openhouse.policy}
 * with {@code updateProperties}, as OpenHouse's Spark policy statements do: refused with Iceberg's
 * {@link BadRequestException} and the message that names the key (see {@link
 * IrcWritePolicyKeyTest}), and nothing stored.
 */
@SpringBootTest(
    classes = SpringH2Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = {PropertyOverrideContextInitializer.class})
@Import(IrcReadRoutesTest.StubDefaults.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class IrcRestCatalogPolicyKeyTest {
  private static final TableIdentifier ID = TableIdentifier.of("irc_pk_rest", "t");

  @LocalServerPort private int port;

  private RESTCatalog rest;

  @BeforeEach
  void start() throws Exception {
    Map<String, String> properties = new HashMap<>();
    properties.put("uri", "http://localhost:" + port + "/iceberg");
    properties.put("token", new DummySecurityJWT("irc_writer").buildNoopJWT());
    rest =
        (RESTCatalog)
            CatalogUtil.loadCatalog(
                RESTCatalog.class.getName(), "openhouse", properties, new Configuration());
    rest.createTable(ID, IcebergSchemaHelper.getSchemaFromSchemaJson(HEALTH_SCHEMA_LITERAL));
  }

  @AfterEach
  void stop() throws Exception {
    try {
      rest.dropTable(ID);
    } finally {
      rest.close();
    }
  }

  @Test
  void updatePropertiesWithThePolicyKeyIsRefused() {
    Table table = rest.loadTable(ID);
    Map<String, String> before = new HashMap<>(table.properties());
    BadRequestException refused =
        assertThrows(
            BadRequestException.class,
            () ->
                table
                    .updateProperties()
                    .set(IrcWritePolicyKeyTest.KEY, IrcWritePolicyKeyTest.PATCH)
                    .commit());
    IrcWritePolicyKeyTest.assertPolicyKeyRefusal(refused.getMessage());
    Table reloaded = rest.loadTable(ID);
    assertFalse(reloaded.properties().containsKey(IrcWritePolicyKeyTest.KEY));
    assertEquals(before, new HashMap<>(reloaded.properties()));
  }
}
