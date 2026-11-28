package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.model.TableModelConstants.HEALTH_SCHEMA_LITERAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.linkedin.openhouse.common.schema.IcebergSchemaHelper;
import com.linkedin.openhouse.common.security.DummyTokenInterceptor.DummySecurityJWT;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import java.util.HashMap;
import java.util.Map;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
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
 * with {@code updateProperties}, as OpenHouse's Spark policy statements do: the policy is applied
 * as the OpenHouse client applies it and the key is not stored (see {@link IrcPolicyPatchTest}); a
 * patch a native policy rule refuses is Iceberg's {@link BadRequestException} with the native
 * message, and nothing is stored.
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
    Schema schema = IcebergSchemaHelper.getSchemaFromSchemaJson(HEALTH_SCHEMA_LITERAL);
    rest.createTable(ID, schema, PartitionSpec.builderFor(schema).hour("timestampCol").build());
  }

  @AfterEach
  void stop() throws Exception {
    try {
      rest.dropTable(ID);
    } finally {
      rest.close();
    }
  }

  /**
   * The server's capability, as a client reads it: {@code RESTCatalog.properties()} is the client's
   * configuration merged with the config response (defaults under it, overrides over it; {@code
   * RESTSessionCatalog.initialize} on Iceberg 1.5.2 and 1.12 alike).
   */
  @Test
  void theCatalogPropertiesNameThePolicyCapability() {
    assertEquals("policies,grants,unlock", rest.properties().get("openhouse.capabilities"));
  }

  @Test
  void updatePropertiesWithThePolicyKeyAppliesThePolicy() {
    Table table = rest.loadTable(ID);
    table.updateProperties().set(IrcWritePolicyKeyTest.KEY, IrcWritePolicyKeyTest.PATCH).commit();
    Table reloaded = rest.loadTable(ID);
    assertFalse(reloaded.properties().containsKey(IrcWritePolicyKeyTest.KEY));
    String policies = reloaded.properties().get("policies");
    assertTrue(policies.contains("\"count\": 3"), policies);
    assertTrue(policies.contains("\"granularity\": \"HOUR\""), policies);
  }

  @Test
  void aPatchANativeRuleRefusesIsABadRequestAndStoresNothing() {
    Table table = rest.loadTable(ID);
    Map<String, String> before = new HashMap<>(table.properties());
    BadRequestException refused =
        assertThrows(
            BadRequestException.class,
            () ->
                table
                    .updateProperties()
                    .set(
                        IrcWritePolicyKeyTest.KEY,
                        "{\"retention\":{\"count\":30,\"granularity\":\"DAY\"}}")
                    .commit());
    assertTrue(refused.getMessage().contains("retention"), refused.getMessage());
    assertEquals(before, new HashMap<>(rest.loadTable(ID).properties()));
  }
}
