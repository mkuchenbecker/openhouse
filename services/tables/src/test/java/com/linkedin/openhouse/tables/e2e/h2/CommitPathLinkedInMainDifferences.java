package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.LinkedInMainComparison.NOT_OBSERVED_ON_MAIN;

import com.linkedin.openhouse.tables.e2e.h2.LinkedInMainComparison.MainDifference;
import com.linkedin.openhouse.tables.formatversion.IcebergV3;
import java.util.ArrayList;
import java.util.List;

/**
 * Every commit-path answer of this build that is not LinkedIn main's, as recorded in {@code
 * linkedin-main/commit-path.tsv} by running main's code: main's answer quoted exactly as the
 * recording stores it, this build's answer, and the cause. Two kinds: differences of the test
 * double ({@link #STAND_IN}, on both lines), and of the Iceberg 1.12 line (the default line only).
 * On the reference line there is no product difference.
 */
final class CommitPathLinkedInMainDifferences {
  private CommitPathLinkedInMainDifferences() {}

  /**
   * The kind of a difference of the test double, not of the product: the commit-path recording ran
   * main's code with this branch's earlier House Tables stand-in (this module's
   * e2e/h2/HouseTablesH2Repository at commit 0fe4678a, copied into main by the recording step;
   * since removed), not main's own; the tests now run on the one stand-in of tables-test-fixtures,
   * held to the real store by HouseTableRepositoryContract. The product code on these paths is
   * LinkedIn main's: TablesServiceImpl.renameTable, restoreTable and searchSoftDeletedTables,
   * OpenHouseInternalRepositoryImpl.rename, restoreTable and searchSoftDeletedTables,
   * OpenHouseInternalCatalog.renameTable, restoreTable and searchSoftDeletedTables, and
   * TablesController's searchSoftDeletedTables (page size 10 by default), restoreTable and
   * renameTable are the same text at bb8929e1 and here.
   */
  static final String STAND_IN = "stand-in difference";

  private static final String SOFT_DELETED_PAGE_WHY =
      "Recorded on main's code with this branch's earlier stand-in, which built the soft-deleted"
          + " page with the number of pages as its page size (pageSize 1, size 1). The real House Tables store pages as asked, and"
          + " the controller asks for its default, 10 (HouseTableRepositoryContract"
          + " theSoftDeletedListingIgnoresCaseSortsAsAskedAndPagesInTheStore). Content and totals"
          + " are as recorded.";

  private static final String SOFT_DELETED_PAGE_AND_EXTRA_LISTING_WHY =
      SOFT_DELETED_PAGE_WHY
          + " The test also lists once more since the stand-in change, after the restore: the row"
          + " is gone (the empty page).";

  private static final String RESTORE_WHY =
      "Recorded on main's code with this branch's earlier stand-in, which answered a restore with an unknown deletion time and a second restore"
          + " of a table restored already the same way, NoSuchUserTable (404). The real store"
          + " answers the first as a missing soft-deleted row (HouseTableRepositoryContract"
          + " aRestoreOfAMissingSoftDeletedRowIsNotFound; the service names it"
          + " NoSuchSoftDeletedUserTableException, as on main), and the second as a conflict, a"
          + " live table holding the key (aRestoreOverALiveTableIsAConflictAndChangesNothing, 409).";

  private static final String CASE_READ_WHY =
      "House Tables keys ignore letter case (the production tables' collation). The recorded"
          + " stand-in (this branch's earlier one, on main's code) compared case exactly, so another"
          + " spelling of a table's name was not found"
          + " (404); the real store reads it as the same table (HouseTableRepositoryContract"
          + " aCreateIsReadBackByAnySpellingWithTheSpellingItWasCreatedWith).";

  private static final String CASE_RENAME_WHY =
      "This test replaces the recorded "
          + "CommitPathStateRulesTest.renameOntoANameDifferingOnlyInCaseFromAnExistingTableSucceeds"
          + " (recorded keys: "
          + "GET /v1/databases/st_ren_exist_case/tables/OTHER; GET /v1/databases/st_ren_exist_case/tables/other; GET /v1/databases/st_ren_exist_case/tables/u; PATCH /v1/databases/st_ren_exist_case/tables/u/rename?toDatabaseId=st_ren_exist_case&toTableId=OTHER; POST /v1/databases/st_ren_exist_case/tables/; verdict"
          + "), renamed when the stand-in changed. Recorded on main's code with this branch's"
          + " earlier stand-in, which compared keys case exactly, so a rename onto another spelling of an existing table's name succeeded"
          + " (204) and made a second table; the real store refuses it as a conflict on an"
          + " occupied key (HouseTableRepositoryContract"
          + " aRenameOntoAnOccupiedKeyOfAnySpellingIsAConflictAndChangesNothing, 409). Main never"
          + " reached these requests in this form, so each states this build's answer.";

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
    // Differences of the test double, on both lines (see STAND_IN).
    differences.add(
        MainDifference.on(
                "CommitPathAuthorizationTest.deniedRestoreIs403AndTableStaysDeleted GET /v1/databases/auth_rst/softDeletedTables")
            .main(
                "5 x 200 {\"pageResults\":{\"content\":[{\"databaseId\":\"auth_rst\",\"deletedAtMs\":<number>,\"purgeAfterMs\":<number>,\"tableId\":\"t\",\"tableLocation\":\"/tmp/auth_rst/t-<uuid>/00000-<uuid>.metadata.json\"}],\"empty\":false,\"first\":true,\"last\":true,\"number\":0,\"numberOfElements\":1,\"pageable\":{\"offset\":0,\"pageNumber\":0,\"pageSize\":1,\"paged\":true,\"sort\":{\"empty\":true,\"sorted\":false,\"unsorted\":true},\"unpaged\":false},\"size\":1,\"sort\":{\"empty\":true,\"sorted\":false,\"unsorted\":true},\"totalElements\":1,\"totalPages\":1}}")
            .here(
                "5 x 200 {\"pageResults\":{\"content\":[{\"databaseId\":\"auth_rst\",\"deletedAtMs\":<number>,\"purgeAfterMs\":<number>,\"tableId\":\"t\",\"tableLocation\":\"/tmp/auth_rst/t-<uuid>/00000-<uuid>.metadata.json\"}],\"empty\":false,\"first\":true,\"last\":true,\"number\":0,\"numberOfElements\":1,\"pageable\":{\"offset\":0,\"pageNumber\":0,\"pageSize\":10,\"paged\":true,\"sort\":{\"empty\":true,\"sorted\":false,\"unsorted\":true},\"unpaged\":false},\"size\":10,\"sort\":{\"empty\":true,\"sorted\":false,\"unsorted\":true},\"totalElements\":1,\"totalPages\":1}}")
            .because(STAND_IN, SOFT_DELETED_PAGE_WHY));
    differences.add(
        MainDifference.on(
                "CommitPathAuthorizationTest.deniedPurgeIs403AndSoftDeletedTableRemains GET /v1/databases/auth_prg/softDeletedTables")
            .main(
                "4 x 200 {\"pageResults\":{\"content\":[{\"databaseId\":\"auth_prg\",\"deletedAtMs\":<number>,\"purgeAfterMs\":<number>,\"tableId\":\"t\",\"tableLocation\":\"/tmp/auth_prg/t-<uuid>/00000-<uuid>.metadata.json\"}],\"empty\":false,\"first\":true,\"last\":true,\"number\":0,\"numberOfElements\":1,\"pageable\":{\"offset\":0,\"pageNumber\":0,\"pageSize\":1,\"paged\":true,\"sort\":{\"empty\":true,\"sorted\":false,\"unsorted\":true},\"unpaged\":false},\"size\":1,\"sort\":{\"empty\":true,\"sorted\":false,\"unsorted\":true},\"totalElements\":1,\"totalPages\":1}}")
            .here(
                "4 x 200 {\"pageResults\":{\"content\":[{\"databaseId\":\"auth_prg\",\"deletedAtMs\":<number>,\"purgeAfterMs\":<number>,\"tableId\":\"t\",\"tableLocation\":\"/tmp/auth_prg/t-<uuid>/00000-<uuid>.metadata.json\"}],\"empty\":false,\"first\":true,\"last\":true,\"number\":0,\"numberOfElements\":1,\"pageable\":{\"offset\":0,\"pageNumber\":0,\"pageSize\":10,\"paged\":true,\"sort\":{\"empty\":true,\"sorted\":false,\"unsorted\":true},\"unpaged\":false},\"size\":10,\"sort\":{\"empty\":true,\"sorted\":false,\"unsorted\":true},\"totalElements\":1,\"totalPages\":1}}")
            .because(STAND_IN, SOFT_DELETED_PAGE_WHY));
    differences.add(
        MainDifference.on(
                "CommitPathConcurrencyTest.purgeRemovesOnlyRowsWhosePurgeTimeIsBeforeTheGivenTime GET /v1/databases/cc_purge/softDeletedTables")
            .main(
                "200 {\"pageResults\":{\"content\":[],\"empty\":true,\"first\":true,\"last\":true,\"number\":0,\"numberOfElements\":0,\"pageable\":{\"offset\":0,\"pageNumber\":0,\"pageSize\":1,\"paged\":true,\"sort\":{\"empty\":true,\"sorted\":false,\"unsorted\":true},\"unpaged\":false},\"size\":1,\"sort\":{\"empty\":true,\"sorted\":false,\"unsorted\":true},\"totalElements\":0,\"totalPages\":0}} ; 3 x 200 {\"pageResults\":{\"content\":[{\"databaseId\":\"cc_purge\",\"deletedAtMs\":<number>,\"purgeAfterMs\":<number>,\"tableId\":\"t\",\"tableLocation\":\"/tmp/cc_purge/t-<uuid>/00000-<uuid>.metadata.json\"}],\"empty\":false,\"first\":true,\"last\":true,\"number\":0,\"numberOfElements\":1,\"pageable\":{\"offset\":0,\"pageNumber\":0,\"pageSize\":1,\"paged\":true,\"sort\":{\"empty\":true,\"sorted\":false,\"unsorted\":true},\"unpaged\":false},\"size\":1,\"sort\":{\"empty\":true,\"sorted\":false,\"unsorted\":true},\"totalElements\":1,\"totalPages\":1}}")
            .here(
                "200 {\"pageResults\":{\"content\":[],\"empty\":true,\"first\":true,\"last\":true,\"number\":0,\"numberOfElements\":0,\"pageable\":{\"offset\":0,\"pageNumber\":0,\"pageSize\":10,\"paged\":true,\"sort\":{\"empty\":true,\"sorted\":false,\"unsorted\":true},\"unpaged\":false},\"size\":10,\"sort\":{\"empty\":true,\"sorted\":false,\"unsorted\":true},\"totalElements\":0,\"totalPages\":0}} ; 3 x 200 {\"pageResults\":{\"content\":[{\"databaseId\":\"cc_purge\",\"deletedAtMs\":<number>,\"purgeAfterMs\":<number>,\"tableId\":\"t\",\"tableLocation\":\"/tmp/cc_purge/t-<uuid>/00000-<uuid>.metadata.json\"}],\"empty\":false,\"first\":true,\"last\":true,\"number\":0,\"numberOfElements\":1,\"pageable\":{\"offset\":0,\"pageNumber\":0,\"pageSize\":10,\"paged\":true,\"sort\":{\"empty\":true,\"sorted\":false,\"unsorted\":true},\"unpaged\":false},\"size\":10,\"sort\":{\"empty\":true,\"sorted\":false,\"unsorted\":true},\"totalElements\":1,\"totalPages\":1}}")
            .because(STAND_IN, SOFT_DELETED_PAGE_WHY));
    differences.add(
        MainDifference.on(
                "CommitPathConcurrencyTest.softDeletedListingIsScopedToTheTableAndDatabase GET /v1/databases/cc_listing/softDeletedTables")
            .main(
                "3 x 200 {\"pageResults\":{\"content\":[{\"databaseId\":\"cc_listing\",\"deletedAtMs\":<number>,\"purgeAfterMs\":<number>,\"tableId\":\"t\",\"tableLocation\":\"/tmp/cc_listing/t-<uuid>/00000-<uuid>.metadata.json\"}],\"empty\":false,\"first\":true,\"last\":true,\"number\":0,\"numberOfElements\":1,\"pageable\":{\"offset\":0,\"pageNumber\":0,\"pageSize\":1,\"paged\":true,\"sort\":{\"empty\":true,\"sorted\":false,\"unsorted\":true},\"unpaged\":false},\"size\":1,\"sort\":{\"empty\":true,\"sorted\":false,\"unsorted\":true},\"totalElements\":1,\"totalPages\":1}}")
            .here(
                "3 x 200 {\"pageResults\":{\"content\":[{\"databaseId\":\"cc_listing\",\"deletedAtMs\":<number>,\"purgeAfterMs\":<number>,\"tableId\":\"t\",\"tableLocation\":\"/tmp/cc_listing/t-<uuid>/00000-<uuid>.metadata.json\"}],\"empty\":false,\"first\":true,\"last\":true,\"number\":0,\"numberOfElements\":1,\"pageable\":{\"offset\":0,\"pageNumber\":0,\"pageSize\":10,\"paged\":true,\"sort\":{\"empty\":true,\"sorted\":false,\"unsorted\":true},\"unpaged\":false},\"size\":10,\"sort\":{\"empty\":true,\"sorted\":false,\"unsorted\":true},\"totalElements\":1,\"totalPages\":1}}")
            .because(STAND_IN, SOFT_DELETED_PAGE_WHY));
    differences.add(
        MainDifference.on(
                "CommitPathConcurrencyTest.softDeletedListingIsScopedToTheTableAndDatabase GET /v1/databases/cc_listing_elsewhere/softDeletedTables")
            .main(
                "200 {\"pageResults\":{\"content\":[],\"empty\":true,\"first\":true,\"last\":true,\"number\":0,\"numberOfElements\":0,\"pageable\":{\"offset\":0,\"pageNumber\":0,\"pageSize\":1,\"paged\":true,\"sort\":{\"empty\":true,\"sorted\":false,\"unsorted\":true},\"unpaged\":false},\"size\":1,\"sort\":{\"empty\":true,\"sorted\":false,\"unsorted\":true},\"totalElements\":0,\"totalPages\":0}}")
            .here(
                "200 {\"pageResults\":{\"content\":[],\"empty\":true,\"first\":true,\"last\":true,\"number\":0,\"numberOfElements\":0,\"pageable\":{\"offset\":0,\"pageNumber\":0,\"pageSize\":10,\"paged\":true,\"sort\":{\"empty\":true,\"sorted\":false,\"unsorted\":true},\"unpaged\":false},\"size\":10,\"sort\":{\"empty\":true,\"sorted\":false,\"unsorted\":true},\"totalElements\":0,\"totalPages\":0}}")
            .because(STAND_IN, SOFT_DELETED_PAGE_WHY));
    differences.add(
        MainDifference.on(
                "CommitPathConcurrencyTest.softDeleteListsARealDeletionTimeAndRestoreBringsTheTableBack GET /v1/databases/cc_restore/softDeletedTables")
            .main(
                "200 {\"pageResults\":{\"content\":[],\"empty\":true,\"first\":true,\"last\":true,\"number\":0,\"numberOfElements\":0,\"pageable\":{\"offset\":0,\"pageNumber\":0,\"pageSize\":1,\"paged\":true,\"sort\":{\"empty\":true,\"sorted\":false,\"unsorted\":true},\"unpaged\":false},\"size\":1,\"sort\":{\"empty\":true,\"sorted\":false,\"unsorted\":true},\"totalElements\":0,\"totalPages\":0}} ; 2 x 200 {\"pageResults\":{\"content\":[{\"databaseId\":\"cc_restore\",\"deletedAtMs\":<number>,\"purgeAfterMs\":<number>,\"tableId\":\"t\",\"tableLocation\":\"/tmp/cc_restore/t-<uuid>/00000-<uuid>.metadata.json\"}],\"empty\":false,\"first\":true,\"last\":true,\"number\":0,\"numberOfElements\":1,\"pageable\":{\"offset\":0,\"pageNumber\":0,\"pageSize\":1,\"paged\":true,\"sort\":{\"empty\":true,\"sorted\":false,\"unsorted\":true},\"unpaged\":false},\"size\":1,\"sort\":{\"empty\":true,\"sorted\":false,\"unsorted\":true},\"totalElements\":1,\"totalPages\":1}}")
            .here(
                "200 {\"pageResults\":{\"content\":[],\"empty\":true,\"first\":true,\"last\":true,\"number\":0,\"numberOfElements\":0,\"pageable\":{\"offset\":0,\"pageNumber\":0,\"pageSize\":10,\"paged\":true,\"sort\":{\"empty\":true,\"sorted\":false,\"unsorted\":true},\"unpaged\":false},\"size\":10,\"sort\":{\"empty\":true,\"sorted\":false,\"unsorted\":true},\"totalElements\":0,\"totalPages\":0}} ; 2 x 200 {\"pageResults\":{\"content\":[{\"databaseId\":\"cc_restore\",\"deletedAtMs\":<number>,\"purgeAfterMs\":<number>,\"tableId\":\"t\",\"tableLocation\":\"/tmp/cc_restore/t-<uuid>/00000-<uuid>.metadata.json\"}],\"empty\":false,\"first\":true,\"last\":true,\"number\":0,\"numberOfElements\":1,\"pageable\":{\"offset\":0,\"pageNumber\":0,\"pageSize\":10,\"paged\":true,\"sort\":{\"empty\":true,\"sorted\":false,\"unsorted\":true},\"unpaged\":false},\"size\":10,\"sort\":{\"empty\":true,\"sorted\":false,\"unsorted\":true},\"totalElements\":1,\"totalPages\":1}}")
            .because(STAND_IN, SOFT_DELETED_PAGE_WHY));
    differences.add(
        MainDifference.on(
                "CommitPathConcurrencyTest.restoreOfAnUnknownDeletionTimeIsNotFoundAndKeepsTheSoftDeletedRow GET /v1/databases/cc_restore_unknown/softDeletedTables")
            .main(
                "2 x 200 {\"pageResults\":{\"content\":[{\"databaseId\":\"cc_restore_unknown\",\"deletedAtMs\":<number>,\"purgeAfterMs\":<number>,\"tableId\":\"t\",\"tableLocation\":\"/tmp/cc_restore_unknown/t-<uuid>/00000-<uuid>.metadata.json\"}],\"empty\":false,\"first\":true,\"last\":true,\"number\":0,\"numberOfElements\":1,\"pageable\":{\"offset\":0,\"pageNumber\":0,\"pageSize\":1,\"paged\":true,\"sort\":{\"empty\":true,\"sorted\":false,\"unsorted\":true},\"unpaged\":false},\"size\":1,\"sort\":{\"empty\":true,\"sorted\":false,\"unsorted\":true},\"totalElements\":1,\"totalPages\":1}}")
            .here(
                "200 {\"pageResults\":{\"content\":[],\"empty\":true,\"first\":true,\"last\":true,\"number\":0,\"numberOfElements\":0,\"pageable\":{\"offset\":0,\"pageNumber\":0,\"pageSize\":10,\"paged\":true,\"sort\":{\"empty\":true,\"sorted\":false,\"unsorted\":true},\"unpaged\":false},\"size\":10,\"sort\":{\"empty\":true,\"sorted\":false,\"unsorted\":true},\"totalElements\":0,\"totalPages\":0}} ; 2 x 200 {\"pageResults\":{\"content\":[{\"databaseId\":\"cc_restore_unknown\",\"deletedAtMs\":<number>,\"purgeAfterMs\":<number>,\"tableId\":\"t\",\"tableLocation\":\"/tmp/cc_restore_unknown/t-<uuid>/00000-<uuid>.metadata.json\"}],\"empty\":false,\"first\":true,\"last\":true,\"number\":0,\"numberOfElements\":1,\"pageable\":{\"offset\":0,\"pageNumber\":0,\"pageSize\":10,\"paged\":true,\"sort\":{\"empty\":true,\"sorted\":false,\"unsorted\":true},\"unpaged\":false},\"size\":10,\"sort\":{\"empty\":true,\"sorted\":false,\"unsorted\":true},\"totalElements\":1,\"totalPages\":1}}")
            .because(STAND_IN, SOFT_DELETED_PAGE_AND_EXTRA_LISTING_WHY));
    differences.add(
        MainDifference.on(
                "CommitPathConcurrencyTest.restoreOfAnUnknownDeletionTimeIsNotFoundAndKeepsTheSoftDeletedRow PUT /v1/databases/cc_restore_unknown/tables/t/restore?deletedAtMs=<number>")
            .main("204 ; 2 x 404 User table cc_restore_unknown.t cannot be found")
            .here(
                "204 ; 404 Soft deleted user table cc_restore_unknown.t with deletedAtMs <number> cannot be found ; 409 Table cc_restore_unknown.t already exists")
            .because(STAND_IN, RESTORE_WHY));
    differences.add(
        MainDifference.on(
                "CommitPathConcurrencyTest.deleteIsAHardDeleteAndNothingAppearsInTheSoftDeletedListing GET /v1/databases/cc_delete/softDeletedTables")
            .main(
                "200 {\"pageResults\":{\"content\":[],\"empty\":true,\"first\":true,\"last\":true,\"number\":0,\"numberOfElements\":0,\"pageable\":{\"offset\":0,\"pageNumber\":0,\"pageSize\":1,\"paged\":true,\"sort\":{\"empty\":true,\"sorted\":false,\"unsorted\":true},\"unpaged\":false},\"size\":1,\"sort\":{\"empty\":true,\"sorted\":false,\"unsorted\":true},\"totalElements\":0,\"totalPages\":0}}")
            .here(
                "200 {\"pageResults\":{\"content\":[],\"empty\":true,\"first\":true,\"last\":true,\"number\":0,\"numberOfElements\":0,\"pageable\":{\"offset\":0,\"pageNumber\":0,\"pageSize\":10,\"paged\":true,\"sort\":{\"empty\":true,\"sorted\":false,\"unsorted\":true},\"unpaged\":false},\"size\":10,\"sort\":{\"empty\":true,\"sorted\":false,\"unsorted\":true},\"totalElements\":0,\"totalPages\":0}}")
            .because(STAND_IN, SOFT_DELETED_PAGE_WHY));
    differences.add(
        MainDifference.on(
                "CommitPathStateRulesTest.renameToTheSameNameDifferingOnlyInCaseIsRejected GET /v1/databases/st_ren_case/tables/T")
            .main("404 User table st_ren_case.T cannot be found")
            .here(
                "200 {\"clusterId\":\"local-cluster\",\"clustering\":[{\"columnName\":\"id\",\"transform\":null},{\"columnName\":\"name\",\"transform\":{\"transformParams\":[\"10\"],\"transformType\":\"TRUNCATE\"}}],\"config\":{},\"creationTime\":<number>,\"databaseId\":\"st_ren_case\",\"lastModifiedTime\":<number>,\"policies\":{\"columnTags\":null,\"history\":{\"granularity\":\"DAY\",\"maxAge\":3,\"versions\":10},\"lockState\":null,\"replication\":{\"config\":[{\"cronSchedule\":\"0 <minute> <hour>/12 ? * * *\",\"destination\":\"CLUSTER1\",\"interval\":\"12H\"}]},\"retention\":{\"columnPattern\":null,\"count\":3,\"granularity\":\"HOUR\",\"timeZone\":null},\"sharingEnabled\":false},\"schema\":\"{\\\\\"type\\\\\":\\\\\"struct\\\\\",\\\\\"schema-id\\\\\":0,\\\\\"fields\\\\\":[{\\\\\"id\\\\\":1,\\\\\"name\\\\\":\\\\\"id\\\\\",\\\\\"required\\\\\":true,\\\\\"type\\\\\":\\\\\"string\\\\\"},{\\\\\"id\\\\\":2,\\\\\"name\\\\\":\\\\\"name\\\\\",\\\\\"required\\\\\":true,\\\\\"type\\\\\":\\\\\"string\\\\\"},{\\\\\"id\\\\\":3,\\\\\"name\\\\\":\\\\\"timestampCol\\\\\",\\\\\"required\\\\\":true,\\\\\"type\\\\\":\\\\\"timestamp\\\\\"},{\\\\\"id\\\\\":4,\\\\\"name\\\\\":\\\\\"stats\\\\\",\\\\\"required\\\\\":true,\\\\\"type\\\\\":\\\\\"double\\\\\"},{\\\\\"id\\\\\":5,\\\\\"name\\\\\":\\\\\"count\\\\\",\\\\\"required\\\\\":true,\\\\\"type\\\\\":\\\\\"int\\\\\"},{\\\\\"id\\\\\":6,\\\\\"name\\\\\":\\\\\"timeLong\\\\\",\\\\\"required\\\\\":true,\\\\\"type\\\\\":\\\\\"long\\\\\"}]}\",\"sortOrder\":\"{\\\\\"order-id\\\\\":0,\\\\\"fields\\\\\":[]}\",\"tableCreator\":\"testUser\",\"tableId\":\"t\",\"tableLocation\":\"file:/tmp/st_ren_case/t-<uuid>/00000-<uuid>.metadata.json\",\"tableProperties\":{\"history.expire.max-ref-age-ms\":\"604800000\",\"openhouse.clusterId\":\"local-cluster\",\"openhouse.creationTime\":\"<number>\",\"openhouse.databaseId\":\"st_ren_case\",\"openhouse.isTableReplicated\":\"false\",\"openhouse.lastModifiedTime\":\"<number>\",\"openhouse.tableCreator\":\"testUser\",\"openhouse.tableId\":\"t\",\"openhouse.tableLocation\":\"/tmp/st_ren_case/t-<uuid>/00000-<uuid>.metadata.json\",\"openhouse.tableType\":\"PRIMARY_TABLE\",\"openhouse.tableUUID\":\"<uuid>\",\"openhouse.tableUri\":\"local-cluster.st_ren_case.t\",\"openhouse.tableVersion\":\"INITIAL_VERSION\",\"policies\":\"{\\\\n  \\\\\"retention\\\\\": {\\\\n    \\\\\"count\\\\\": 3,\\\\n    \\\\\"granularity\\\\\": \\\\\"HOUR\\\\\"\\\\n  },\\\\n  \\\\\"sharingEnabled\\\\\": false,\\\\n  \\\\\"replication\\\\\": {\\\\n    \\\\\"config\\\\\": [\\\\n      {\\\\n        \\\\\"destination\\\\\": \\\\\"CLUSTER1\\\\\",\\\\n        \\\\\"interval\\\\\": \\\\\"12H\\\\\",\\\\n        \\\\\"cronSchedule\\\\\": \\\\\"0 <minute> <hour>/12 ? * * *\\\\\"\\\\n      }\\\\n    ]\\\\n  },\\\\n  \\\\\"history\\\\\": {\\\\n    \\\\\"maxAge\\\\\": 3,\\\\n    \\\\\"granularity\\\\\": \\\\\"DAY\\\\\",\\\\n    \\\\\"versions\\\\\": 10\\\\n  }\\\\n}\",\"user.a\":\"b\",\"user.c\":\"d\",\"write.format.default\":\"orc\",\"write.metadata.delete-after-commit.enabled\":\"false\",\"write.metadata.previous-versions-max\":\"100\",\"write.parquet.compression-codec\":\"zstd\"},\"tableType\":\"PRIMARY_TABLE\",\"tableUUID\":\"<uuid>\",\"tableUri\":\"local-cluster.st_ren_case.t\",\"tableVersion\":\"INITIAL_VERSION\",\"timePartitioning\":{\"columnName\":\"timestampCol\",\"granularity\":\"HOUR\"}}")
            .because(STAND_IN, CASE_READ_WHY));
    differences.add(
        MainDifference.on(
                "CommitPathStateRulesTest.renameOntoANameDifferingOnlyInCaseFromAnExistingTableIsAConflict POST /v1/databases/st_ren_exist_case/tables/")
            .main(NOT_OBSERVED_ON_MAIN)
            .here("2 x 201")
            .because(STAND_IN, CASE_RENAME_WHY));
    differences.add(
        MainDifference.on(
                "CommitPathStateRulesTest.renameOntoANameDifferingOnlyInCaseFromAnExistingTableIsAConflict GET /v1/databases/st_ren_exist_case/tables/other")
            .main(NOT_OBSERVED_ON_MAIN)
            .here(
                "2 x 200 {\"clusterId\":\"local-cluster\",\"clustering\":[{\"columnName\":\"id\",\"transform\":null},{\"columnName\":\"name\",\"transform\":{\"transformParams\":[\"10\"],\"transformType\":\"TRUNCATE\"}}],\"config\":{},\"creationTime\":<number>,\"databaseId\":\"st_ren_exist_case\",\"lastModifiedTime\":<number>,\"policies\":{\"columnTags\":null,\"history\":{\"granularity\":\"DAY\",\"maxAge\":3,\"versions\":10},\"lockState\":null,\"replication\":{\"config\":[{\"cronSchedule\":\"0 <minute> <hour>/12 ? * * *\",\"destination\":\"CLUSTER1\",\"interval\":\"12H\"}]},\"retention\":{\"columnPattern\":null,\"count\":3,\"granularity\":\"HOUR\",\"timeZone\":null},\"sharingEnabled\":false},\"schema\":\"{\\\\\"type\\\\\":\\\\\"struct\\\\\",\\\\\"schema-id\\\\\":0,\\\\\"fields\\\\\":[{\\\\\"id\\\\\":1,\\\\\"name\\\\\":\\\\\"id\\\\\",\\\\\"required\\\\\":true,\\\\\"type\\\\\":\\\\\"string\\\\\"},{\\\\\"id\\\\\":2,\\\\\"name\\\\\":\\\\\"name\\\\\",\\\\\"required\\\\\":true,\\\\\"type\\\\\":\\\\\"string\\\\\"},{\\\\\"id\\\\\":3,\\\\\"name\\\\\":\\\\\"timestampCol\\\\\",\\\\\"required\\\\\":true,\\\\\"type\\\\\":\\\\\"timestamp\\\\\"},{\\\\\"id\\\\\":4,\\\\\"name\\\\\":\\\\\"stats\\\\\",\\\\\"required\\\\\":true,\\\\\"type\\\\\":\\\\\"double\\\\\"},{\\\\\"id\\\\\":5,\\\\\"name\\\\\":\\\\\"count\\\\\",\\\\\"required\\\\\":true,\\\\\"type\\\\\":\\\\\"int\\\\\"},{\\\\\"id\\\\\":6,\\\\\"name\\\\\":\\\\\"timeLong\\\\\",\\\\\"required\\\\\":true,\\\\\"type\\\\\":\\\\\"long\\\\\"}]}\",\"sortOrder\":\"{\\\\\"order-id\\\\\":0,\\\\\"fields\\\\\":[]}\",\"tableCreator\":\"testUser\",\"tableId\":\"other\",\"tableLocation\":\"file:/tmp/st_ren_exist_case/other-<uuid>/00000-<uuid>.metadata.json\",\"tableProperties\":{\"history.expire.max-ref-age-ms\":\"604800000\",\"openhouse.clusterId\":\"local-cluster\",\"openhouse.creationTime\":\"<number>\",\"openhouse.databaseId\":\"st_ren_exist_case\",\"openhouse.isTableReplicated\":\"false\",\"openhouse.lastModifiedTime\":\"<number>\",\"openhouse.tableCreator\":\"testUser\",\"openhouse.tableId\":\"other\",\"openhouse.tableLocation\":\"/tmp/st_ren_exist_case/other-<uuid>/00000-<uuid>.metadata.json\",\"openhouse.tableType\":\"PRIMARY_TABLE\",\"openhouse.tableUUID\":\"<uuid>\",\"openhouse.tableUri\":\"local-cluster.st_ren_exist_case.other\",\"openhouse.tableVersion\":\"INITIAL_VERSION\",\"policies\":\"{\\\\n  \\\\\"retention\\\\\": {\\\\n    \\\\\"count\\\\\": 3,\\\\n    \\\\\"granularity\\\\\": \\\\\"HOUR\\\\\"\\\\n  },\\\\n  \\\\\"sharingEnabled\\\\\": false,\\\\n  \\\\\"replication\\\\\": {\\\\n    \\\\\"config\\\\\": [\\\\n      {\\\\n        \\\\\"destination\\\\\": \\\\\"CLUSTER1\\\\\",\\\\n        \\\\\"interval\\\\\": \\\\\"12H\\\\\",\\\\n        \\\\\"cronSchedule\\\\\": \\\\\"0 <minute> <hour>/12 ? * * *\\\\\"\\\\n      }\\\\n    ]\\\\n  },\\\\n  \\\\\"history\\\\\": {\\\\n    \\\\\"maxAge\\\\\": 3,\\\\n    \\\\\"granularity\\\\\": \\\\\"DAY\\\\\",\\\\n    \\\\\"versions\\\\\": 10\\\\n  }\\\\n}\",\"user.a\":\"b\",\"user.c\":\"d\",\"write.format.default\":\"orc\",\"write.metadata.delete-after-commit.enabled\":\"false\",\"write.metadata.previous-versions-max\":\"100\",\"write.parquet.compression-codec\":\"zstd\"},\"tableType\":\"PRIMARY_TABLE\",\"tableUUID\":\"<uuid>\",\"tableUri\":\"local-cluster.st_ren_exist_case.other\",\"tableVersion\":\"INITIAL_VERSION\",\"timePartitioning\":{\"columnName\":\"timestampCol\",\"granularity\":\"HOUR\"}}")
            .because(STAND_IN, CASE_RENAME_WHY));
    differences.add(
        MainDifference.on(
                "CommitPathStateRulesTest.renameOntoANameDifferingOnlyInCaseFromAnExistingTableIsAConflict GET /v1/databases/st_ren_exist_case/tables/u")
            .main(NOT_OBSERVED_ON_MAIN)
            .here(
                "2 x 200 {\"clusterId\":\"local-cluster\",\"clustering\":[{\"columnName\":\"id\",\"transform\":null},{\"columnName\":\"name\",\"transform\":{\"transformParams\":[\"10\"],\"transformType\":\"TRUNCATE\"}}],\"config\":{},\"creationTime\":<number>,\"databaseId\":\"st_ren_exist_case\",\"lastModifiedTime\":<number>,\"policies\":{\"columnTags\":null,\"history\":{\"granularity\":\"DAY\",\"maxAge\":3,\"versions\":10},\"lockState\":null,\"replication\":{\"config\":[{\"cronSchedule\":\"0 <minute> <hour>/12 ? * * *\",\"destination\":\"CLUSTER1\",\"interval\":\"12H\"}]},\"retention\":{\"columnPattern\":null,\"count\":3,\"granularity\":\"HOUR\",\"timeZone\":null},\"sharingEnabled\":false},\"schema\":\"{\\\\\"type\\\\\":\\\\\"struct\\\\\",\\\\\"schema-id\\\\\":0,\\\\\"fields\\\\\":[{\\\\\"id\\\\\":1,\\\\\"name\\\\\":\\\\\"id\\\\\",\\\\\"required\\\\\":true,\\\\\"type\\\\\":\\\\\"string\\\\\"},{\\\\\"id\\\\\":2,\\\\\"name\\\\\":\\\\\"name\\\\\",\\\\\"required\\\\\":true,\\\\\"type\\\\\":\\\\\"string\\\\\"},{\\\\\"id\\\\\":3,\\\\\"name\\\\\":\\\\\"timestampCol\\\\\",\\\\\"required\\\\\":true,\\\\\"type\\\\\":\\\\\"timestamp\\\\\"},{\\\\\"id\\\\\":4,\\\\\"name\\\\\":\\\\\"stats\\\\\",\\\\\"required\\\\\":true,\\\\\"type\\\\\":\\\\\"double\\\\\"},{\\\\\"id\\\\\":5,\\\\\"name\\\\\":\\\\\"count\\\\\",\\\\\"required\\\\\":true,\\\\\"type\\\\\":\\\\\"int\\\\\"},{\\\\\"id\\\\\":6,\\\\\"name\\\\\":\\\\\"timeLong\\\\\",\\\\\"required\\\\\":true,\\\\\"type\\\\\":\\\\\"long\\\\\"}]}\",\"sortOrder\":\"{\\\\\"order-id\\\\\":0,\\\\\"fields\\\\\":[]}\",\"tableCreator\":\"testUser\",\"tableId\":\"u\",\"tableLocation\":\"file:/tmp/st_ren_exist_case/u-<uuid>/00000-<uuid>.metadata.json\",\"tableProperties\":{\"history.expire.max-ref-age-ms\":\"604800000\",\"openhouse.clusterId\":\"local-cluster\",\"openhouse.creationTime\":\"<number>\",\"openhouse.databaseId\":\"st_ren_exist_case\",\"openhouse.isTableReplicated\":\"false\",\"openhouse.lastModifiedTime\":\"<number>\",\"openhouse.tableCreator\":\"testUser\",\"openhouse.tableId\":\"u\",\"openhouse.tableLocation\":\"/tmp/st_ren_exist_case/u-<uuid>/00000-<uuid>.metadata.json\",\"openhouse.tableType\":\"PRIMARY_TABLE\",\"openhouse.tableUUID\":\"<uuid>\",\"openhouse.tableUri\":\"local-cluster.st_ren_exist_case.u\",\"openhouse.tableVersion\":\"INITIAL_VERSION\",\"policies\":\"{\\\\n  \\\\\"retention\\\\\": {\\\\n    \\\\\"count\\\\\": 3,\\\\n    \\\\\"granularity\\\\\": \\\\\"HOUR\\\\\"\\\\n  },\\\\n  \\\\\"sharingEnabled\\\\\": false,\\\\n  \\\\\"replication\\\\\": {\\\\n    \\\\\"config\\\\\": [\\\\n      {\\\\n        \\\\\"destination\\\\\": \\\\\"CLUSTER1\\\\\",\\\\n        \\\\\"interval\\\\\": \\\\\"12H\\\\\",\\\\n        \\\\\"cronSchedule\\\\\": \\\\\"0 <minute> <hour>/12 ? * * *\\\\\"\\\\n      }\\\\n    ]\\\\n  },\\\\n  \\\\\"history\\\\\": {\\\\n    \\\\\"maxAge\\\\\": 3,\\\\n    \\\\\"granularity\\\\\": \\\\\"DAY\\\\\",\\\\n    \\\\\"versions\\\\\": 10\\\\n  }\\\\n}\",\"user.a\":\"b\",\"user.c\":\"d\",\"write.format.default\":\"orc\",\"write.metadata.delete-after-commit.enabled\":\"false\",\"write.metadata.previous-versions-max\":\"100\",\"write.parquet.compression-codec\":\"zstd\"},\"tableType\":\"PRIMARY_TABLE\",\"tableUUID\":\"<uuid>\",\"tableUri\":\"local-cluster.st_ren_exist_case.u\",\"tableVersion\":\"INITIAL_VERSION\",\"timePartitioning\":{\"columnName\":\"timestampCol\",\"granularity\":\"HOUR\"}}")
            .because(STAND_IN, CASE_RENAME_WHY));
    differences.add(
        MainDifference.on(
                "CommitPathStateRulesTest.renameOntoANameDifferingOnlyInCaseFromAnExistingTableIsAConflict PATCH /v1/databases/st_ren_exist_case/tables/u/rename?toDatabaseId=st_ren_exist_case&toTableId=OTHER")
            .main(NOT_OBSERVED_ON_MAIN)
            .here("409 Table local-cluster.st_ren_exist_case.other already exists")
            .because(STAND_IN, CASE_RENAME_WHY));
    differences.add(
        MainDifference.on(
                "CommitPathStateRulesTest.renameOntoANameDifferingOnlyInCaseFromAnExistingTableIsAConflict verdict")
            .main(NOT_OBSERVED_ON_MAIN)
            .here("passes")
            .because(STAND_IN, CASE_RENAME_WHY));
    differences.add(
        MainDifference.on(
                "CommitPathTableCountLimitTest.restoreAtTheLimitIsRejectedWith422AndSucceedsAfterADrop GET /v1/databases/cl_restore/softDeletedTables")
            .main(
                "200 {\"pageResults\":{\"content\":[],\"empty\":true,\"first\":true,\"last\":true,\"number\":0,\"numberOfElements\":0,\"pageable\":{\"offset\":0,\"pageNumber\":0,\"pageSize\":1,\"paged\":true,\"sort\":{\"empty\":true,\"sorted\":false,\"unsorted\":true},\"unpaged\":false},\"size\":1,\"sort\":{\"empty\":true,\"sorted\":false,\"unsorted\":true},\"totalElements\":0,\"totalPages\":0}} ; 3 x 200 {\"pageResults\":{\"content\":[{\"databaseId\":\"cl_restore\",\"deletedAtMs\":<number>,\"purgeAfterMs\":<number>,\"tableId\":\"t1\",\"tableLocation\":\"/tmp/cl_restore/t1-<uuid>/00000-<uuid>.metadata.json\"}],\"empty\":false,\"first\":true,\"last\":true,\"number\":0,\"numberOfElements\":1,\"pageable\":{\"offset\":0,\"pageNumber\":0,\"pageSize\":1,\"paged\":true,\"sort\":{\"empty\":true,\"sorted\":false,\"unsorted\":true},\"unpaged\":false},\"size\":1,\"sort\":{\"empty\":true,\"sorted\":false,\"unsorted\":true},\"totalElements\":1,\"totalPages\":1}}")
            .here(
                "200 {\"pageResults\":{\"content\":[],\"empty\":true,\"first\":true,\"last\":true,\"number\":0,\"numberOfElements\":0,\"pageable\":{\"offset\":0,\"pageNumber\":0,\"pageSize\":10,\"paged\":true,\"sort\":{\"empty\":true,\"sorted\":false,\"unsorted\":true},\"unpaged\":false},\"size\":10,\"sort\":{\"empty\":true,\"sorted\":false,\"unsorted\":true},\"totalElements\":0,\"totalPages\":0}} ; 3 x 200 {\"pageResults\":{\"content\":[{\"databaseId\":\"cl_restore\",\"deletedAtMs\":<number>,\"purgeAfterMs\":<number>,\"tableId\":\"t1\",\"tableLocation\":\"/tmp/cl_restore/t1-<uuid>/00000-<uuid>.metadata.json\"}],\"empty\":false,\"first\":true,\"last\":true,\"number\":0,\"numberOfElements\":1,\"pageable\":{\"offset\":0,\"pageNumber\":0,\"pageSize\":10,\"paged\":true,\"sort\":{\"empty\":true,\"sorted\":false,\"unsorted\":true},\"unpaged\":false},\"size\":10,\"sort\":{\"empty\":true,\"sorted\":false,\"unsorted\":true},\"totalElements\":1,\"totalPages\":1}}")
            .because(STAND_IN, SOFT_DELETED_PAGE_WHY));
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
    return java.util.Collections.singletonMap(
        "CommitPathStateRulesTest.renameOntoANameDifferingOnlyInCaseFromAnExistingTableSucceeds",
        "Renamed to renameOntoANameDifferingOnlyInCaseFromAnExistingTableIsAConflict when the"
            + " House Tables stand-in was held to the real store; that test's answers are declared"
            + " as stand-in differences that name these recorded keys.");
  }
}
