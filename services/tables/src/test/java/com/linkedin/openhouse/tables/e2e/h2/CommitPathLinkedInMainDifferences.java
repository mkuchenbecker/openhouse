package com.linkedin.openhouse.tables.e2e.h2;

import com.linkedin.openhouse.tables.e2e.h2.LinkedInMainComparison.MainDifference;
import com.linkedin.openhouse.tables.formatversion.IcebergV3;
import java.util.ArrayList;
import java.util.List;

/**
 * Every commit-path answer of this build that is not LinkedIn main's, as recorded in {@code
 * linkedin-main/commit-path.tsv} by running main's code: main's answer quoted exactly as the
 * recording stores it, this build's answer, and the cause. On the reference line (LinkedIn Iceberg
 * 1.5.2) there is none.
 */
final class CommitPathLinkedInMainDifferences {
  private CommitPathLinkedInMainDifferences() {}

  /** The kind of the differences that the Iceberg 1.12 line makes. */
  static final String ICEBERG_112 = "Iceberg 1.12 line";

  private static final String JACKSON_LOCATION_WHY =
      "Kept on purpose, a security default. The message of a 500 that a request body which is not"
          + " JSON gets carries Jackson's parse location. In jackson-core 2.16 the default of"
          + " INCLUDE_SOURCE_IN_LOCATION changed from true to false, so the location no longer"
          + " quotes the request body (\"Source: REDACTED\") and its column is counted"
          + " differently. The default line resolves jackson-core 2.22.2; LinkedIn main and the"
          + " reference line resolve 2.14.3. Status and the rest of the message are as on main.";

  static List<MainDifference> differences() {
    List<MainDifference> differences = new ArrayList<>();
    if (IcebergV3.supported()) {
      differences.add(
          MainDifference.on(
                  "CommitPathRequestValidationTest.invalidSortOrdersAreRejectedAndNothingIsCreated POST /v1/databases/rv_sort/tables/")
              .main(
                  "400 sortOrder : provided {\"order-id\":1,\"fields\":[{\"transform\":\"identity\",\"source-id\":1,\"direction\":\"sideways\",\"null-order\":\"nulls-first\"}]} is not a valid sort order ; 400 sortOrder : provided {} is not a valid sort order ; 500 java.io.UncheckedIOException: com.fasterxml.jackson.core.JsonParseException: Unrecognized token 'not': was expecting (JSON String, Number, Array, Object or token 'null', 'true' or 'false')\\n at [Source: (String)\"not json\"; line: 1, column: 4] ; 500 org.apache.iceberg.exceptions.ValidationException: Cannot find source column for sort field: identity(99) ASC NULLS FIRST")
              .here(
                  "400 sortOrder : provided {\"order-id\":1,\"fields\":[{\"transform\":\"identity\",\"source-id\":1,\"direction\":\"sideways\",\"null-order\":\"nulls-first\"}]} is not a valid sort order ; 400 sortOrder : provided {} is not a valid sort order ; 500 java.io.UncheckedIOException: com.fasterxml.jackson.core.JsonParseException: Unrecognized token 'not': was expecting (JSON String, Number, Array, Object or token 'null', 'true' or 'false')\\n at [Source: REDACTED (`StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION` disabled); line: 1, column: 1] ; 500 org.apache.iceberg.exceptions.ValidationException: Cannot find source column for sort field: identity(99) ASC NULLS FIRST")
              .because(ICEBERG_112, JACKSON_LOCATION_WHY));
      differences.add(
          MainDifference.on(
                  "CommitPathRequestValidationTest.schemaThatIsDuplicatedEmptyOrUnparseableIsRejectedAndNothingIsCreated POST /v1/databases/rv_sch/tables/")
              .main(
                  "400 schema : duplicate column names found [ID] in top-level. Column names must be unique (case-insensitive). ; 400 schema : provided {\"type\":\"struct\",\"fields\":[]}, should contain at least one column ; 500 java.io.UncheckedIOException: com.fasterxml.jackson.core.JsonParseException: Unrecognized token 'garbage': was expecting (JSON String, Number, Array, Object or token 'null', 'true' or 'false')\\n at [Source: (String)\"garbage\"; line: 1, column: 8] ; 500 java.io.UncheckedIOException: com.fasterxml.jackson.databind.exc.MismatchedInputException: No content to map due to end-of-input\\n at [Source: (String)\"\"; line: 1, column: 0] ; 500 org.apache.iceberg.exceptions.ValidationException: Invalid schema: multiple fields for name id: 1 and 2")
              .here(
                  "400 schema : duplicate column names found [ID] in top-level. Column names must be unique (case-insensitive). ; 400 schema : provided {\"type\":\"struct\",\"fields\":[]}, should contain at least one column ; 500 java.io.UncheckedIOException: com.fasterxml.jackson.core.JsonParseException: Unrecognized token 'garbage': was expecting (JSON String, Number, Array, Object or token 'null', 'true' or 'false')\\n at [Source: REDACTED (`StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION` disabled); line: 1, column: 1] ; 500 java.io.UncheckedIOException: com.fasterxml.jackson.databind.exc.MismatchedInputException: No content to map due to end-of-input\\n at [Source: REDACTED (`StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION` disabled); line: 1] ; 500 org.apache.iceberg.exceptions.ValidationException: Invalid schema: multiple fields for name id: 1 and 2")
              .because(ICEBERG_112, JACKSON_LOCATION_WHY));
      differences.add(
          MainDifference.on(
                  "CommitPathSnapshotsTest.snapshotThatIsNotJsonIsAServerErrorAndTableIsUnchanged PUT /v1/databases/rv_snap_garbage/tables/t/iceberg/v2/snapshots")
              .main(
                  "500 org.apache.iceberg.exceptions.CommitStateUnknownException: com.fasterxml.jackson.core.JsonParseException: Unrecognized token 'not': was expecting (JSON String, Number, Array, Object or token 'null', 'true' or 'false')\\n at [Source: (String)\"not a snapshot\"; line: 1, column: 4]\\nCannot determine whether the commit was successful or not, the underlying data files may or may not be needed. Manual intervention via the Remove Orphan Files Action can remove these files when a connection to the Catalog can be re-established if the commit was actually unsuccessful.\\nPlease check to see whether or not your commit was successful before retrying this commit. Retrying an already successful operation will result in duplicate records or unintentional modifications.\\nAt this time no files will be deleted including possibly unused manifest lists.")
              .here(
                  "500 org.apache.iceberg.exceptions.CommitStateUnknownException: com.fasterxml.jackson.core.JsonParseException: Unrecognized token 'not': was expecting (JSON String, Number, Array, Object or token 'null', 'true' or 'false')\\n at [Source: REDACTED (`StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION` disabled); line: 1, column: 1]\\nCannot determine whether the commit was successful or not, the underlying data files may or may not be needed. Manual intervention via the Remove Orphan Files Action can remove these files when a connection to the Catalog can be re-established if the commit was actually unsuccessful.\\nPlease check to see whether or not your commit was successful before retrying this commit. Retrying an already successful operation will result in duplicate records or unintentional modifications.\\nAt this time no files will be deleted including possibly unused manifest lists.")
              .because(ICEBERG_112, JACKSON_LOCATION_WHY));
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
}
