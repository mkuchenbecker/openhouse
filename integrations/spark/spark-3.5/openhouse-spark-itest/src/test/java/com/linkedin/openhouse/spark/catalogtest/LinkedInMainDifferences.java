package com.linkedin.openhouse.spark.catalogtest;

import static com.linkedin.openhouse.spark.smoke.LinkedInMainComparison.NOT_OBSERVED_ON_MAIN;

import com.linkedin.openhouse.javaclient.IcebergLine;
import com.linkedin.openhouse.spark.smoke.LinkedInMainComparison.MainDifference;
import java.util.ArrayList;
import java.util.List;

/**
 * Every statement on which the legacy catalog path of this build answers differently from LinkedIn
 * main, as recorded in {@code linkedin-main/*.tsv} by running main's code: main's outcome quoted
 * exactly as the recording stores it, this build's outcome, and the cause. Each line of the build
 * (LinkedIn Iceberg 1.5.2, Iceberg 1.12) has its own list; nothing here changes the product, and a
 * declaration whose statement gives main's outcome again fails.
 */
final class LinkedInMainDifferences {
  private LinkedInMainDifferences() {}

  /** The kind of the differences that the stored namespace records make. */
  static final String NAMESPACE_RECORDS = "namespace records";

  /** The kind of the differences that the Iceberg 1.12 line makes. */
  static final String ICEBERG_112 = "Iceberg 1.12 line";

  private static final boolean ICEBERG_112_LINE = IcebergLine.current() == IcebergLine.V3;

  private static final String NAMESPACE_RECORDS_WHY =
      "The OpenHouse catalog implements SupportsNamespaces over namespace records that the tables"
          + " service stores apart from tables (/v1/{prefix}/namespaces, IcebergNamespacesController;"
          + " OpenHouseCatalog.loadNamespaceMetadata, createNamespace, dropNamespace). On LinkedIn"
          + " main it refused every namespace call. Spark 3.5 with Iceberg 1.5.2 checks existence by"
          + " loading the namespace: a new name is created (a record is stored, and USE then finds"
          + " it), and a database that holds tables but has no record is reported missing"
          + " (SCHEMA_NOT_FOUND).";

  private static final String NAMESPACE_EXISTS_WHY =
      "Iceberg 1.12's SparkCatalog checks a namespace's existence with catalog.namespaceExists"
          + " (Apache Iceberg pull request 14507), which the OpenHouse catalog refuses with its own"
          + " wording; Iceberg 1.5.2 left Spark's default, which describes the namespace. Still"
          + " refused, same class.";

  private static final String NAMESPACE_RECORDS_112_WHY =
      "As on the 1.5.2 line, the namespace records: DESCRIBE and ALTER load the namespace's record"
          + " (OpenHouseCatalog.loadNamespaceMetadata), and a database that holds tables but has no"
          + " record is reported missing (SCHEMA_NOT_FOUND).";

  private static final String ROW_LINEAGE_WHY =
      "Iceberg 1.12's SparkTable lists the format v3 row-lineage metadata columns _row_id and"
          + " _last_updated_sequence_number for every table, a format 2 table included; Iceberg 1.5.2"
          + " has no such columns. The table, its columns and its properties are as on main. The"
          + " OpenHouse catalog does not build that list: a restore would go in the Iceberg fork's"
          + " SparkTable (spark/v3.5, its metadata columns), listing them for format 3 and above only.";

  private static final String CAPABILITY_WHY =
      "On the Iceberg 1.12 line the Spark 3.5 OpenHouse catalog declares no column-defaults"
          + " capability (OpenHouseCatalog.capabilities, IcebergLine.usesReadBridge), so creating a"
          + " table that requires it is refused (422 CLIENT_CAPABILITY_MISSING) and the test creates"
          + " it through a catalog that declares it: one more refusal, and the refusals that follow"
          + " move by one. On main the catalog always declared it.";

  private static final String CONTRACT_NAMESPACE_WHY =
      "The namespace records (see the smoke scenarios' differences): the Java client's"
          + " createNamespace, loadNamespaceMetadata and dropNamespace call the tables service, which"
          + " LinkedIn main's client refused, so the test's namespace calls take another way on"
          + " main.";

  static List<MainDifference> smokeScenarios() {
    List<MainDifference> differences = new ArrayList<>();
    if (ICEBERG_112_LINE) {
      differences.add(
          MainDifference.on("s26 #1 DESCRIBE TABLE EXTENDED openhouse.smoke_t1.s26_describe")
              .main(
                  "rows [id,bigint,null, ,,, # Metadata Columns,,, _spec_id,int,, _partition,struct<>,, _file,string,, _pos,bigint,, _deleted,boolean,, ,,, # Detailed Table Information,,, Name,openhouse.smoke_t1.s26_describe,, Type,MANAGED,, Location,/tmp/smoke_t1/s26_describe-<uuid>,, Provider,iceberg,, Owner,<os user>,, Table Properties,[current-snapshot-id=none,format=iceberg/orc,format-version=2,history.expire.max-ref-age-ms=604800000,openhouse.clusterId=local-cluster,openhouse.creationTime=<number>,openhouse.databaseId=smoke_t1,openhouse.isTableReplicated=false,openhouse.lastModifiedTime=<number>,openhouse.tableCreator=undefined,openhouse.tableId=s26_describe,openhouse.tableLocation=/tmp/smoke_t1/s26_describe-<uuid>/00000-<uuid>.metadata.json,openhouse.tableType=PRIMARY_TABLE,openhouse.tableUUID=<uuid>,openhouse.tableUri=local-cluster.smoke_t1.s26_describe,openhouse.tableVersion=INITIAL_VERSION,policies=,write.format.default=orc,write.metadata.delete-after-commit.enabled=false,write.metadata.previous-versions-max=100,write.parquet.compression-codec=zstd],]")
              .here(
                  "rows [id,bigint,null, ,,, # Metadata Columns,,, _spec_id,int,, _partition,struct<>,, _file,string,, _pos,bigint,, _deleted,boolean,, _row_id,bigint,, _last_updated_sequence_number,bigint,, ,,, # Detailed Table Information,,, Name,openhouse.smoke_t1.s26_describe,, Type,MANAGED,, Location,/tmp/smoke_t1/s26_describe-<uuid>,, Provider,iceberg,, Owner,<os user>,, Table Properties,[current-snapshot-id=none,format=iceberg/orc,format-version=2,history.expire.max-ref-age-ms=604800000,openhouse.clusterId=local-cluster,openhouse.creationTime=<number>,openhouse.databaseId=smoke_t1,openhouse.isTableReplicated=false,openhouse.lastModifiedTime=<number>,openhouse.tableCreator=undefined,openhouse.tableId=s26_describe,openhouse.tableLocation=/tmp/smoke_t1/s26_describe-<uuid>/00000-<uuid>.metadata.json,openhouse.tableType=PRIMARY_TABLE,openhouse.tableUUID=<uuid>,openhouse.tableUri=local-cluster.smoke_t1.s26_describe,openhouse.tableVersion=INITIAL_VERSION,policies=,write.format.default=orc,write.metadata.delete-after-commit.enabled=false,write.metadata.previous-versions-max=100,write.parquet.compression-codec=zstd],]")
              .because(ICEBERG_112, ROW_LINEAGE_WHY));
      differences.add(
          MainDifference.on("s19 #1 CREATE NAMESPACE openhouse.smoke_t1_new_ns")
              .main(
                  "fails with java.lang.UnsupportedOperationException: Describing database is not supported")
              .here(
                  "fails with java.lang.UnsupportedOperationException: Checking if database exists is not supported")
              .because(ICEBERG_112, NAMESPACE_EXISTS_WHY));
      differences.add(
          MainDifference.on("s19 #1 USE openhouse.smoke_t1_new_ns")
              .main(
                  "fails with java.lang.UnsupportedOperationException: Describing database is not supported")
              .here(
                  "fails with java.lang.UnsupportedOperationException: Checking if database exists is not supported")
              .because(ICEBERG_112, NAMESPACE_EXISTS_WHY));
      differences.add(
          MainDifference.on("s24 #1 DESCRIBE NAMESPACE openhouse.smoke_t1")
              .main(
                  "fails with java.lang.UnsupportedOperationException: Describing database is not supported")
              .here(
                  "fails with org.apache.spark.sql.catalyst.analysis.NoSuchNamespaceException: [SCHEMA_NOT_FOUND] The schema `smoke_t1` cannot be found. Verify the spelling and correctness of the schema and catalog.\\nIf you did not qualify the name with a catalog, verify the current_schema() output, or qualify the name with the correct catalog.\\nTo tolerate the error on drop use DROP SCHEMA IF EXISTS.")
              .because(NAMESPACE_RECORDS, NAMESPACE_RECORDS_112_WHY));
      differences.add(
          MainDifference.on("s24 #1 ALTER NAMESPACE openhouse.smoke_t1 SET DBPROPERTIES ('k'='v')")
              .main(
                  "fails with java.lang.UnsupportedOperationException: Set properties on a database is not supported")
              .here(
                  "fails with org.apache.spark.sql.catalyst.analysis.NoSuchNamespaceException: [SCHEMA_NOT_FOUND] The schema `smoke_t1` cannot be found. Verify the spelling and correctness of the schema and catalog.\\nIf you did not qualify the name with a catalog, verify the current_schema() output, or qualify the name with the correct catalog.\\nTo tolerate the error on drop use DROP SCHEMA IF EXISTS.")
              .because(NAMESPACE_RECORDS, NAMESPACE_RECORDS_112_WHY));
      differences.add(
          MainDifference.on("s24 #1 DROP NAMESPACE openhouse.smoke_t1")
              .main(
                  "fails with java.lang.UnsupportedOperationException: Describing database is not supported")
              .here(
                  "fails with java.lang.UnsupportedOperationException: Checking if database exists is not supported")
              .because(ICEBERG_112, NAMESPACE_EXISTS_WHY));
      differences.add(
          MainDifference.on("s24 #1 USE openhouse.smoke_t1")
              .main(
                  "fails with java.lang.UnsupportedOperationException: Describing database is not supported")
              .here(
                  "fails with java.lang.UnsupportedOperationException: Checking if database exists is not supported")
              .because(ICEBERG_112, NAMESPACE_EXISTS_WHY));
    } else {
      differences.add(
          MainDifference.on("s19 #1 CREATE NAMESPACE openhouse.smoke_t1_new_ns")
              .main(
                  "fails with java.lang.UnsupportedOperationException: Describing database is not supported")
              .here("rows []")
              .because(NAMESPACE_RECORDS, NAMESPACE_RECORDS_WHY));
      differences.add(
          MainDifference.on("s19 #1 USE openhouse.smoke_t1_new_ns")
              .main(
                  "fails with java.lang.UnsupportedOperationException: Describing database is not supported")
              .here("rows []")
              .because(NAMESPACE_RECORDS, NAMESPACE_RECORDS_WHY));
      differences.add(
          MainDifference.on("s24 #1 DESCRIBE NAMESPACE openhouse.smoke_t1")
              .main(
                  "fails with java.lang.UnsupportedOperationException: Describing database is not supported")
              .here(
                  "fails with org.apache.spark.sql.catalyst.analysis.NoSuchNamespaceException: [SCHEMA_NOT_FOUND] The schema `smoke_t1` cannot be found. Verify the spelling and correctness of the schema and catalog.\\nIf you did not qualify the name with a catalog, verify the current_schema() output, or qualify the name with the correct catalog.\\nTo tolerate the error on drop use DROP SCHEMA IF EXISTS.")
              .because(NAMESPACE_RECORDS, NAMESPACE_RECORDS_WHY));
      differences.add(
          MainDifference.on("s24 #1 ALTER NAMESPACE openhouse.smoke_t1 SET DBPROPERTIES ('k'='v')")
              .main(
                  "fails with java.lang.UnsupportedOperationException: Set properties on a database is not supported")
              .here(
                  "fails with org.apache.spark.sql.catalyst.analysis.NoSuchNamespaceException: [SCHEMA_NOT_FOUND] The schema `smoke_t1` cannot be found. Verify the spelling and correctness of the schema and catalog.\\nIf you did not qualify the name with a catalog, verify the current_schema() output, or qualify the name with the correct catalog.\\nTo tolerate the error on drop use DROP SCHEMA IF EXISTS.")
              .because(NAMESPACE_RECORDS, NAMESPACE_RECORDS_WHY));
      differences.add(
          MainDifference.on("s24 #1 DROP NAMESPACE openhouse.smoke_t1")
              .main(
                  "fails with java.lang.UnsupportedOperationException: Describing database is not supported")
              .here(
                  "fails with org.apache.spark.sql.catalyst.analysis.NoSuchNamespaceException: [SCHEMA_NOT_FOUND] The schema `smoke_t1` cannot be found. Verify the spelling and correctness of the schema and catalog.\\nIf you did not qualify the name with a catalog, verify the current_schema() output, or qualify the name with the correct catalog.\\nTo tolerate the error on drop use DROP SCHEMA IF EXISTS.")
              .because(NAMESPACE_RECORDS, NAMESPACE_RECORDS_WHY));
      differences.add(
          MainDifference.on("s24 #1 USE openhouse.smoke_t1")
              .main(
                  "fails with java.lang.UnsupportedOperationException: Describing database is not supported")
              .here(
                  "fails with org.apache.spark.sql.catalyst.analysis.NoSuchNamespaceException: [SCHEMA_NOT_FOUND] The schema `openhouse`.`smoke_t1` cannot be found. Verify the spelling and correctness of the schema and catalog.\\nIf you did not qualify the name with a catalog, verify the current_schema() output, or qualify the name with the correct catalog.\\nTo tolerate the error on drop use DROP SCHEMA IF EXISTS.")
              .because(NAMESPACE_RECORDS, NAMESPACE_RECORDS_WHY));
    }
    return differences;
  }

  static List<MainDifference> catalogPluginContract() {
    List<MainDifference> differences = new ArrayList<>();
    differences.add(
        MainDifference.on(
                "CatalogPluginContractTest.namespaceOperationsUseTheNamespaceRecord thrown #2")
            .main(NOT_OBSERVED_ON_MAIN)
            .here(
                "java.lang.UnsupportedOperationException: Openhouse supports 2-lvl namespace <schema>.<table>")
            .because(NAMESPACE_RECORDS, CONTRACT_NAMESPACE_WHY));
    differences.add(
        MainDifference.on(
                "CatalogPluginContractTest.namespaceOperationsUseTheNamespaceRecord verdict")
            .main(
                "fails: org.opentest4j.AssertionFailedError: Unexpected exception type thrown, expected: <org.apache.iceberg.exceptions.NoSuchNamespaceException> but was: <java.lang.UnsupportedOperationException>")
            .here("passes")
            .because(NAMESPACE_RECORDS, CONTRACT_NAMESPACE_WHY));
    differences.add(
        MainDifference.on("CatalogPluginContractTest.sqlCreateAndDropNamespace verdict")
            .main("fails: java.lang.UnsupportedOperationException: Drop database is not supported")
            .here("passes")
            .because(NAMESPACE_RECORDS, CONTRACT_NAMESPACE_WHY));
    if (ICEBERG_112_LINE) {
      differences.add(
          MainDifference.on("CatalogPluginContractTest.sqlCreateAndDropNamespace thrown #1")
              .main(NOT_OBSERVED_ON_MAIN)
              .here(
                  "java.lang.UnsupportedOperationException: Checking if database exists is not supported")
              .because(ICEBERG_112, NAMESPACE_EXISTS_WHY));
      differences.add(
          MainDifference.on("CatalogPluginContractTest.sqlCreateAndDropNamespace thrown #2")
              .main(NOT_OBSERVED_ON_MAIN)
              .here(
                  "java.lang.UnsupportedOperationException: Checking if database exists is not supported")
              .because(ICEBERG_112, NAMESPACE_EXISTS_WHY));
      differences.add(
          MainDifference.on("CatalogPluginContractTest.capabilityMismatch thrown #2")
              .main(
                  "com.linkedin.openhouse.javaclient.exception.WebClientResponseWithMessageException: 422 Unprocessable Entity , {\"status\":\"UNPROCESSABLE_ENTITY\",\"error\":\"Unprocessable Entity\",\"message\":\"CLIENT_CAPABILITY_MISSING: d1_column_default.cpc_capability_plain requires the column-defaults capability, which this client does not declare (X-Client-Name: (none), User-Agent: openhouse-java-client/unknown, X-OpenHouse-Client-Capabilities: (none)). Use a client that declares column-defaults in X-OpenHouse-Client-Capabilities.\",\"stacktrace\":null,\"cause\":\"Not Available\"} (status 422)")
              .here(
                  "com.linkedin.openhouse.javaclient.exception.WebClientResponseWithMessageException: 422 Unprocessable Entity , {\"status\":\"UNPROCESSABLE_ENTITY\",\"error\":\"Unprocessable Entity\",\"message\":\"CLIENT_CAPABILITY_MISSING: d1_column_default.cpc_capability requires the column-defaults capability, which this client does not declare (X-Client-Name: (none), User-Agent: openhouse-java-client/unknown, X-OpenHouse-Client-Capabilities: (none)). Use a client that declares column-defaults in X-OpenHouse-Client-Capabilities.\",\"stacktrace\":null,\"cause\":\"Not Available\"} (status 422)")
              .because(ICEBERG_112, CAPABILITY_WHY));
      differences.add(
          MainDifference.on("CatalogPluginContractTest.capabilityMismatch thrown #3")
              .main(NOT_OBSERVED_ON_MAIN)
              .here(
                  "com.linkedin.openhouse.javaclient.exception.WebClientResponseWithMessageException: 422 Unprocessable Entity , {\"status\":\"UNPROCESSABLE_ENTITY\",\"error\":\"Unprocessable Entity\",\"message\":\"CLIENT_CAPABILITY_MISSING: d1_column_default.cpc_capability_plain requires the column-defaults capability, which this client does not declare (X-Client-Name: (none), User-Agent: openhouse-java-client/unknown, X-OpenHouse-Client-Capabilities: (none)). Use a client that declares column-defaults in X-OpenHouse-Client-Capabilities.\",\"stacktrace\":null,\"cause\":\"Not Available\"} (status 422)")
              .because(ICEBERG_112, CAPABILITY_WHY));
    }
    return differences;
  }

  /**
   * Tests recorded on main that this build no longer runs, each with the reason. None: a test that
   * is removed or disabled must be named here, or the comparison fails.
   */
  static java.util.Map<String, String> absentTests() {
    return java.util.Collections.emptyMap();
  }

  static List<MainDifference> writeStatements() {
    return new ArrayList<>();
  }
}
