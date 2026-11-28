package com.linkedin.openhouse.spark42.smoke;

import com.linkedin.openhouse.spark.smoke.SparkSqlSmokeScenarios;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.spark.sql.SparkSession;

/**
 * The Spark SQL smoke suite on the target platform: Spark 4.2 on Iceberg 1.12 through Iceberg's own
 * {@code SparkCatalog} and {@code RESTCatalog} over the service's IRC routes, with the OpenHouse
 * SQL extensions and no OpenHouse catalog or client class ({@link SmokeSpark}).
 *
 * <p>Every statement of the suite runs. {@link #differences()} is the complete list of statements
 * whose outcome is not the baseline's (Spark 3.5 with the OpenHouse catalog): {@link
 * SparkSqlSmokeScenarios} fails a statement that differs without being listed, and a listed one
 * that does not differ or that quotes another baseline than the scenario states. Each entry asserts
 * what this platform does, quotes the baseline, and says of which kind the difference is:
 *
 * <ul>
 *   <li>(a) follows from a recorded decision or from the goal itself. The baseline raises a class
 *       of the OpenHouse Java client, which this platform does not have by definition; or the
 *       statement stays refused, as decided, and only Iceberg's wording is new.
 *   <li>(b) Spark 4 does it, with or without OpenHouse: {@link Spark42WithoutOpenHouseTest} runs
 *       the same statement on Iceberg's Hadoop catalog.
 *   <li>(d) the IRC path does it and no decision covers it yet. The OpenHouse catalog class decided
 *       the baseline outcome in the engine, and that class is not here.
 * </ul>
 *
 * No statement differs because of Iceberg 1.12 alone (c), and none is a defect of the SQL
 * extensions (e). Two outcomes of the namespace statements over stored namespace records are known
 * defects of the namespace model, marked KNOWN DEFECT in their reason (a created namespace is not
 * listed; a database that holds tables has no record whose properties could be set).
 *
 * <p>A {@code RENAME TO} target that names the catalog (s13b, and the cross-catalog refusal of s29)
 * is not on the list: the OpenHouse catalog class decided those outcomes in the engine too, and the
 * SQL extensions read the target as it did ({@code ResolveRenameTableTarget}).
 */
public class Spark42IrcSparkSqlSmokeTest extends SparkSqlSmokeScenarios {

  private static final String DECIDED = "(a) decided";
  private static final String SPARK_4 = "(b) Spark 4";
  private static final String IRC_UNDECIDED = "(d) IRC path, not decided";

  private static final String UNSUPPORTED = "java.lang.UnsupportedOperationException";
  private static final String BAD_REQUEST = "org.apache.iceberg.exceptions.BadRequestException";
  private static final String JAVA_CLIENT_FAILURE =
      "com.linkedin.openhouse.javaclient.exception.WebClientResponseWithMessageException";

  @Override
  protected SparkSession sparkSession() throws Exception {
    return SmokeSpark.session();
  }

  /** The same session: the one service has a database with views enabled. */
  @Override
  protected SparkSession viewsSparkSession() throws Exception {
    return SmokeSpark.session();
  }

  /** The service keeps this run's tables in its own directory, not in {@code /tmp}. */
  @Override
  protected String storageRoot() {
    try {
      return SmokeTestService.get().warehouse();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  protected List<Difference> differences() {
    String s26 = CAT_DB + ".s26_describe";
    String s29 = CAT_DB + ".s29_rename";
    List<Difference> differences =
        new ArrayList<>(
            Arrays.asList(
                // ---- s19, s24: namespace statements.
                Difference.on("s19", "CREATE NAMESPACE openhouse.smoke_t1_new_ns")
                    .baseline(fails(UNSUPPORTED, "Describing database is not supported"))
                    .here(succeeds())
                    .because(
                        IRC_UNDECIDED,
                        "The IRC create route stores a namespace record for a name that holds"
                            + " nothing. KNOWN DEFECT: the namespace is then not listed (SHOW"
                            + " DATABASES), whose list comes from tables only: House Tables has no"
                            + " list of records."),
                // Baseline: refused. Here it answers.
                Difference.on("s24", "DESCRIBE NAMESPACE " + CAT_DB)
                    .baseline(fails(UNSUPPORTED, "Describing database is not supported"))
                    .here(rows("Catalog Name,openhouse", "Namespace Name," + DB))
                    .because(
                        IRC_UNDECIDED,
                        "The IRC namespace route answers for a database that has tables, so"
                            + " RESTCatalog loads the namespace. OpenHouseCatalog refused the call in the"
                            + " engine. Iceberg's Hadoop catalog answers the statement as well."),
                Difference.on("s24", "ALTER NAMESPACE " + CAT_DB + " SET DBPROPERTIES ('k'='v')")
                    .baseline(fails(UNSUPPORTED, "Set properties on a database is not supported"))
                    .here(
                        fails(
                            "org.apache.spark.sql.catalyst.analysis.NoSuchNamespaceException",
                            "[SCHEMA_NOT_FOUND] The schema `" + DB + "` cannot be found."))
                    .because(
                        IRC_UNDECIDED,
                        "KNOWN DEFECT, a consequence of the two existence rules: the namespace"
                            + " exists by holding tables, but its properties are those of a"
                            + " namespace record, and it has none, so the IRC update answers the"
                            + " namespace service's 404 for a missing record. No record is created"
                            + " implicitly; nor can one be created for it, since a create of a"
                            + " namespace that holds tables is a conflict."),
                Difference.on("s24", "DROP NAMESPACE " + CAT_DB)
                    .baseline(fails(UNSUPPORTED, "Describing database is not supported"))
                    .here(
                        fails(
                            "org.apache.iceberg.exceptions.NamespaceNotEmptyException",
                            "Namespace " + DB + " is not empty"))
                    .because(
                        IRC_UNDECIDED,
                        "Still refused, now as the spec's NamespaceNotEmptyException: the IRC drop"
                            + " refuses a namespace that holds a table or a view, and nothing is"
                            + " dropped."),
                // Baseline: refused. Here it answers, with no row.
                Difference.on("s24", "SHOW NAMESPACES IN " + CAT_DB)
                    .baseline(
                        fails(UNSUPPORTED, "Openhouse supports 2-lvl namespace <schema>.<table>"))
                    .here(rows())
                    .because(
                        IRC_UNDECIDED,
                        "The IRC namespace list answers an empty list under an existing database."
                            + " OpenHouseCatalog refused the call in the"
                            + " engine. Iceberg's Hadoop catalog answers an empty list as well."),
                // Baseline: refused. Here the existence check is answered from tables.
                Difference.on("s24", "USE " + CAT_DB)
                    .baseline(fails(UNSUPPORTED, "Describing database is not supported"))
                    .here(succeeds())
                    .because(
                        IRC_UNDECIDED,
                        "The IRC namespace route answers for a database that has tables, so"
                            + " the existence check passes. OpenHouseCatalog refused it in the"
                            + " engine."),
                Difference.on("s19", "USE openhouse.smoke_t1_new_ns")
                    .baseline(fails(UNSUPPORTED, "Describing database is not supported"))
                    .here(succeeds())
                    .because(
                        IRC_UNDECIDED,
                        "CREATE NAMESPACE stored a record, which the IRC namespace route counts as"
                            + " existence, so the check passes; OpenHouseCatalog refused it in the"
                            + " engine."),

                // ---- s22: the snapshot a replace adds. Baseline: an append.
                Difference.on(
                        "s22",
                        "SELECT operation FROM "
                            + CAT_DB
                            + ".s22_rtas.snapshots ORDER BY committed_at")
                    .baseline(rows("append", "append"))
                    .here(rows("append", "overwrite"))
                    .because(
                        SPARK_4,
                        "Spark 4 plans the write of a REPLACE TABLE AS SELECT as an overwrite, where Spark"
                            + " 3.5 plans an append (V2CreateTableAsSelectBaseExec.writeToTable), and"
                            + " Iceberg names the snapshot after it. The rows, schema and properties after"
                            + " the replace are the baseline's. The same on Iceberg's Hadoop catalog."),

                // ---- s25: SHOW TABLES without a database. Baseline: refused by the OpenHouse
                // catalog.
                Difference.on("s25", "SHOW TABLES IN openhouse")
                    .baseline(showTablesNeedsADatabase())
                    .here(invalidEmptyNamespace())
                    .because(
                        IRC_UNDECIDED,
                        "Still refused, with another class and message: Iceberg's RESTCatalog refuses an"
                            + " empty namespace in the engine, where OpenHouseCatalog.listTables did."),
                Difference.on("s25", "SHOW TABLES")
                    .baseline(showTablesNeedsADatabase())
                    .here(invalidEmptyNamespace())
                    .because(IRC_UNDECIDED, "As SHOW TABLES IN openhouse."),

                // ---- s26: DESCRIBE TABLE EXTENDED. Baseline: a row
                // "Name,<catalog>.<db>.<table>,".
                Difference.on("s26", "DESCRIBE TABLE EXTENDED " + s26)
                    .baseline(
                        hasRow("Name," + s26 + ","),
                        hasRow("Type,MANAGED,"),
                        hasRow("Provider,iceberg,"),
                        hasRowStartingWith(
                            "Location," + storageRoot() + "/" + DB + "/s26_describe-"))
                    .here(
                        noRowStartingWith("Name,"),
                        hasRow("Catalog,openhouse,"),
                        hasRow("Namespace," + DB + ","),
                        hasRow("Table,s26_describe,"),
                        hasRow("Type,MANAGED,"),
                        hasRow("Provider,iceberg,"),
                        hasRowStartingWith(
                            "Location," + storageRoot() + "/" + DB + "/s26_describe-"))
                    .because(
                        SPARK_4,
                        "Spark 4 describes a table's name as the rows Catalog, Namespace and Table, where"
                            + " Spark 3.5 has one Name row. Type, Provider and Location are the baseline's."
                            + " The same on Iceberg's Hadoop catalog."),

                // ---- s27, s29: failures the baseline raises as a class of the OpenHouse Java
                // client.
                Difference.on("s27", "ALTER TABLE " + CAT_DB + ".s27_missing UNLOCK")
                    .baseline(
                        fails(
                            JAVA_CLIENT_FAILURE,
                            "User table " + DB + ".s27_missing cannot be found"))
                    .here(
                        fails(
                            "org.apache.iceberg.exceptions.NoSuchTableException",
                            "User table " + DB + ".s27_missing cannot be found"))
                    .because(
                        DECIDED,
                        "The goal excludes the Java client's exception class from the engine. The"
                            + " capability client raises Iceberg's type for a 404 with the same native"
                            + " message."),
                Difference.on("s29", "ALTER TABLE " + s29 + " RENAME TO " + DB + ".s29_rename")
                    .baseline(
                        fails(
                            JAVA_CLIENT_FAILURE,
                            "Cannot rename a table to the same current db name and table name: s29_rename"))
                    .here(
                        fails(
                            BAD_REQUEST,
                            "Cannot rename a table to the same current db name and table name: s29_rename"))
                    .because(
                        DECIDED,
                        "The goal excludes the Java client's exception class from the engine. The IRC"
                            + " routes answer a native 400 as Iceberg's BadRequestException with the native"
                            + " message.")));
    // ---- s32, s33: what a refused replace leaves, and how a stale commit is applied.
    differences.addAll(
        refusedReplaceLeavesItsDataFile(
            IRC_UNDECIDED,
            "Iceberg's RESTCatalog stages a replace on the client: it loads the"
                + " table (GET), and the first request that says \"replace\" is the commit, after"
                + " Spark wrote the data. The service can refuse no earlier; the GET carries no"
                + " intent. The same on Spark 3.5 over the same routes. The file is left for the"
                + " orphan files job."));
    differences.addAll(
        staleCommitMeetsNoConflict(
            IRC_UNDECIDED,
            "The Iceberg REST client sends the changed keys with only an"
                + " assert-table-uuid requirement, and the service applies them onto the stored"
                + " table, merging a policy patch onto the stored policies (PolicyPatch). Nothing"
                + " to refuse; the table ends with both changes, as on the baseline. The same on"
                + " Spark 3.5 over the same routes."));
    return differences;
  }

  private static Expectation showTablesNeedsADatabase() {
    return fails(
        "org.apache.iceberg.exceptions.ValidationException",
        "DatabaseId was not provided, for SQL please run \"SHOW TABLES IN <databaseId>\" instead");
  }

  private static Expectation invalidEmptyNamespace() {
    return fails("org.apache.iceberg.exceptions.NoSuchNamespaceException", "Invalid namespace: ");
  }
}
