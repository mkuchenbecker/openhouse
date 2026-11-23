package com.linkedin.openhouse.spark42.smoke;

import java.util.Arrays;
import java.util.Collections;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * {@code ALTER TABLE ... RENAME TO} with every kind of target, from Spark 4.2 over IRC, against the
 * service: what the statement answers and which table the service has afterwards ({@link
 * SmokeTestService#exists}, through the OpenHouse Java client in the service's JVM).
 *
 * <p>The smoke suite has the two targets its baseline has (s13b: the catalog's own name; s29:
 * another catalog's). These are the rest of what {@code OpenHouseCatalog.renameTable} did with a
 * target on Spark 3.5, each run there with the OpenHouse catalog for comparison (N6 record): a
 * catalog that is not named {@code openhouse}, a database that is named like the catalog, more than
 * two namespace levels, and the targets that name no catalog. The SQL extensions read the target
 * ({@code ResolveRenameTableTarget}); the unit tests of the rule are {@code RenameTableTargetTest}
 * in the runtime module.
 */
public class SparkIrcRenameTargetTest {

  private static final String DB = "irc42r";
  private static final String UNSUPPORTED = "java.lang.UnsupportedOperationException";

  private static void sql(String statement) throws Exception {
    SmokeSpark.session().sql(statement).collectAsList();
  }

  /** Creates {@code openhouse.<db>.<table>} anew and drops the names it will be renamed to. */
  private static String fresh(String db, String table, String... targets) throws Exception {
    for (String target : targets) {
      sql("DROP TABLE IF EXISTS " + SmokeSpark.OPENHOUSE + "." + db + "." + target);
    }
    String name = SmokeSpark.OPENHOUSE + "." + db + "." + table;
    sql("DROP TABLE IF EXISTS " + name);
    sql("CREATE TABLE " + name + " (id bigint)");
    return name;
  }

  private static boolean exists(String db, String table) throws Exception {
    return SmokeTestService.get().exists(db + "." + table);
  }

  private static Throwable failure(String statement) throws Exception {
    SparkSession spark = SmokeSpark.session();
    return Assertions.assertThrows(Throwable.class, () -> spark.sql(statement).collectAsList());
  }

  private static void assertFailure(Throwable failure, String className, String message) {
    Assertions.assertEquals(className, failure.getClass().getName(), String.valueOf(failure));
    Assertions.assertEquals(message, failure.getMessage());
  }

  @Test
  void theCatalogsOwnNameInTheTargetIsNotANamespaceLevel() throws Exception {
    String t = fresh(DB, "own", "own2");
    sql("INSERT INTO " + t + " VALUES (1)");

    sql("ALTER TABLE " + t + " RENAME TO openhouse." + DB + ".own2");

    Assertions.assertFalse(exists(DB, "own"));
    Assertions.assertEquals(
        Collections.singletonList("1"), SmokeTestService.get().stored(DB + ".own2").rows());
  }

  /** The name that is taken off is the session's name for the catalog, whatever it is. */
  @Test
  void theNameIsTheOneTheSessionGivesTheCatalog() throws Exception {
    fresh(DB, "named", "named2", "named3");
    String views = SmokeSpark.OPENHOUSE_VIEWS + "." + DB;

    // The same service under another catalog name: that name is the catalog's own.
    sql("ALTER TABLE " + views + ".named RENAME TO " + views + ".named2");
    Assertions.assertFalse(exists(DB, "named"));
    Assertions.assertTrue(exists(DB, "named2"));

    // And "openhouse" is then another catalog's name, though it is the same service.
    assertFailure(
        failure("ALTER TABLE " + views + ".named2 RENAME TO openhouse." + DB + ".named3"),
        UNSUPPORTED,
        "Cannot rename tables across catalogs: from="
            + views
            + ".named2, to=openhouse."
            + DB
            + ".named3");
    Assertions.assertTrue(exists(DB, "named2"));
    Assertions.assertFalse(exists(DB, "named3"));
  }

  /**
   * A database named like the catalog. A target namespace of one level is a database, so {@code
   * openhouse.<table>} is a table of the database {@code openhouse}; a target namespace of two
   * levels is {@code <catalog>.<database>}, so {@code openhouse.openhouse.<table>} is the same
   * table. As with the OpenHouse catalog on Spark 3.5.
   */
  @Test
  void aDatabaseNamedLikeTheCatalogIsADatabaseInATargetOfOneLevel() throws Exception {
    String inside = fresh("openhouse", "amb", "amb2", "amb3");

    sql("ALTER TABLE " + inside + " RENAME TO openhouse.amb2");
    Assertions.assertFalse(exists("openhouse", "amb"));
    Assertions.assertTrue(exists("openhouse", "amb2"));

    sql("ALTER TABLE openhouse.openhouse.amb2 RENAME TO openhouse.openhouse.amb3");
    Assertions.assertFalse(exists("openhouse", "amb2"));
    Assertions.assertTrue(exists("openhouse", "amb3"));

    // From another database the one-level target is still the database openhouse: the service
    // answers as for any rename into another database, and nothing is renamed.
    String outside = fresh(DB, "amb_out");
    Throwable refused = failure("ALTER TABLE " + outside + " RENAME TO openhouse.amb_out");
    Assertions.assertTrue(
        String.valueOf(refused.getMessage())
            .contains(
                "Rename table across databases is not supported. Cannot rename table amb_out from "
                    + DB
                    + " to openhouse"),
        String.valueOf(refused));
    Assertions.assertTrue(exists(DB, "amb_out"));
    Assertions.assertFalse(exists("openhouse", "amb_out"));
  }

  @Test
  void aTargetNamespaceOfMoreThanTwoLevelsIsRefusedInTheEngine() throws Exception {
    String t = fresh(DB, "levels");
    for (String namespace :
        Arrays.asList("openhouse." + DB + ".extra", "other_catalog." + DB + ".extra")) {
      assertFailure(
          failure("ALTER TABLE " + t + " RENAME TO " + namespace + ".levels2"),
          "org.apache.iceberg.exceptions.ValidationException",
          "Namespace has unexpected levels " + namespace);
    }
    Assertions.assertTrue(exists(DB, "levels"));
  }

  @Test
  void aTargetThatNamesNoCatalogWorksAsBefore() throws Exception {
    String t = fresh(DB, "plain", "plain2", "plain3");

    sql("ALTER TABLE " + t + " RENAME TO " + DB + ".plain2");
    Assertions.assertFalse(exists(DB, "plain"));
    Assertions.assertTrue(exists(DB, "plain2"));

    // Spark gives a target without a namespace the table's namespace.
    sql("ALTER TABLE openhouse." + DB + ".plain2 RENAME TO plain3");
    Assertions.assertFalse(exists(DB, "plain2"));
    Assertions.assertTrue(exists(DB, "plain3"));
  }

  /** Iceberg's Hadoop catalog has nothing of OpenHouse: its own answers, for any target. */
  @Test
  void aCatalogThatIsNotOpenHouseGetsItsOwnAnswer() throws Exception {
    String t = SmokeSpark.LOCAL + ".rename_db.t";
    sql("DROP TABLE IF EXISTS " + t);
    sql("CREATE TABLE " + t + " (id bigint) USING iceberg");
    for (String target :
        Arrays.asList(SmokeSpark.LOCAL + ".rename_db.t2", "other_catalog.rename_db.t2")) {
      assertFailure(
          failure("ALTER TABLE " + t + " RENAME TO " + target),
          UNSUPPORTED,
          "Cannot rename Hadoop tables");
    }
  }
}
