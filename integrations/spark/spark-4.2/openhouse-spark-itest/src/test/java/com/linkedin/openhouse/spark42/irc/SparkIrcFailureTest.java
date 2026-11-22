package com.linkedin.openhouse.spark42.irc;

import static com.linkedin.openhouse.spark42.irc.IrcSpark.DB;
import static com.linkedin.openhouse.spark42.irc.IrcSpark.column;
import static com.linkedin.openhouse.spark42.irc.IrcSpark.failure;

import java.util.Arrays;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * What a Spark 4.2 user sees when a read over IRC fails: the exception Spark SQL throws and its
 * message, pinned as observed.
 */
public class SparkIrcFailureTest {

  static void assertFailure(String type, String message, Throwable failure) {
    Assertions.assertEquals(type, failure.getClass().getName(), String.valueOf(failure));
    Assertions.assertTrue(
        String.valueOf(failure.getMessage()).contains(message),
        "message was: " + failure.getMessage());
  }

  @Test
  void aMissingTableIsTableOrViewNotFound() throws Exception {
    assertFailure(
        "org.apache.spark.sql.catalyst.ExtendedAnalysisException",
        "[TABLE_OR_VIEW_NOT_FOUND] The table or view `irc`.`irc42`.`never` cannot be found.",
        failure("SELECT * FROM irc." + DB + ".never"));
    assertFailure(
        "org.apache.spark.sql.catalyst.ExtendedAnalysisException",
        "[TABLE_OR_VIEW_NOT_FOUND] The table or view `irc`.`irc42`.`never` cannot be found.",
        failure("DESCRIBE TABLE irc." + DB + ".never"));
  }

  /**
   * Listing the tables of a missing namespace surfaces Iceberg's own exception with the service's
   * message: Iceberg's {@code SparkCatalog.listTables} does not translate it to Spark's {@code
   * SCHEMA_NOT_FOUND}. (The OpenHouse catalog lists nothing for a missing database.) Reading a
   * table in it is Spark's ordinary table-not-found.
   */
  @Test
  void aMissingNamespace() throws Exception {
    assertFailure(
        "org.apache.iceberg.exceptions.NoSuchNamespaceException",
        "Namespace does not exist: irc42_never",
        failure("SHOW TABLES IN irc.irc42_never"));
    assertFailure(
        "org.apache.spark.sql.catalyst.ExtendedAnalysisException",
        "[TABLE_OR_VIEW_NOT_FOUND] The table or view `irc`.`irc42_never`.`flat` cannot be found.",
        failure("SELECT * FROM irc.irc42_never.flat"));
  }

  /**
   * The service's authorization denies this principal every table. Listing makes no authorization
   * decision on the native API, so it lists; loading the table is refused.
   */
  @Test
  void aDeniedPrincipalCanListButNotRead() throws Exception {
    Assertions.assertEquals(Arrays.asList(DB), column("SHOW NAMESPACES IN irc_stranger"));
    Assertions.assertTrue(
        IrcSpark.rows("SHOW TABLES IN irc_stranger." + DB).stream()
            .anyMatch(row -> "flat".equals(row.get(1))));
    assertFailure(
        "org.apache.iceberg.exceptions.ForbiddenException",
        "Forbidden: Operation on table irc42.flat failed as user irc_stranger is unauthorized",
        failure("SELECT * FROM irc_stranger." + DB + ".flat"));
    assertFailure(
        "org.apache.iceberg.exceptions.ForbiddenException",
        "Forbidden: Operation on table irc42.flat failed as user irc_stranger is unauthorized",
        failure("DESCRIBE TABLE irc_stranger." + DB + ".flat"));
  }

  /** A token the service does not accept fails the catalog's first request, its config call. */
  @Test
  void aBadTokenFailsEveryStatement() throws Exception {
    assertFailure(
        "org.apache.iceberg.exceptions.NotAuthorizedException",
        "Not authorized: Unauthorized",
        failure("SHOW NAMESPACES IN irc_badtoken"));
    assertFailure(
        "org.apache.iceberg.exceptions.NotAuthorizedException",
        "Not authorized: Unauthorized",
        failure("SELECT * FROM irc_badtoken." + DB + ".flat"));
  }
}
