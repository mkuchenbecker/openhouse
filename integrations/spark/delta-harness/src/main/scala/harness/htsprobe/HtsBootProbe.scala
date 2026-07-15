package harness.htsprobe

// Standalone probe (NOT part of the harness) that boots ONLY the real House Table Service as a
// Spring Boot context on an ephemeral port and confirms it answers. This isolates the single
// biggest unknown in the HTS-embed work — does the real HTS co-boot cleanly on H2 in-process —
// before wiring it into the harness. Mirrors services/.../e2e/SpringH2HtsApplication's annotation
// set (test-scope, so replicated here) so the boot is faithful to the repo's own HTS-on-H2 e2e.

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration
import org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration
import org.springframework.context.annotation.ComponentScan
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.web.context.WebServerApplicationContext

// Exclude Spring Security auto-config: spring-security-web is only partially present on the harness
// classpath (WebInvocationPrivilegeEvaluator absent), and the harness runs unauthenticated anyway —
// exactly what SpringH2TestApplication (the tables boot) does for the same reason.
@SpringBootApplication(exclude = Array(classOf[SecurityAutoConfiguration], classOf[ManagementWebSecurityAutoConfiguration]))
@ComponentScan(basePackages = Array(
  "com.linkedin.openhouse.housetables.api",
  "com.linkedin.openhouse.housetables.dto.mapper",
  "com.linkedin.openhouse.housetables.controller",
  "com.linkedin.openhouse.housetables.services",
  "com.linkedin.openhouse.common.exception.handler",
  "com.linkedin.openhouse.common.audit",
  "com.linkedin.openhouse.housetables.repository",
  "com.linkedin.openhouse.housetables.properties",
  "com.linkedin.openhouse.housetables.config",
  "com.linkedin.openhouse.cluster.configs",
  "com.linkedin.openhouse.cluster.storage"))
@EntityScan(basePackages = Array("com.linkedin.openhouse.housetables.model"))
class HtsBootApp

object HtsBootProbe {
  def main(args: Array[String]): Unit = {
    val ctx =
      new SpringApplicationBuilder(classOf[HtsBootApp])
        .properties(
          "server.port=0",
          "cluster.storage.root-path=/tmp/hts-probe",
          "cluster.tables.allowed-client-name-values=trino,spark")
        .run()

    val port = ctx.asInstanceOf[WebServerApplicationContext].getWebServer.getPort
    println(s"HTS-PROBE booted on port=$port")

    def get(path: String): (Int, String) = {
      val conn = new java.net.URL(s"http://localhost:$port$path").openConnection().asInstanceOf[java.net.HttpURLConnection]
      conn.setRequestMethod("GET")
      val code = conn.getResponseCode
      val is = if (code < 400) conn.getInputStream else conn.getErrorStream
      val body = if (is == null) "" else scala.io.Source.fromInputStream(is, "UTF-8").mkString
      (code, body)
    }

    // 1) actuator health — proves the web context + datasource came up
    val (hc, hb) = get("/actuator/health")
    println(s"HTS-PROBE GET /actuator/health -> $hc  ${hb.take(200)}")

    // 2) the real user-tables endpoint — proves controller->handler->service->JDBC on H2 is live.
    //    GET requires databaseId+tableId; a missing table returns 404 (NOT a 5xx). Either a 200 or
    //    a 404 proves the full controller->handler->service->JDBC path executed against H2.
    val (uc, ub) = get("/hts/tables?databaseId=probe_db&tableId=nope")
    println(s"HTS-PROBE GET /hts/tables -> $uc  ${ub.take(300)}")

    val ok = (hc == 200) && (uc < 500)
    println(s"HTS-PROBE result: ${if (ok) "PASS" else "FAIL"}")
    try ctx.close() catch { case _: Throwable => () }
    System.exit(if (ok) 0 else 1)
  }
}
