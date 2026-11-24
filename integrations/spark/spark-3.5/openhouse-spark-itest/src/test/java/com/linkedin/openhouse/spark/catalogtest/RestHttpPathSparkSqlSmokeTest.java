package com.linkedin.openhouse.spark.catalogtest;

import com.linkedin.openhouse.javaclient.IcebergLine;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;

/**
 * Runs the Spark SQL smoke suite on the plain Iceberg REST path (work item N1-c): Spark 3.5 with
 * Iceberg's own {@code SparkCatalog}, {@code type=rest}, over HTTP to the tables service's Iceberg
 * REST routes, with the OpenHouse SQL extensions in the session and no OpenHouse catalog class on
 * the catalog. {@link #restHttpDifferences()} is the complete list of statements whose outcome is
 * not the baseline's.
 *
 * <p>Iceberg 1.12 line only. On {@code -PicebergLinkedIn} every scenario is skipped by the
 * assumption in {@link #onlyOnTheIceberg112Line()}; {@code RestHttpPathLineTest} pins that the
 * assumption holds exactly on the line the build says it is.
 */
public class RestHttpPathSparkSqlSmokeTest extends SparkSqlSmokeTest {

  @Override
  protected String catalogPath() {
    return REST_HTTP;
  }

  /** Whether this path runs on the Iceberg on this classpath. */
  static boolean runsOnThisLine() {
    return IcebergLine.current() == IcebergLine.V3;
  }

  @BeforeAll
  void onlyOnTheIceberg112Line() {
    Assumptions.assumeTrue(
        runsOnThisLine(),
        "The rest-http smoke path runs on the Iceberg 1.12 line only; this is "
            + IcebergLine.current());
  }

  /** The same refusal or failure, with another exception class or another wording. */
  static final String TYPE_OR_WORDING = "exception type or wording";

  /** A namespace or SHOW statement the OpenHouse catalog class answered in the engine. */
  static final String NAMESPACE_OR_SHOW = "namespace and SHOW behaviour";

  private static final String UNSUPPORTED = "java.lang.UnsupportedOperationException";
  private static final String JAVA_CLIENT_FAILURE =
      "com.linkedin.openhouse.javaclient.exception.WebClientResponseWithMessageException";
  private static final String NAMESPACES_STAY_UNSUPPORTED =
      "Decision D20: namespace create, update and drop stay unsupported over IRC. Still an"
          + " UnsupportedOperationException; the wording is the Iceberg 1.12 client's, which"
          + " refuses a route the service's config does not list before sending anything.";

  /**
   * The statements on which the rest-http path differs from the baseline: the nine statements on
   * which Spark 4.2 over IRC differs for a reason other than Spark 4 (decision D37), each with the
   * same outcome there. No statement differs in the rows it reads or in what a write stores.
   */
  static List<Difference> restHttpDifferences() {
    List<Difference> differences = new ArrayList<>();
    // ---- s19, s24: namespace statements that stay refused, with the REST client's wording.
    differences.add(
        Difference.on("s19", "CREATE NAMESPACE openhouse.smoke_t1_new_ns")
            .baseline(fails(UNSUPPORTED, "Describing database is not supported"))
            .here(
                fails(
                    UNSUPPORTED, "Server does not support endpoint: POST /v1/{prefix}/namespaces"))
            .because(TYPE_OR_WORDING, NAMESPACES_STAY_UNSUPPORTED));
    differences.add(
        Difference.on("s24", "ALTER NAMESPACE " + CAT_DB + " SET DBPROPERTIES ('k'='v')")
            .baseline(fails(UNSUPPORTED, "Set properties on a database is not supported"))
            .here(
                fails(
                    UNSUPPORTED,
                    "Server does not support endpoint:"
                        + " POST /v1/{prefix}/namespaces/{namespace}/properties"))
            .because(TYPE_OR_WORDING, NAMESPACES_STAY_UNSUPPORTED));
    differences.add(
        Difference.on("s24", "DROP NAMESPACE " + CAT_DB)
            .baseline(fails(UNSUPPORTED, "Describing database is not supported"))
            .here(
                fails(
                    UNSUPPORTED,
                    "Server does not support endpoint: DELETE /v1/{prefix}/namespaces/{namespace}"))
            .because(TYPE_OR_WORDING, NAMESPACES_STAY_UNSUPPORTED));
    // ---- s24: namespace statements the OpenHouse catalog refused and the IRC routes answer.
    differences.add(
        Difference.on("s24", "DESCRIBE NAMESPACE " + CAT_DB)
            .baseline(fails(UNSUPPORTED, "Describing database is not supported"))
            .here(rows("Catalog Name,openhouse", "Namespace Name," + DB))
            .because(
                NAMESPACE_OR_SHOW,
                "Accepted in D37 for Spark 4.2: the IRC namespace route answers for a database"
                    + " that has tables, so RESTCatalog loads it; OpenHouseCatalog refused in the"
                    + " engine."));
    differences.add(
        Difference.on("s24", "SHOW NAMESPACES IN " + CAT_DB)
            .baseline(fails(UNSUPPORTED, "Openhouse supports 2-lvl namespace <schema>.<table>"))
            .here(rows())
            .because(
                NAMESPACE_OR_SHOW,
                "Accepted in D37 for Spark 4.2: the IRC namespace list answers an empty list under"
                    + " an existing database; OpenHouseCatalog refused in the engine."));
    // ---- s25: SHOW TABLES without a database. Still refused, by Iceberg's RESTCatalog.
    String noDatabase =
        "DatabaseId was not provided, for SQL please run \"SHOW TABLES IN <databaseId>\" instead";
    String emptyNamespace =
        "Accepted in D37 for Spark 4.2: still refused, with another class and message; Iceberg's"
            + " RESTCatalog refuses an empty namespace in the engine, where"
            + " OpenHouseCatalog.listTables did.";
    for (String statement : new String[] {"SHOW TABLES IN openhouse", "SHOW TABLES"}) {
      differences.add(
          Difference.on("s25", statement)
              .baseline(fails("org.apache.iceberg.exceptions.ValidationException", noDatabase))
              .here(
                  fails(
                      "org.apache.iceberg.exceptions.NoSuchNamespaceException",
                      "Invalid namespace: "))
              .because(NAMESPACE_OR_SHOW, emptyNamespace));
    }
    // ---- s27, s29: failures the baseline raises as a class of the OpenHouse Java client.
    differences.add(
        Difference.on("s27", "ALTER TABLE " + CAT_DB + ".s27_missing UNLOCK")
            .baseline(
                fails(JAVA_CLIENT_FAILURE, "User table " + DB + ".s27_missing cannot be found"))
            .here(
                fails(
                    "org.apache.iceberg.exceptions.NoSuchTableException",
                    "User table " + DB + ".s27_missing cannot be found"))
            .because(
                TYPE_OR_WORDING,
                "No OpenHouse Java client on this catalog: the capability client (D23) raises"
                    + " Iceberg's type for a 404, with the same native message."));
    differences.add(
        Difference.on(
                "s29", "ALTER TABLE " + CAT_DB + ".s29_rename RENAME TO " + DB + ".s29_rename")
            .baseline(
                fails(
                    JAVA_CLIENT_FAILURE,
                    "Cannot rename a table to the same current db name and table name: s29_rename"))
            .here(
                fails(
                    "org.apache.iceberg.exceptions.BadRequestException",
                    "Malformed request: Cannot rename a table to the same current db name and table"
                        + " name: s29_rename"))
            .because(
                TYPE_OR_WORDING,
                "No OpenHouse Java client on this catalog: the IRC routes answer a native 400 as"
                    + " Iceberg's BadRequestException, prefixed \"Malformed request: \" (D16)."));
    return differences;
  }
}
