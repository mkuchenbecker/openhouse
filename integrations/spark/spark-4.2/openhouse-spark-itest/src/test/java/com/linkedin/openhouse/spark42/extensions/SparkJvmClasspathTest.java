package com.linkedin.openhouse.spark42.extensions;

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
import org.apache.spark.sql.connector.catalog.CatalogPlugin;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The Spark 4.2 JVM has no OpenHouse catalog or client class. The OpenHouse SQL extensions jar is
 * OpenHouse code and is on the classpath, so the check is no longer "nothing of OpenHouse": every
 * OpenHouse class on the classpath is in that jar (the extensions and the capabilities module) or
 * is one of these tests, and none of them is a catalog, an OpenHouse client, the generated client,
 * or the service. Nothing of Spring is on the classpath at all.
 */
public class SparkJvmClasspathTest {

  private static final List<String> EXTENSIONS_PACKAGES =
      Arrays.asList(
          "com/linkedin/openhouse/spark/extensions/",
          "com/linkedin/openhouse/spark/sql/",
          "com/linkedin/openhouse/spark/capabilities/",
          "com/linkedin/openhouse/capabilities/");

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
  void theOnlyOpenHouseClassesAreTheSqlExtensionsAndTheseTests() throws Exception {
    TreeMap<String, TreeSet<String>> byOrigin = classesByOrigin();
    List<String> extensionsJars =
        byOrigin.keySet().stream()
            .filter(origin -> origin.endsWith(".jar"))
            .collect(Collectors.toList());
    Assertions.assertEquals(1, extensionsJars.size(), byOrigin.keySet().toString());
    Assertions.assertTrue(
        Paths.get(extensionsJars.get(0))
            .getFileName()
            .toString()
            .matches("openhouse-spark-4\\.2-runtime_2\\.13(-.+)?-uber\\.jar"),
        extensionsJars.get(0));
    for (String name : byOrigin.get(extensionsJars.get(0))) {
      Assertions.assertTrue(
          EXTENSIONS_PACKAGES.stream().anyMatch(name::startsWith), name + " in the extensions jar");
    }
    for (String origin : byOrigin.keySet()) {
      if (!origin.endsWith(".jar")) {
        for (String name : byOrigin.get(origin)) {
          Assertions.assertTrue(
              name.startsWith("com/linkedin/openhouse/spark42/"), name + " in " + origin);
        }
      }
    }
  }

  @Test
  void noOpenHouseClassIsACatalog() throws Exception {
    TreeMap<String, TreeSet<String>> byOrigin = classesByOrigin();
    for (TreeSet<String> names : byOrigin.values()) {
      for (String name : names) {
        if (name.startsWith("com/linkedin/openhouse/spark42/")) {
          continue;
        }
        Class<?> loaded =
            Class.forName(
                name.substring(0, name.length() - ".class".length()).replace('/', '.'),
                false,
                getClass().getClassLoader());
        Assertions.assertFalse(
            org.apache.iceberg.catalog.Catalog.class.isAssignableFrom(loaded), loaded.getName());
        Assertions.assertFalse(CatalogPlugin.class.isAssignableFrom(loaded), loaded.getName());
      }
    }
  }

  @Test
  void noOpenHouseCatalogClientOrServiceClassLoads() {
    for (String name :
        Arrays.asList(
            "com.linkedin.openhouse.javaclient.OpenHouseCatalog",
            "com.linkedin.openhouse.javaclient.api.SupportsGrantRevoke",
            "com.linkedin.openhouse.spark.OpenHouseCatalog",
            "com.linkedin.openhouse.spark.OpenHouseRestCatalog",
            "com.linkedin.openhouse.gen.tables.client.model.CreateUpdateLockRequestBody",
            "com.linkedin.openhouse.tables.client.api.TableApi",
            "com.linkedin.openhouse.tables.api.irc.IcebergRestCatalogController",
            "org.springframework.web.reactive.function.client.WebClient")) {
      Assertions.assertThrows(ClassNotFoundException.class, () -> Class.forName(name), name);
    }
  }
}
