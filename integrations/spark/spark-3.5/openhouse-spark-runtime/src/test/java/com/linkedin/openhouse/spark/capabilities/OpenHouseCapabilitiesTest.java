package com.linkedin.openhouse.spark.capabilities;

import com.linkedin.openhouse.capabilities.CapabilityNames;
import com.linkedin.openhouse.capabilities.rest.RestCapabilityClient;
import com.linkedin.openhouse.spark.capabilities.CatalogCapabilities.Path;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.hadoop.HadoopCatalog;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.spark.SparkCatalog;
import org.apache.iceberg.spark.SparkSessionCatalog;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.connector.catalog.CatalogPlugin;
import org.apache.spark.sql.connector.catalog.TableCatalog;
import org.apache.spark.sql.util.CaseInsensitiveStringMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

/**
 * {@link OpenHouseCapabilities} on each catalog path, for each capability, through Iceberg's own
 * {@link SparkCatalog} and {@link SparkSessionCatalog} as Spark builds them from configuration.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenHouseCapabilitiesTest {
  private static final Set<String> ALL =
      new LinkedHashSet<>(
          Arrays.asList(CapabilityNames.POLICIES, CapabilityNames.GRANTS, CapabilityNames.UNLOCK));

  private SparkSession spark;
  private String warehouse;

  @BeforeAll
  void startSpark() throws Exception {
    spark = SparkSession.builder().master("local[1]").getOrCreate();
    warehouse = Files.createTempDirectory("capabilities").toString();
  }

  @AfterAll
  void stopSpark() {
    spark.close();
  }

  private static Map<String, String> openHouse(String catalogImpl) {
    Map<String, String> properties = new HashMap<>();
    properties.put("catalog-impl", catalogImpl);
    properties.put("uri", "http://localhost:1");
    properties.put("cluster", "local-cluster");
    return properties;
  }

  private Map<String, String> rest(String clientSide, String serverAdvertises) {
    Map<String, String> properties = new HashMap<>();
    properties.put("catalog-impl", FakeRestCatalog.class.getName());
    properties.put("uri", "http://localhost:1");
    if (clientSide != null) {
      properties.put(CapabilityNames.ADVERTISED_PROPERTY, clientSide);
    }
    if (serverAdvertises != null) {
      properties.put(FakeRestCatalog.SERVER_ADVERTISES, serverAdvertises);
    }
    return properties;
  }

  private Map<String, String> hadoop(String catalogImpl) {
    Map<String, String> properties = new HashMap<>();
    if (catalogImpl == null) {
      properties.put("type", "hadoop");
    } else {
      properties.put("catalog-impl", catalogImpl);
    }
    properties.put("warehouse", warehouse);
    return properties;
  }

  private static SparkCatalog sparkCatalog(String name, Map<String, String> properties) {
    SparkCatalog catalog = new SparkCatalog();
    catalog.initialize(name, new CaseInsensitiveStringMap(properties));
    return catalog;
  }

  private static SparkCatalog sparkCatalog(
      String name, Map<String, String> properties, boolean cacheEnabled) {
    Map<String, String> withCache = new HashMap<>(properties);
    withCache.put("cache-enabled", String.valueOf(cacheEnabled));
    return sparkCatalog(name, withCache);
  }

  private static void assertUnsupportedGrantsAndUnlock(CatalogPlugin catalog) {
    UnsupportedOperationException grants =
        Assertions.assertThrows(
            UnsupportedOperationException.class,
            () -> OpenHouseCapabilities.requireGrants(catalog));
    Assertions.assertEquals(
        "Catalog '" + catalog.name() + "' does not support Grant Revoke Statements",
        grants.getMessage());
    UnsupportedOperationException unlock =
        Assertions.assertThrows(
            UnsupportedOperationException.class,
            () -> OpenHouseCapabilities.requireUnlock(catalog));
    Assertions.assertEquals(
        "Catalog '" + catalog.name() + "' does not support UNLOCK", unlock.getMessage());
  }

  // ------------------------------------------------------------------ legacy and in-process

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void legacyOpenHouseCatalogOffersEverythingThroughItsInterfaces(boolean cacheEnabled) {
    SparkCatalog catalog =
        sparkCatalog(
            "openhouse",
            openHouse(com.linkedin.openhouse.spark.OpenHouseCatalog.class.getName()),
            cacheEnabled);
    CatalogCapabilities capabilities = OpenHouseCapabilities.resolve(catalog);
    Assertions.assertEquals(Path.OPENHOUSE_CATALOG, capabilities.path());
    Assertions.assertEquals(ALL, capabilities.offered());
    Assertions.assertTrue(capabilities.advertised().isEmpty());
    Assertions.assertNotNull(OpenHouseCapabilities.requireGrants(catalog));
    Assertions.assertNotNull(OpenHouseCapabilities.requireUnlock(catalog));
    OpenHouseCapabilities.requirePolicies(catalog);
    Assertions.assertTrue(
        OpenHouseCapabilities.icebergCatalog(catalog)
            instanceof com.linkedin.openhouse.spark.OpenHouseCatalog);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void inProcessRestCatalogIsFoundByItsInterfacesBeforeItsProperties(boolean cacheEnabled) {
    // It is a RESTCatalog, and its config advertises nothing: the interfaces win.
    SparkCatalog catalog =
        sparkCatalog(
            "openhouse",
            openHouse(com.linkedin.openhouse.spark.OpenHouseRestCatalog.class.getName()),
            cacheEnabled);
    Catalog iceberg = OpenHouseCapabilities.icebergCatalog(catalog);
    Assertions.assertTrue(iceberg instanceof RESTCatalog);
    Assertions.assertTrue(iceberg instanceof com.linkedin.openhouse.spark.OpenHouseRestCatalog);
    CatalogCapabilities capabilities = OpenHouseCapabilities.resolve(catalog);
    Assertions.assertEquals(Path.OPENHOUSE_CATALOG, capabilities.path());
    Assertions.assertEquals(ALL, capabilities.offered());
    OpenHouseCapabilities.requirePolicies(catalog);
  }

  @Test
  void aCatalogWithOnlyOneInterfaceOffersOnlyThatHandle() {
    SparkCatalog catalog =
        sparkCatalog("unlock_only", hadoop(CapabilityTestCatalogs.UnlockOnly.class.getName()));
    CatalogCapabilities capabilities = OpenHouseCapabilities.resolve(catalog);
    Assertions.assertEquals(Path.OPENHOUSE_CATALOG, capabilities.path());
    Assertions.assertEquals(
        new LinkedHashSet<>(Arrays.asList(CapabilityNames.POLICIES, CapabilityNames.UNLOCK)),
        capabilities.offered());
    Assertions.assertNotNull(OpenHouseCapabilities.requireUnlock(catalog));
    UnsupportedOperationException grants =
        Assertions.assertThrows(
            UnsupportedOperationException.class,
            () -> OpenHouseCapabilities.requireGrants(catalog));
    Assertions.assertEquals(
        "Catalog 'unlock_only' does not support Grant Revoke Statements", grants.getMessage());
  }

  @Test
  void aCatalogImplementingTheCapabilityInterfacesIsUsedDirectly() {
    SparkCatalog catalog =
        sparkCatalog("native", hadoop(CapabilityTestCatalogs.Native.class.getName()));
    Catalog iceberg = OpenHouseCapabilities.icebergCatalog(catalog);
    CatalogCapabilities capabilities = OpenHouseCapabilities.resolve(catalog);
    Assertions.assertEquals(Path.OPENHOUSE_CATALOG, capabilities.path());
    Assertions.assertEquals(ALL, capabilities.offered());
    Assertions.assertSame(iceberg, capabilities.grants().get());
    Assertions.assertSame(iceberg, capabilities.unlock().get());
  }

  // ------------------------------------------------------------------ Iceberg's RESTCatalog

  @Test
  void restCatalogAdvertisingNothingOffersNothing() {
    SparkCatalog catalog = sparkCatalog("irc", rest(null, null));
    CatalogCapabilities capabilities = OpenHouseCapabilities.resolve(catalog);
    Assertions.assertEquals(Path.ICEBERG_REST, capabilities.path());
    Assertions.assertTrue(capabilities.offered().isEmpty());
    Assertions.assertTrue(capabilities.advertised().isEmpty());
    UnsupportedOperationException policies =
        Assertions.assertThrows(
            UnsupportedOperationException.class,
            () -> OpenHouseCapabilities.requirePolicies(catalog));
    Assertions.assertEquals(
        "Catalog 'irc' does not support OpenHouse policies: its server does not advertise"
            + " 'policies' in openhouse.capabilities (advertised: [])",
        policies.getMessage());
    assertUnsupportedGrantsAndUnlock(catalog);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void restCatalogWhoseServerAdvertisesPoliciesOffersPolicies(boolean cacheEnabled) {
    SparkCatalog catalog = sparkCatalog("irc", rest(null, "policies"), cacheEnabled);
    Assertions.assertTrue(OpenHouseCapabilities.icebergCatalog(catalog) instanceof RESTCatalog);
    CatalogCapabilities capabilities = OpenHouseCapabilities.resolve(catalog);
    Assertions.assertEquals(Path.ICEBERG_REST, capabilities.path());
    Assertions.assertEquals(Collections.singleton("policies"), capabilities.advertised());
    Assertions.assertEquals(Collections.singleton("policies"), capabilities.offered());
    OpenHouseCapabilities.requirePolicies(catalog);
    assertUnsupportedGrantsAndUnlock(catalog);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void restCatalogAdvertisingGrantsAndUnlockOffersThemThroughTheCapabilityClient(
      boolean cacheEnabled) {
    SparkCatalog catalog =
        sparkCatalog("irc", rest(null, " Policies, GRANTS ,unlock,metrics"), cacheEnabled);
    CatalogCapabilities capabilities = OpenHouseCapabilities.resolve(catalog);
    Assertions.assertEquals(Path.ICEBERG_REST, capabilities.path());
    Assertions.assertEquals(
        new LinkedHashSet<>(Arrays.asList("policies", "grants", "unlock", "metrics")),
        capabilities.advertised());
    Assertions.assertEquals(ALL, capabilities.offered());
    Assertions.assertTrue(capabilities.grants().get() instanceof RestCapabilityClient);
    Assertions.assertTrue(capabilities.unlock().get() instanceof RestCapabilityClient);
    Assertions.assertTrue(
        OpenHouseCapabilities.requireGrants(catalog) instanceof RestCapabilityClient);
    Assertions.assertTrue(
        OpenHouseCapabilities.requireUnlock(catalog) instanceof RestCapabilityClient);
    OpenHouseCapabilities.requirePolicies(catalog);
  }

  @Test
  void theCapabilityClientIsBuiltFromTheCatalogsOwnMergedProperties() {
    // The same URI, prefix, token and header.* values as the catalog, after the server's config
    // overrides are merged in: the statements authenticate as the session's catalog does.
    Map<String, String> properties = rest(null, "grants,unlock");
    properties.put("token", "the-token");
    properties.put("prefix", "warehouse-1");
    properties.put("header.X-Client-Name", "spark-sql");
    SparkCatalog catalog = sparkCatalog("irc", properties);
    RESTCatalog iceberg = (RESTCatalog) OpenHouseCapabilities.icebergCatalog(catalog);
    RestCapabilityClient grants =
        (RestCapabilityClient) OpenHouseCapabilities.requireGrants(catalog);
    RestCapabilityClient unlock =
        (RestCapabilityClient) OpenHouseCapabilities.requireUnlock(catalog);
    Assertions.assertEquals(iceberg.properties(), grants.properties());
    Assertions.assertEquals(iceberg.properties(), unlock.properties());
    Assertions.assertEquals("grants,unlock", grants.properties().get("openhouse.capabilities"));
    Assertions.assertEquals("the-token", grants.properties().get("token"));
    Assertions.assertEquals("warehouse-1", grants.properties().get("prefix"));
    Assertions.assertEquals("spark-sql", grants.properties().get("header.X-Client-Name"));
  }

  @Test
  void eachCapabilityIsOfferedOnlyWhereItIsAdvertised() {
    SparkCatalog grantsOnly = sparkCatalog("irc", rest(null, "policies,grants"));
    Assertions.assertEquals(
        new LinkedHashSet<>(Arrays.asList("policies", "grants")),
        OpenHouseCapabilities.resolve(grantsOnly).offered());
    Assertions.assertNotNull(OpenHouseCapabilities.requireGrants(grantsOnly));
    Assertions.assertEquals(
        "Catalog 'irc' does not support UNLOCK",
        Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> OpenHouseCapabilities.requireUnlock(grantsOnly))
            .getMessage());

    SparkCatalog unlockOnly = sparkCatalog("irc", rest(null, "unlock"));
    Assertions.assertEquals(
        Collections.singleton("unlock"), OpenHouseCapabilities.resolve(unlockOnly).offered());
    Assertions.assertNotNull(OpenHouseCapabilities.requireUnlock(unlockOnly));
    Assertions.assertEquals(
        "Catalog 'irc' does not support Grant Revoke Statements",
        Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> OpenHouseCapabilities.requireGrants(unlockOnly))
            .getMessage());
  }

  @Test
  void restCatalogAdvertisingOnlyOtherNamesRefusesPolicies() {
    SparkCatalog catalog = sparkCatalog("irc", rest(null, "grants,unlock"));
    UnsupportedOperationException policies =
        Assertions.assertThrows(
            UnsupportedOperationException.class,
            () -> OpenHouseCapabilities.requirePolicies(catalog));
    Assertions.assertTrue(
        policies.getMessage().endsWith("(advertised: [grants, unlock])"), policies.getMessage());
    Assertions.assertEquals(
        new LinkedHashSet<>(Arrays.asList("grants", "unlock")),
        OpenHouseCapabilities.resolve(catalog).offered());
  }

  @Test
  void theCatalogPropertiesAreWhatIsRead() {
    // RESTCatalog merges the server's overrides over the client's own properties; the resolver
    // reads the merged map, so a client-side value counts only when the server sends none.
    Assertions.assertTrue(
        OpenHouseCapabilities.resolve(sparkCatalog("irc", rest("policies", null)))
            .offers(CapabilityNames.POLICIES));
    Assertions.assertFalse(
        OpenHouseCapabilities.resolve(sparkCatalog("irc", rest("policies", "grants")))
            .offers(CapabilityNames.POLICIES));
  }

  // ------------------------------------------------------------------ other catalogs

  @Test
  void otherIcebergCatalogsOfferOnlyWhatTheyAlwaysAllowed() {
    SparkCatalog catalog = sparkCatalog("hadoop", hadoop(null));
    Assertions.assertTrue(OpenHouseCapabilities.icebergCatalog(catalog) instanceof HadoopCatalog);
    CatalogCapabilities capabilities = OpenHouseCapabilities.resolve(catalog);
    Assertions.assertEquals(Path.OTHER_ICEBERG, capabilities.path());
    // Policy statements on such a catalog keep refusing tables without openhouse.tableId
    // themselves, as before.
    Assertions.assertEquals(Collections.singleton("policies"), capabilities.offered());
    OpenHouseCapabilities.requirePolicies(catalog);
    assertUnsupportedGrantsAndUnlock(catalog);
  }

  @Test
  void sessionCatalogIsUnwrappedToTheCatalogItWraps() {
    SparkSessionCatalog<?> catalog = new SparkSessionCatalog<>();
    catalog.initialize(
        "spark_catalog",
        new CaseInsensitiveStringMap(
            openHouse(com.linkedin.openhouse.spark.OpenHouseCatalog.class.getName())));
    Assertions.assertTrue(
        OpenHouseCapabilities.icebergCatalog(catalog)
            instanceof com.linkedin.openhouse.spark.OpenHouseCatalog);
    Assertions.assertEquals(ALL, OpenHouseCapabilities.resolve(catalog).offered());

    SparkSessionCatalog<?> hadoopSession = new SparkSessionCatalog<>();
    hadoopSession.initialize("spark_catalog", new CaseInsensitiveStringMap(hadoop(null)));
    Assertions.assertEquals(
        Path.OTHER_ICEBERG, OpenHouseCapabilities.resolve(hadoopSession).path());
  }

  // ------------------------------------------------------------------ OpenHouse over REST

  @Test
  void anOpenHouseRestCatalogIsIcebergsRestCatalogOnAServerThatAdvertisesACapability() {
    // By what the catalog's merged properties advertise, whichever names those are and whether or
    // not this client knows them.
    for (boolean cacheEnabled : new boolean[] {true, false}) {
      Assertions.assertTrue(
          OpenHouseCapabilities.resolve(sparkCatalog("irc", rest(null, "policies"), cacheEnabled))
              .isOpenHouseRest());
    }
    Assertions.assertTrue(
        OpenHouseCapabilities.resolve(sparkCatalog("irc", rest(null, "metrics")))
            .isOpenHouseRest());
    Assertions.assertTrue(
        OpenHouseCapabilities.resolve(sparkCatalog("irc", rest("policies", null)))
            .isOpenHouseRest());

    // A REST server that advertises none is not an OpenHouse server, whatever the client claims.
    Assertions.assertFalse(
        OpenHouseCapabilities.resolve(sparkCatalog("irc", rest(null, null))).isOpenHouseRest());
    Assertions.assertFalse(
        OpenHouseCapabilities.resolve(sparkCatalog("irc", rest(null, " , "))).isOpenHouseRest());
    Assertions.assertFalse(
        OpenHouseCapabilities.resolve(sparkCatalog("irc", rest("policies", ""))).isOpenHouseRest());

    // An OpenHouse catalog class is not Iceberg's REST catalog: it has its own behavior. That
    // holds for the in-process OpenHouseRestCatalog too, though it is a RESTCatalog.
    for (String catalogClass :
        Arrays.asList(
            com.linkedin.openhouse.spark.OpenHouseCatalog.class.getName(),
            com.linkedin.openhouse.spark.OpenHouseRestCatalog.class.getName())) {
      Assertions.assertFalse(
          OpenHouseCapabilities.resolve(sparkCatalog("openhouse", openHouse(catalogClass)))
              .isOpenHouseRest(),
          catalogClass);
    }
    Assertions.assertFalse(
        OpenHouseCapabilities.resolve(
                sparkCatalog("native", hadoop(CapabilityTestCatalogs.Native.class.getName())))
            .isOpenHouseRest());
    // Nor is any other catalog, though policy statements are not refused on an Iceberg one.
    Assertions.assertFalse(
        OpenHouseCapabilities.resolve(sparkCatalog("hadoop", hadoop(null))).isOpenHouseRest());
    TableCatalog other = Mockito.mock(TableCatalog.class);
    Mockito.when(other.name()).thenReturn("spark_catalog");
    Assertions.assertFalse(OpenHouseCapabilities.resolve(other).isOpenHouseRest());
  }

  @Test
  void aNonIcebergCatalogOffersNothing() {
    TableCatalog catalog = Mockito.mock(TableCatalog.class);
    Mockito.when(catalog.name()).thenReturn("spark_catalog");
    Assertions.assertNull(OpenHouseCapabilities.icebergCatalog(catalog));
    CatalogCapabilities capabilities = OpenHouseCapabilities.resolve(catalog);
    Assertions.assertEquals(Path.NOT_ICEBERG, capabilities.path());
    Assertions.assertTrue(capabilities.offered().isEmpty());
    assertUnsupportedGrantsAndUnlock(catalog);
  }
}
