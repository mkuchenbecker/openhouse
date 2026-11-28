package com.linkedin.openhouse.spark.catalogtest;

import com.linkedin.openhouse.javaclient.IcebergLine;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the Spark SQL smoke suite on the plain Iceberg REST path (work item N1-c): Spark 3.5 with
 * Iceberg's own {@code SparkCatalog}, {@code type=rest}, over HTTP to the tables service's Iceberg
 * REST routes, with the OpenHouse SQL extensions in the session and no OpenHouse catalog class on
 * the catalog. {@link #restHttpDifferences()} is the complete list of statements whose outcome is
 * not the baseline's.
 *
 * <p>On both Iceberg lines. The lines' clients differ on one statement, the exception class a
 * refused namespace drop is raised as ({@link #restHttpDifferences()}). {@code
 * RestHttpPathLineTest} pins that the line the declarations follow is the build's line.
 */
public class RestHttpPathSparkSqlSmokeTest extends SparkSqlSmokeTest {

  @Override
  protected String catalogPath() {
    return REST_HTTP;
  }

  /** The same refusal or failure, with another exception class or another wording. */
  static final String TYPE_OR_WORDING = "exception type or wording";

  /** A namespace or SHOW statement the OpenHouse catalog class answered in the engine. */
  static final String NAMESPACE_OR_SHOW = "namespace and SHOW behaviour";

  private static final String UNSUPPORTED = "java.lang.UnsupportedOperationException";
  private static final String JAVA_CLIENT_FAILURE =
      "com.linkedin.openhouse.javaclient.exception.WebClientResponseWithMessageException";
  /**
   * Why a property update of {@value #DB}, which holds tables and has no namespace record, is a
   * missing namespace.
   */
  static final String TABLE_ONLY_HAS_NO_RECORD =
      "KNOWN DEFECT, a consequence of the two existence rules: the namespace exists by holding"
          + " tables, but its properties are those of a namespace record, and it has none, so the"
          + " IRC update answers the namespace service's 404 for a missing record. No record is"
          + " created implicitly; nor can one be created for it, since a create of a namespace"
          + " that holds tables is a conflict.";

  /**
   * The statements on which the rest-http path differs from the baseline: the nine statements on
   * which Spark 4.2 over IRC differs for a reason other than Spark 4 (decision D37), each with the
   * same outcome there. Apart from CREATE NAMESPACE, which stores a namespace record, no statement
   * differs in the rows it reads or in what a write stores.
   */
  static List<Difference> restHttpDifferences() {
    List<Difference> differences = new ArrayList<>();
    // ---- s19, s24: namespace writes, served over stored namespace records.
    differences.add(
        Difference.on("s19", "CREATE NAMESPACE openhouse.smoke_t1_new_ns")
            .baseline(fails(UNSUPPORTED, "Describing database is not supported"))
            .here(succeeds())
            .because(
                NAMESPACE_RECORDS,
                "The IRC create route stores a namespace record for a name that holds nothing."
                    + " KNOWN DEFECT: the namespace is then not listed (SHOW DATABASES), whose list"
                    + " comes from tables only: House Tables has no list of records."));
    differences.add(
        Difference.on("s24", "ALTER NAMESPACE " + CAT_DB + " SET DBPROPERTIES ('k'='v')")
            .baseline(fails(UNSUPPORTED, "Set properties on a database is not supported"))
            .here(
                fails(
                    "org.apache.spark.sql.catalyst.analysis.NoSuchNamespaceException",
                    "[SCHEMA_NOT_FOUND] The schema `" + DB + "` cannot be found."))
            .because(NAMESPACE_RECORDS, TABLE_ONLY_HAS_NO_RECORD));
    differences.add(
        Difference.on("s24", "DROP NAMESPACE " + CAT_DB)
            .baseline(fails(UNSUPPORTED, "Describing database is not supported"))
            .here(
                fails(
                    IcebergLine.current() == IcebergLine.V3
                        ? "org.apache.iceberg.exceptions.NamespaceNotEmptyException"
                        : "org.apache.iceberg.exceptions.AlreadyExistsException",
                    "Namespace " + DB + " is not empty"))
            .because(
                NAMESPACE_RECORDS,
                "Still refused: the IRC drop refuses a namespace that holds a table or a view"
                    + " with the spec's 409 NamespaceNotEmptyException, and nothing is dropped."
                    + " Iceberg 1.12's client raises that type; 1.5.2's maps every 409 of a"
                    + " namespace route to AlreadyExistsException."));
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
    // ---- s19, s24: USE, whose existence check the IRC routes answer from tables.
    differences.add(
        Difference.on("s24", "USE " + CAT_DB)
            .baseline(fails(UNSUPPORTED, "Describing database is not supported"))
            .here(succeeds())
            .because(
                NAMESPACE_OR_SHOW,
                "The IRC namespace route answers for a database that has tables, so the existence"
                    + " check passes; OpenHouseCatalog refused it in the engine."));
    differences.add(
        Difference.on("s19", "USE openhouse.smoke_t1_new_ns")
            .baseline(fails(UNSUPPORTED, "Describing database is not supported"))
            .here(succeeds())
            .because(
                NAMESPACE_RECORDS,
                "CREATE NAMESPACE stored a record, which the IRC namespace route counts as"
                    + " existence, so the check passes; OpenHouseCatalog refused it in the"
                    + " engine."));
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
    // ---- s32, s33: what a refused replace leaves, and how a stale commit is applied.
    differences.addAll(
        refusedReplaceLeavesItsDataFile(
            REFUSED_AT_COMMIT,
            "Work item C3: Iceberg's RESTCatalog stages a replace on the client: it loads the"
                + " table (GET), and the first request that says \"replace\" is the commit, after"
                + " Spark wrote the data. The service can refuse no earlier; the GET carries no"
                + " intent. Same on Spark 4.2. The file is left for the orphan files job."));
    differences.addAll(
        staleCommitMeetsNoConflict(
            NO_CONFLICT,
            "Work item C4: the Iceberg REST client sends the changed keys with only an"
                + " assert-table-uuid requirement, and the service applies them onto the stored"
                + " table, merging a policy patch onto the stored policies (PolicyPatch). Nothing"
                + " to refuse; the table ends with both changes, as on the baseline. Same on"
                + " Spark 4.2."));
    return differences;
  }
}
