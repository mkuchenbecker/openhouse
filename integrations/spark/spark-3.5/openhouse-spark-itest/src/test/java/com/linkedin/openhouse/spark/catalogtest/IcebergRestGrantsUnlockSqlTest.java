package com.linkedin.openhouse.spark.catalogtest;

import com.linkedin.openhouse.common.security.DummyTokenInterceptor;
import com.linkedin.openhouse.spark.grants.GrantStoringAuthorization;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.LockState;
import com.linkedin.openhouse.tables.model.TableDto;
import com.linkedin.openhouse.tables.model.TableDtoPrimaryKey;
import com.linkedin.openhouse.tables.repository.OpenHouseInternalRepository;
import com.linkedin.openhouse.tablestest.OpenHouseSparkITest;
import com.linkedin.openhouse.tablestest.SpringH2TestApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * GRANT, REVOKE, SHOW GRANTS and UNLOCK through Iceberg's own {@code SparkCatalog} over an
 * unmodified {@code RESTCatalog} ({@code type=rest}), compared statement by statement with the same
 * statement through the OpenHouse catalog on an identical table.
 *
 * <p>A second embedded tables service stores grants ({@link GrantStoringAuthorization}; the default
 * fixture drops them) and denies {@value GrantStoringAuthorization#STRANGER} every privilege. Each
 * statement runs twice: through the OpenHouse catalog ({@code oh}) on database {@value #LEGACY_DB},
 * and through the REST catalog ({@code irc}) on database {@value #REST_DB}, whose tables are
 * created identically. Each outcome is pinned; where both succeed they must be equal (SHOW GRANTS
 * rows included), and where both fail the REST failure must carry the native message the OpenHouse
 * catalog's failure carries. After every statement the stored grants and locks of the two
 * databases, read from the service, must be equal.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class IcebergRestGrantsUnlockSqlTest extends OpenHouseSparkITest {
  static final String LEGACY_DB = "gu_ohc";
  static final String REST_DB = "gu_irc";
  private static final String OWNER = "gu_owner";
  private static final String UNLOCK_ENABLED = "spark.sql.openhouse.unlock.enabled";
  private static final String LEGACY_FAILURE =
      "com.linkedin.openhouse.javaclient.exception.WebClientResponseWithMessageException";

  /** Every table each case uses, created in both databases. */
  private static final List<String> TABLES =
      Arrays.asList(
          "shared",
          "unshared",
          "grant_locked",
          "legacy_lock",
          "legacy_lock_by_reason",
          "system_lock",
          "system_lock_wrong_reason",
          "legacy_lock_wrong_reason",
          "denied_lock",
          "unlocked",
          "effect",
          "across");

  private ConfigurableApplicationContext server;
  private SparkSession spark;
  private GrantStoringAuthorization acls;
  private OpenHouseInternalRepository repository;
  private final HttpClient http = HttpClient.newHttpClient();

  @BeforeAll
  void start() throws Exception {
    org.apache.catalina.webresources.TomcatURLStreamHandlerFactory.disable();
    server =
        new SpringApplication(SpringH2TestApplication.class, GrantStoringAuthorization.Config.class)
            .run(
                "--server.port=0",
                "--cluster.security.token.interceptor.classname="
                    + DummyTokenInterceptor.class.getName());
    acls = server.getBean(GrantStoringAuthorization.class);
    repository = server.getBean(OpenHouseInternalRepository.class);
    spark = getSparkSession().newSession();
    openHouseCatalog("ohc", OWNER);
    openHouseCatalog("ohc_stranger", GrantStoringAuthorization.STRANGER);
    restCatalog("irc", OWNER);
    restCatalog("irc_stranger", GrantStoringAuthorization.STRANGER);
    // The principal that holds only what is granted to it. No catalog cache, so that every
    // statement asks the service again after a grant or a revoke.
    openHouseCatalog("ohc_only", GrantStoringAuthorization.ONLY_GRANTED);
    restCatalog("irc_only", GrantStoringAuthorization.ONLY_GRANTED);
    spark.conf().set("spark.sql.catalog.irc_only.cache-enabled", "false");
    // The REST-shaped catalog class over the same service, for grants seen across paths.
    openHouseCatalog("shim", OWNER);
    spark
        .conf()
        .set(
            "spark.sql.catalog.shim.catalog-impl",
            "com.linkedin.openhouse.spark.OpenHouseRestCatalog");

    for (String db : Arrays.asList(LEGACY_DB, REST_DB)) {
      for (String table : TABLES) {
        spark.sql("CREATE TABLE ohc." + db + "." + table + " (id bigint)");
        if (!table.equals("unshared")) {
          spark.sql("ALTER TABLE ohc." + db + "." + table + " SET POLICY (SHARING=TRUE)");
        }
      }
      lock(db, "grant_locked", null);
      lock(db, "legacy_lock", null);
      lock(db, "legacy_lock_by_reason", null);
      lock(db, "legacy_lock_wrong_reason", null);
      lock(db, "denied_lock", null);
      lock(db, "system_lock", "SYSTEM_ONLY");
      lock(db, "system_lock_wrong_reason", "SYSTEM_ONLY");
      spark.sql("INSERT INTO ohc." + db + ".effect VALUES (1)");
    }
    assertSameState("set-up");
  }

  @AfterAll
  void stop() {
    if (server != null) {
      server.close();
    }
  }

  private String uri() {
    return "http://localhost:" + ((WebServerApplicationContext) server).getWebServer().getPort();
  }

  private static String token(String principal) {
    try {
      return new DummyTokenInterceptor.DummySecurityJWT(principal).buildNoopJWT();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** The OpenHouse catalog, configured as the Spark 3.5 tests configure it. */
  private void openHouseCatalog(String name, String principal) {
    String prefix = "spark.sql.catalog." + name;
    spark.conf().set(prefix, "org.apache.iceberg.spark.SparkCatalog");
    spark.conf().set(prefix + ".catalog-impl", "com.linkedin.openhouse.spark.OpenHouseCatalog");
    spark.conf().set(prefix + ".uri", uri());
    spark.conf().set(prefix + ".cluster", "local-cluster");
    spark.conf().set(prefix + ".auth-token", token(principal));
    spark.conf().set(prefix + ".cache-enabled", "false");
  }

  /** Iceberg's own REST catalog, configured as a user would. */
  private void restCatalog(String name, String principal) {
    String prefix = "spark.sql.catalog." + name;
    spark.conf().set(prefix, "org.apache.iceberg.spark.SparkCatalog");
    spark.conf().set(prefix + ".type", "rest");
    spark.conf().set(prefix + ".uri", uri() + "/iceberg");
    spark.conf().set(prefix + ".token", token(principal));
  }

  /** Locks a table through the native lock route (there is no SQL statement that locks). */
  private void lock(String db, String table, String reason) throws Exception {
    String body =
        reason == null
            ? "{\"locked\":true,\"message\":\"locked by the test\"}"
            : "{\"locked\":true,\"reason\":\"" + reason + "\"}";
    HttpResponse<String> response =
        http.send(
            HttpRequest.newBuilder(
                    URI.create(uri() + "/v1/databases/" + db + "/tables/" + table + "/lock"))
                .header("Authorization", "Bearer " + token(OWNER))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    Assertions.assertEquals(201, response.statusCode(), response.body());
  }

  // ------------------------------------------------------------------ the stored state

  private String lockState(String db, String table) {
    Optional<TableDto> dto =
        repository.findById(TableDtoPrimaryKey.builder().databaseId(db).tableId(table).build());
    if (!dto.isPresent()) {
      return "missing";
    }
    LockState lock =
        dto.get().getPolicies() == null ? null : dto.get().getPolicies().getLockState();
    return lock == null || !lock.isLocked() ? "unlocked" : "locked " + lock.getReason();
  }

  /** Every grant and lock of {@code db}, its tables and its {@code _new} sibling database. */
  private List<String> state(String db) {
    List<String> state = new ArrayList<>();
    state.add("database: " + acls.stored(db));
    state.add("new database: " + acls.stored(db + "_new"));
    for (String table : TABLES) {
      state.add(table + ": " + acls.stored(db + "." + table) + " " + lockState(db, table));
    }
    return state;
  }

  private void assertSameState(String after) {
    Assertions.assertEquals(state(LEGACY_DB), state(REST_DB), "stored state after " + after);
  }

  // ------------------------------------------------------------------ running a statement

  /** "ok" plus the rows, or the failure's class and message, with the database written {db}. */
  private String outcome(String sql, String db) {
    String outcome;
    try {
      List<String> rows =
          spark.sql(sql).collectAsList().stream().map(Row::toString).collect(Collectors.toList());
      outcome = rows.isEmpty() ? "ok" : "ok " + rows;
    } catch (Throwable failure) {
      outcome = failure.getClass().getName() + ": " + failure.getMessage();
    }
    return outcome.replace(db, "{db}");
  }

  /**
   * Runs {@code template} through both catalogs ({@code {cat}} is {@code ohc} or {@code irc},
   * {@code {db}} the database) and returns the REST outcome, after checking it against the
   * OpenHouse catalog's: equal when the OpenHouse catalog succeeds or fails with something other
   * than the Java client's response exception; otherwise that exception, carrying {@code
   * nativeMessage}, which the REST failure must carry too. The stored state must be equal after.
   */
  private String both(String template, boolean stranger, String nativeMessage) {
    return both(template, stranger ? "_stranger" : "", nativeMessage);
  }

  /**
   * As {@link #both(String, boolean, String)}, as the principal of the catalogs named {@code ohc}
   * and {@code irc} followed by {@code principal}: {@code ""} the owner, {@code "_stranger"} the
   * principal denied everything, {@code "_only"} the one that holds only what is granted to it.
   */
  private String both(String template, String principal, String nativeMessage) {
    String legacy = outcome(sql(template, "ohc" + principal, LEGACY_DB), LEGACY_DB);
    String rest = outcome(sql(template, "irc" + principal, REST_DB), REST_DB);
    // Both outcomes in the test report, for the record of what a user sees on each path.
    System.out.println("OUTCOME\t" + template + " (as" + principal + ")\t" + legacy + "\t" + rest);
    if (nativeMessage == null) {
      Assertions.assertEquals(legacy, rest, template);
    } else {
      Assertions.assertTrue(
          legacy.startsWith(LEGACY_FAILURE + ": ") || legacy.startsWith("java.lang."), legacy);
      Assertions.assertTrue(legacy.contains(nativeMessage), legacy + " carries " + nativeMessage);
      Assertions.assertTrue(rest.contains(nativeMessage), rest + " carries " + nativeMessage);
    }
    assertSameState(template);
    return rest;
  }

  private static String sql(String template, String catalog, String db) {
    return template.replace("{cat}", catalog).replace("{db}", db);
  }

  private static Set<String> set(String... values) {
    return new LinkedHashSet<>(Arrays.asList(values));
  }

  // ------------------------------------------------------------------ grants

  @Test
  void grantShowAndRevokeOnATable() {
    Assertions.assertEquals(
        "ok", both("GRANT SELECT ON TABLE {cat}.{db}.shared TO gu_reader", false, null));
    Assertions.assertEquals(
        "ok", both("GRANT ALTER ON TABLE {cat}.{db}.shared TO PUBLIC", false, null));
    Assertions.assertEquals(
        "ok", both("GRANT MANAGE GRANTS ON TABLE {cat}.{db}.shared TO gu_admin", false, null));
    Assertions.assertEquals(
        Arrays.asList("gu_reader=TABLE_VIEWER", "*=TABLE_ADMIN", "gu_admin=ACL_EDITOR"),
        acls.stored(REST_DB + ".shared"));
    Assertions.assertEquals(
        "ok [[SELECT,gu_reader], [ALTER,PUBLIC], [MANAGE GRANTS,gu_admin]]",
        both("SHOW GRANTS ON TABLE {cat}.{db}.shared", false, null));
    // Listing makes no access decision, natively or over REST.
    Assertions.assertEquals(
        "ok [[SELECT,gu_reader], [ALTER,PUBLIC], [MANAGE GRANTS,gu_admin]]",
        both("SHOW GRANTS ON TABLE {cat}.{db}.shared", true, null));
    Assertions.assertEquals(
        "ok", both("REVOKE SELECT ON TABLE {cat}.{db}.shared FROM gu_reader", false, null));
    Assertions.assertEquals(
        "ok", both("REVOKE ALTER ON TABLE {cat}.{db}.shared FROM public", false, null));
    Assertions.assertEquals(Arrays.asList("gu_admin=ACL_EDITOR"), acls.stored(REST_DB + ".shared"));
    Assertions.assertEquals(
        "ok [[MANAGE GRANTS,gu_admin]]",
        both("SHOW GRANTS ON TABLE {cat}.{db}.shared", false, null));
  }

  @Test
  void grantShowAndRevokeOnADatabaseWithTablesAndOnOneWithNone() {
    Assertions.assertEquals(
        "ok", both("GRANT CREATE TABLE ON DATABASE {cat}.{db} TO gu_creator", false, null));
    Assertions.assertEquals(
        "ok [[CREATE TABLE,gu_creator]]", both("SHOW GRANTS ON DATABASE {cat}.{db}", false, null));
    Assertions.assertEquals(
        "ok", both("REVOKE CREATE TABLE ON DATABASE {cat}.{db} FROM gu_creator", false, null));
    Assertions.assertEquals("ok", both("SHOW GRANTS ON DATABASE {cat}.{db}", false, null));

    // A database that holds no table yet: the grant comes before the first table.
    Assertions.assertEquals(
        "ok", both("GRANT CREATE TABLE ON DATABASE {cat}.{db}_new TO gu_creator", false, null));
    Assertions.assertEquals(
        Arrays.asList("gu_creator=TABLE_CREATOR"), acls.stored(REST_DB + "_new"));
    Assertions.assertEquals(
        "ok [[CREATE TABLE,gu_creator]]",
        both("SHOW GRANTS ON DATABASE {cat}.{db}_new", false, null));
    Assertions.assertEquals(
        "ok", both("REVOKE CREATE TABLE ON DATABASE {cat}.{db}_new FROM gu_creator", false, null));
    Assertions.assertEquals(Arrays.asList(), acls.stored(REST_DB + "_new"));
  }

  @Test
  void aTableThatIsNotSharedRefusesAGrant() {
    // The OpenHouse catalog throws IllegalArgumentException("400 , <body>"); REST keeps the type.
    String message = "{db}.unshared is not a shared table";
    Assertions.assertEquals(
        "java.lang.IllegalArgumentException: " + message,
        both("GRANT SELECT ON TABLE {cat}.{db}.unshared TO gu_reader", false, message));
    Assertions.assertTrue(
        outcome("GRANT SELECT ON TABLE ohc." + LEGACY_DB + ".unshared TO gu_reader", LEGACY_DB)
            .startsWith("java.lang.IllegalArgumentException: 400 , "));
  }

  @Test
  void aLockedTableRefusesAGrantButNotARevoke() {
    String message = "{db}.grant_locked is in locked state and grants are not allowed for sharing";
    Assertions.assertEquals(
        "java.lang.IllegalArgumentException: " + message,
        both("GRANT SELECT ON TABLE {cat}.{db}.grant_locked TO gu_reader", false, message));
    Assertions.assertEquals(
        "ok", both("REVOKE SELECT ON TABLE {cat}.{db}.grant_locked FROM gu_reader", false, null));
  }

  @Test
  void aDeniedPrincipalIsForbidden() {
    String table = "Operation on table {db}.shared failed as user irc_stranger is unauthorized";
    Assertions.assertEquals(
        "org.apache.iceberg.exceptions.ForbiddenException: Forbidden: " + table,
        both("GRANT SELECT ON TABLE {cat}.{db}.shared TO gu_reader", true, table));
    Assertions.assertEquals(
        "org.apache.iceberg.exceptions.ForbiddenException: Forbidden: " + table,
        both("REVOKE SELECT ON TABLE {cat}.{db}.shared FROM gu_reader", true, table));
    String database = "Operation on database [{db}] failed as user [irc_stranger] is unauthorized";
    Assertions.assertEquals(
        "org.apache.iceberg.exceptions.ForbiddenException: Forbidden: " + database,
        both("GRANT CREATE TABLE ON DATABASE {cat}.{db} TO gu_reader", true, database));
  }

  // ------------------------------------------------------------------ the effect of a grant

  private static final String ONLY = "_only";

  /**
   * A grant has an effect: {@value GrantStoringAuthorization#ONLY_GRANTED} holds only the
   * privileges of what is granted to it, so it is refused a read until SELECT is granted, reads
   * after the grant, and is refused again after the revoke; the grant gives what it names and not a
   * write. Through the native routes (OpenHouse catalog) and the IRC routes (REST catalog), with
   * the same outcome and the same stored state.
   */
  @Test
  void aTableGrantLetsThePrincipalReadAndTheRevokeTakesItAway() {
    String select = "SELECT * FROM {cat}.{db}.effect";
    String message = "Operation on table {db}.effect failed as user only_granted is unauthorized";
    String forbidden = "org.apache.iceberg.exceptions.ForbiddenException: Forbidden: " + message;
    Assertions.assertEquals(forbidden, both(select, ONLY, message));
    Assertions.assertEquals(
        "ok", both("GRANT SELECT ON TABLE {cat}.{db}.effect TO only_granted", "", null));
    Assertions.assertEquals("ok [[1]]", both(select, ONLY, null));
    // SELECT gives a read, not a write.
    Assertions.assertEquals(
        forbidden, both("INSERT INTO {cat}.{db}.effect VALUES (2)", ONLY, message));
    Assertions.assertEquals(
        "ok", both("REVOKE SELECT ON TABLE {cat}.{db}.effect FROM only_granted", "", null));
    Assertions.assertEquals(forbidden, both(select, ONLY, message));
    Assertions.assertEquals("ok [[1]]", both(select, "", null));
  }

  /**
   * The database form: {@value GrantStoringAuthorization#ONLY_GRANTED} may not create a table in a
   * database until CREATE TABLE is granted on it, and may again not after the revoke.
   *
   * <p>With the grant, the two paths differ, and both create the table. Over IRC the create
   * succeeds. Through the OpenHouse catalog the statement fails after the table is created: the
   * catalog reads the new table back, which needs a privilege on the table that CREATE TABLE on the
   * database does not give in this backend (the creator is given nothing on its table here; what a
   * deployed policy gives a creator is not in this repository). Pinned as observed.
   */
  @Test
  void aDatabaseGrantLetsThePrincipalCreateATableAndTheRevokeTakesItAway() {
    String create = "CREATE TABLE {cat}.{db}.made_by_grantee (id bigint)";
    String message = "Operation on database [{db}] failed as user [only_granted] is unauthorized";
    String forbidden = "org.apache.iceberg.exceptions.ForbiddenException: Forbidden: " + message;
    try {
      Assertions.assertEquals(forbidden, both(create, ONLY, message));
      Assertions.assertEquals(
          "ok", both("GRANT CREATE TABLE ON DATABASE {cat}.{db} TO only_granted", "", null));

      Assertions.assertEquals(
          LEGACY_FAILURE
              + ": 403 Forbidden , {\"status\":\"FORBIDDEN\",\"error\":\"Forbidden\",\"message\":"
              + "\"Operation on table {db}.made_by_grantee failed as user only_granted is"
              + " unauthorized\",\"stacktrace\":null,\"cause\":\"Not Available\"}",
          outcome(sql(create, "ohc" + ONLY, LEGACY_DB), LEGACY_DB));
      Assertions.assertEquals("ok", outcome(sql(create, "irc" + ONLY, REST_DB), REST_DB));
      Assertions.assertEquals("unlocked", lockState(LEGACY_DB, "made_by_grantee"));
      Assertions.assertEquals("unlocked", lockState(REST_DB, "made_by_grantee"));

      Assertions.assertEquals(
          "ok", both("REVOKE CREATE TABLE ON DATABASE {cat}.{db} FROM only_granted", "", null));
      both("DROP TABLE {cat}.{db}.made_by_grantee", "", null);
      Assertions.assertEquals(forbidden, both(create, ONLY, message));
    } finally {
      both("DROP TABLE IF EXISTS {cat}.{db}.made_by_grantee", "", null);
    }
  }

  /**
   * One service, three catalog paths: a grant made through one is listed through the others, and a
   * revoke through one is gone through the others. The OpenHouse catalog and the REST-shaped
   * catalog class reach the native routes, the REST catalog the IRC capability routes; all three
   * reach the one store.
   */
  @Test
  void aGrantMadeThroughOnePathIsListedAndRevokedThroughTheOthers() {
    String t = "." + REST_DB + ".across";
    Assertions.assertEquals("ok", outcome("GRANT SELECT ON TABLE ohc" + t + " TO gu_x", REST_DB));
    Assertions.assertEquals("ok", outcome("GRANT ALTER ON TABLE irc" + t + " TO gu_y", REST_DB));
    Assertions.assertEquals(
        "ok", outcome("GRANT MANAGE GRANTS ON TABLE shim" + t + " TO gu_z", REST_DB));
    String all = "ok [[SELECT,gu_x], [ALTER,gu_y], [MANAGE GRANTS,gu_z]]";
    for (String catalog : Arrays.asList("ohc", "irc", "shim")) {
      Assertions.assertEquals(
          all, outcome("SHOW GRANTS ON TABLE " + catalog + t, REST_DB), catalog);
    }
    Assertions.assertEquals(
        Arrays.asList("gu_x=TABLE_VIEWER", "gu_y=TABLE_ADMIN", "gu_z=ACL_EDITOR"),
        acls.stored(REST_DB + ".across"));

    Assertions.assertEquals(
        "ok", outcome("REVOKE SELECT ON TABLE irc" + t + " FROM gu_x", REST_DB));
    Assertions.assertEquals(
        "ok", outcome("REVOKE ALTER ON TABLE shim" + t + " FROM gu_y", REST_DB));
    Assertions.assertEquals(
        "ok", outcome("REVOKE MANAGE GRANTS ON TABLE ohc" + t + " FROM gu_z", REST_DB));
    for (String catalog : Arrays.asList("ohc", "irc", "shim")) {
      Assertions.assertEquals(
          "ok", outcome("SHOW GRANTS ON TABLE " + catalog + t, REST_DB), catalog);
    }

    String db = " ON DATABASE irc." + REST_DB;
    Assertions.assertEquals("ok", outcome("GRANT CREATE TABLE" + db + " TO gu_across", REST_DB));
    for (String catalog : Arrays.asList("ohc", "irc", "shim")) {
      Assertions.assertEquals(
          "ok [[CREATE TABLE,gu_across]]",
          outcome("SHOW GRANTS ON DATABASE " + catalog + "." + REST_DB, REST_DB),
          catalog);
    }
    Assertions.assertEquals(
        "ok",
        outcome("REVOKE CREATE TABLE ON DATABASE shim." + REST_DB + " FROM gu_across", REST_DB));
    Assertions.assertEquals("ok", outcome("SHOW GRANTS ON DATABASE ohc." + REST_DB, REST_DB));
    // Everything granted is revoked again: the two databases hold the same.
    assertSameState("grants across paths");
  }

  @Test
  void aMissingTableIsNoSuchTable() {
    String message = "User table {db}.missing cannot be found";
    Assertions.assertEquals(
        "org.apache.iceberg.exceptions.NoSuchTableException: " + message,
        both("GRANT SELECT ON TABLE {cat}.{db}.missing TO gu_reader", false, message));
    Assertions.assertEquals(
        "org.apache.iceberg.exceptions.NoSuchTableException: " + message,
        both("SHOW GRANTS ON TABLE {cat}.{db}.missing", false, message));
  }

  @Test
  void aMultiLevelNamespaceIsRefusedBeforeAnyRequest() {
    // The Java client's check, made the same way by the capability client.
    Assertions.assertEquals(
        "org.apache.iceberg.exceptions.ValidationException: Input namespace has more than one"
            + " levels {db}.x",
        both("GRANT SELECT ON TABLE {cat}.{db}.x.t TO gu_reader", false, null));
    Assertions.assertEquals(
        "org.apache.iceberg.exceptions.ValidationException: Input namespace has more than one"
            + " levels {db}.x",
        both("SHOW GRANTS ON TABLE {cat}.{db}.x.t", false, null));
  }

  // ------------------------------------------------------------------ unlock

  private String unlock(String template, boolean stranger, String nativeMessage) {
    spark.conf().set(UNLOCK_ENABLED, "true");
    try {
      return both(template, stranger, nativeMessage);
    } finally {
      spark.conf().unset(UNLOCK_ENABLED);
    }
  }

  @Test
  void unlockIsDisabledUnlessSwitchedOn() {
    Assertions.assertEquals(
        "java.lang.UnsupportedOperationException: ALTER TABLE UNLOCK is disabled. Set "
            + UNLOCK_ENABLED
            + "=true to enable it.",
        both("ALTER TABLE {cat}.{db}.grant_locked UNLOCK", false, null));
    Assertions.assertEquals("locked LEGACY", lockState(REST_DB, "grant_locked"));
  }

  @Test
  void unlockRemovesTheLockItNames() {
    Assertions.assertEquals("ok", unlock("ALTER TABLE {cat}.{db}.legacy_lock UNLOCK", false, null));
    Assertions.assertEquals("unlocked", lockState(REST_DB, "legacy_lock"));
    Assertions.assertEquals(
        "ok",
        unlock("ALTER TABLE {cat}.{db}.legacy_lock_by_reason UNLOCK REASON Legacy", false, null));
    Assertions.assertEquals("unlocked", lockState(REST_DB, "legacy_lock_by_reason"));
    Assertions.assertEquals(
        "ok", unlock("ALTER TABLE {cat}.{db}.system_lock UNLOCK REASON system_only", false, null));
    Assertions.assertEquals("unlocked", lockState(REST_DB, "system_lock"));
    // A table that holds no lock: nothing to remove, with either reason.
    Assertions.assertEquals("ok", unlock("ALTER TABLE {cat}.{db}.unlocked UNLOCK", false, null));
    Assertions.assertEquals(
        "ok", unlock("ALTER TABLE {cat}.{db}.unlocked UNLOCK REASON SYSTEM_ONLY", false, null));
  }

  @Test
  void unlockWithTheWrongReasonIsALockConflictAndKeepsTheLock() {
    String system =
        "Table {db}.system_lock_wrong_reason has a SYSTEM_ONLY lock. Remove it with"
            + " reason SYSTEM_ONLY.";
    Assertions.assertEquals(
        "java.lang.IllegalStateException: " + system,
        unlock("ALTER TABLE {cat}.{db}.system_lock_wrong_reason UNLOCK", false, system));
    Assertions.assertEquals("locked SYSTEM_ONLY", lockState(REST_DB, "system_lock_wrong_reason"));
    String legacy = "Table {db}.legacy_lock_wrong_reason has a LEGACY lock, not SYSTEM_ONLY.";
    Assertions.assertEquals(
        "java.lang.IllegalStateException: " + legacy,
        unlock(
            "ALTER TABLE {cat}.{db}.legacy_lock_wrong_reason UNLOCK REASON SYSTEM_ONLY",
            false,
            legacy));
    Assertions.assertEquals("locked LEGACY", lockState(REST_DB, "legacy_lock_wrong_reason"));
  }

  @Test
  void unlockByADeniedPrincipalIsForbiddenAndKeepsTheLock() {
    String message =
        "Operation on table {db}.denied_lock failed as user irc_stranger is unauthorized to act on"
            + " Locked table";
    Assertions.assertEquals(
        "org.apache.iceberg.exceptions.ForbiddenException: Forbidden: " + message,
        unlock("ALTER TABLE {cat}.{db}.denied_lock UNLOCK", true, message));
    Assertions.assertEquals("locked LEGACY", lockState(REST_DB, "denied_lock"));
  }

  @Test
  void unlockOfAMissingTableIsNoSuchTable() {
    String message = "User table {db}.missing cannot be found";
    Assertions.assertEquals(
        "org.apache.iceberg.exceptions.NoSuchTableException: " + message,
        unlock("ALTER TABLE {cat}.{db}.missing UNLOCK", false, message));
  }

  @Test
  void anUnknownReasonIsAParseErrorOnBothPaths() {
    // Refused by the parser, before any catalog is involved: the same parse error on both paths.
    String outcome = unlock("ALTER TABLE {cat}.{db}.unlocked UNLOCK REASON BOGUS", false, null);
    Assertions.assertTrue(
        outcome.startsWith(
                "com.linkedin.openhouse.spark.sql.catalyst.parser.extensions.OpenhouseParseException")
            && outcome.contains("BOGUS"),
        outcome);
  }

  @Test
  void theResolverOffersEveryCapabilityOnTheRestCatalog() {
    Assertions.assertEquals(
        set("policies", "grants", "unlock"),
        com.linkedin.openhouse.spark.capabilities.OpenHouseCapabilities.resolve(
                spark.sessionState().catalogManager().catalog("irc"))
            .offered());
  }
}
