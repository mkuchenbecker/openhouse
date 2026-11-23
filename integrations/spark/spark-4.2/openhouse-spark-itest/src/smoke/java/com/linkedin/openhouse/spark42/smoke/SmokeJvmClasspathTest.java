package com.linkedin.openhouse.spark42.smoke;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The JVM the smoke suite runs Spark in is the target platform: of OpenHouse it has the SQL
 * extensions' uber jar (the extensions and the capabilities module) and these tests, and nothing
 * else. No OpenHouse catalog, no OpenHouse Java client, no generated client, no service class and
 * nothing of Spring is on its classpath, and every catalog of the session is Iceberg's own. The
 * same claims as {@code SparkJvmClasspathTest} makes for the SQL-extensions tests' JVM.
 */
public class SmokeJvmClasspathTest {

  private static final List<String> EXTENSIONS_PACKAGES =
      Arrays.asList(
          "com/linkedin/openhouse/spark/extensions/",
          "com/linkedin/openhouse/spark/sql/",
          "com/linkedin/openhouse/spark/capabilities/",
          "com/linkedin/openhouse/capabilities/");

  /** The smoke suite's own classes: what runs it here, and its statements and baseline. */
  private static final List<String> SMOKE_PACKAGES =
      Arrays.asList("com/linkedin/openhouse/spark42/smoke/", "com/linkedin/openhouse/spark/smoke/");

  /** Every class under {@code com/linkedin/openhouse} or {@code org/springframework}, by origin. */
  private static TreeMap<String, TreeSet<String>> classesByOrigin() throws IOException {
    TreeMap<String, TreeSet<String>> byOrigin = new TreeMap<>();
    for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
      Path path = Paths.get(entry);
      List<String> names = new ArrayList<>();
      if (Files.isDirectory(path)) {
        try (Stream<Path> files = Files.walk(path)) {
          files
              .filter(f -> f.toString().endsWith(".class"))
              .forEach(
                  f -> names.add(path.relativize(f).toString().replace(File.separatorChar, '/')));
        }
      } else if (entry.endsWith(".jar") && Files.exists(path)) {
        try (JarFile jar = new JarFile(path.toFile())) {
          Enumeration<JarEntry> entries = jar.entries();
          while (entries.hasMoreElements()) {
            names.add(entries.nextElement().getName());
          }
        }
      }
      for (String name : names) {
        if (name.endsWith(".class")
            && (name.startsWith("com/linkedin/openhouse/")
                || name.startsWith("org/springframework/"))) {
          byOrigin.computeIfAbsent(entry, k -> new TreeSet<>()).add(name);
        }
      }
    }
    return byOrigin;
  }

  @Test
  void theOnlyOpenHouseClassesAreTheSqlExtensionsAndTheSmokeSuite() throws Exception {
    TreeMap<String, TreeSet<String>> byOrigin = classesByOrigin();
    List<String> jars =
        byOrigin.keySet().stream()
            .filter(origin -> origin.endsWith(".jar"))
            .collect(Collectors.toList());
    Assertions.assertEquals(1, jars.size(), byOrigin.keySet().toString());
    Assertions.assertTrue(
        Paths.get(jars.get(0))
            .getFileName()
            .toString()
            .matches("openhouse-spark-4\\.2-runtime_2\\.13(-.+)?-uber\\.jar"),
        jars.get(0));
    for (String name : byOrigin.get(jars.get(0))) {
      Assertions.assertTrue(
          EXTENSIONS_PACKAGES.stream().anyMatch(name::startsWith), name + " in the extensions jar");
    }
    for (String origin : byOrigin.keySet()) {
      if (!origin.endsWith(".jar")) {
        for (String name : byOrigin.get(origin)) {
          Assertions.assertTrue(
              SMOKE_PACKAGES.stream().anyMatch(name::startsWith), name + " in " + origin);
        }
      }
    }
  }

  @Test
  void noOpenHouseCatalogClientOrServiceClassLoads() {
    for (String name :
        Arrays.asList(
            "com.linkedin.openhouse.javaclient.OpenHouseCatalog",
            "com.linkedin.openhouse.javaclient.api.SupportsGrantRevoke",
            "com.linkedin.openhouse.javaclient.exception.WebClientResponseWithMessageException",
            "com.linkedin.openhouse.spark.OpenHouseCatalog",
            "com.linkedin.openhouse.spark.OpenHouseRestCatalog",
            "com.linkedin.openhouse.gen.tables.client.model.CreateUpdateLockRequestBody",
            "com.linkedin.openhouse.tables.client.api.TableApi",
            "com.linkedin.openhouse.tables.api.irc.IcebergRestCatalogController",
            "org.springframework.web.reactive.function.client.WebClient")) {
      Assertions.assertThrows(ClassNotFoundException.class, () -> Class.forName(name), name);
    }
  }

  /** The catalogs the smoke statements name are Iceberg's SparkCatalog over a REST catalog. */
  @Test
  void theSmokeCatalogsAreIcebergsOwnOverRest() throws Exception {
    SparkSession spark = SmokeSpark.session();
    for (String catalog : Arrays.asList(SmokeSpark.OPENHOUSE, SmokeSpark.OPENHOUSE_VIEWS)) {
      String prefix = "spark.sql.catalog." + catalog;
      Assertions.assertEquals("org.apache.iceberg.spark.SparkCatalog", spark.conf().get(prefix));
      Assertions.assertEquals("rest", spark.conf().get(prefix + ".type"));
      Assertions.assertFalse(spark.conf().contains(prefix + ".catalog-impl"), catalog);
      Assertions.assertEquals(
          "org.apache.iceberg.spark.SparkCatalog",
          spark.sessionState().catalogManager().catalog(catalog).getClass().getName());
    }
  }
}
