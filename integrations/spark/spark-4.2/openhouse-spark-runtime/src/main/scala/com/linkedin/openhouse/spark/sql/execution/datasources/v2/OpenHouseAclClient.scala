package com.linkedin.openhouse.spark.sql.execution.datasources.v2

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.spark.sql.SparkSession

import scala.collection.JavaConverters._

/**
 * Direct-HTTP client for the OpenHouse server ACL endpoints, used by the Spark-4.0 GRANT / REVOKE /
 * SHOW GRANTS execs on the REST-first lane.
 *
 * <p>Background: the legacy spark-3.x execs extracted a custom `OpenHouseCatalog` implementing
 * `SupportsGrantRevoke` and called `updateTableAclPolicies(...)`. On the Spark-4.0 REST lane the
 * Spark catalog is the STOCK `org.apache.iceberg.rest.RESTCatalog`, which does NOT implement
 * `SupportsGrantRevoke` and whose Iceberg REST surface exposes only table CRUD + snapshots +
 * policy translation (no ACL endpoint). That path is therefore a dead end.
 *
 * <p>Instead this client calls the EXISTING OpenHouse server ACL endpoints directly over HTTP. The
 * embedded OpenHouse server (and production deployments) serve BOTH the Iceberg REST catalog under
 * `/iceberg` AND the tables/databases ACL surface `/v1/databases/.../aclPolicies` on the same
 * host/port. The base server URI is derived from the Spark catalog's `uri` config by stripping the
 * trailing `/iceberg`; the same bearer `token` config is presented.
 *   - Update: `PATCH  {base}/v1/databases/{db}/tables/{t}/aclPolicies` (or `.../databases/{db}/...`)
 *   - Read:   `GET    {base}/v1/databases/{db}/tables/{t}/aclPolicies`
 */
object OpenHouseAclClient {

  /**
   * Privilege -> role mapping. Mirrors the authoritative
   * `integrations/java/iceberg-1.2/.../javaclient/mapper/Privileges.java` enum EXACTLY. Inlined here
   * (like the granularity tokens in the AST builder) so this Spark-4.0 runtime module needs no
   * cross-module dependency on the java-client.
   */
  private val PRIVILEGE_TO_ROLE: Map[String, String] = Map(
    "SELECT" -> "TABLE_VIEWER",
    "DESCRIBE" -> "TABLE_VIEWER",
    "MANAGE GRANTS" -> "ACL_EDITOR",
    "ALTER" -> "TABLE_ADMIN",
    "CREATE TABLE" -> "TABLE_CREATOR"
  )

  /**
   * Role -> privilege reverse mapping used by SHOW GRANTS. Mirrors
   * `Privileges.fromRole(role).getPrivilege()` (see the java-client `SparkMapper.toAclPolicyDto`):
   * `fromRole` returns the FIRST enum value with a matching role, so `TABLE_VIEWER` resolves to
   * `SELECT` (declared before `DESCRIBE`).
   */
  private val ROLE_TO_PRIVILEGE: Map[String, String] = Map(
    "TABLE_VIEWER" -> "SELECT",
    "ACL_EDITOR" -> "MANAGE GRANTS",
    "TABLE_ADMIN" -> "ALTER",
    "TABLE_CREATOR" -> "CREATE TABLE"
  )

  def privilegeToRole(privilege: String): String =
    PRIVILEGE_TO_ROLE.getOrElse(
      privilege,
      throw new UnsupportedOperationException(
        s"Unsupported privilege '$privilege' for ACL grant/revoke mapping"))

  def roleToPrivilege(role: String): String = ROLE_TO_PRIVILEGE.getOrElse(role, role)

  private val httpClient: HttpClient = HttpClient.newHttpClient()

  // Jackson is provided on the runtime classpath by both Spark 4.0 and Iceberg.
  private val MAPPER: ObjectMapper = new ObjectMapper()

  /**
   * Derives the OpenHouse server base URI (with the trailing `/iceberg` stripped) and the bearer
   * token from the active Spark catalog configuration, i.e. `spark.sql.catalog.<name>.uri` and
   * `spark.sql.catalog.<name>.token`.
   */
  def serverBaseAndToken(spark: SparkSession, catalogName: String): (String, String) = {
    val prefix = s"spark.sql.catalog.$catalogName."
    val uri = spark.conf.get(prefix + "uri")
    val base = uri.stripSuffix("/").stripSuffix("/iceberg")
    val token = spark.conf.getOption(prefix + "token").getOrElse("")
    (base, token)
  }

  private def withAuth(builder: HttpRequest.Builder, token: String): HttpRequest.Builder =
    if (token != null && token.nonEmpty) builder.header("Authorization", s"Bearer $token") else builder

  /**
   * Issues the `PATCH .../aclPolicies` grant/revoke request. `aclPath` is the full server path, e.g.
   * `/v1/databases/db/tables/t/aclPolicies`. Throws on any non-2xx response.
   */
  def updateAclPolicies(
      baseUri: String,
      token: String,
      aclPath: String,
      isGrant: Boolean,
      role: String,
      principal: String): Unit = {
    val body = MAPPER.createObjectNode()
    body.put("operation", if (isGrant) "GRANT" else "REVOKE")
    body.put("role", role)
    body.put("principal", principal)
    val json = body.toString

    val request = withAuth(
      HttpRequest
        .newBuilder()
        .uri(URI.create(baseUri + aclPath))
        .header("Content-Type", "application/json"),
      token)
      .method("PATCH", HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
      .build()

    val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() / 100 != 2) {
      throw new RuntimeException(
        s"Grant/Revoke ACL update failed: PATCH $baseUri$aclPath returned HTTP " +
          s"${response.statusCode()}: ${response.body()}")
    }
  }

  /**
   * Issues the `GET .../aclPolicies` request and returns the (privilege, principal) rows, mapping
   * each server-side `role` back to its privilege. Throws on any non-2xx response.
   */
  def getAclPolicies(baseUri: String, token: String, aclPath: String): Seq[(String, String)] = {
    val request = withAuth(
      HttpRequest.newBuilder().uri(URI.create(baseUri + aclPath)),
      token).GET().build()

    val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() / 100 != 2) {
      throw new RuntimeException(
        s"Show grants failed: GET $baseUri$aclPath returned HTTP " +
          s"${response.statusCode()}: ${response.body()}")
    }

    val root = MAPPER.readTree(response.body())
    val results = root.get("results")
    if (results == null || !results.isArray) {
      Seq.empty
    } else {
      results.elements().asScala.toSeq.map { node =>
        val role = if (node.hasNonNull("role")) node.get("role").asText() else ""
        val principal = if (node.hasNonNull("principal")) node.get("principal").asText() else ""
        (roleToPrivilege(role), principal)
      }
    }
  }
}
