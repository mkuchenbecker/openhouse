package harness

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

import org.junit.jupiter.api.Assertions.{assertEquals, assertTrue}
import org.junit.jupiter.api.Test

final class CaseCatalogTest {
  private val expectedCaseCount = 1542
  private val expectedCatalogSha256 =
    "cfe2306610b0fdf2d590f9429289a7045d7c239da59244418f66bf252bb1d895"

  @Test
  def orderedCaseCatalogMatchesBaseline(): Unit = {
    val cases = Plan.cases
    val caseIds = cases.map(_.id)
    val actualCatalogSha256 = sha256(caseIds.mkString("\n"))
    val duplicateCaseIds = caseIds.groupBy(identity).collect {
      case (caseId, occurrences) if occurrences.size > 1 => caseId
    }.toList.sorted

    assertTrue(
      duplicateCaseIds.isEmpty,
      s"case IDs must be unique; duplicates=${duplicateCaseIds.mkString(", ")}")
    assertEquals(
      expectedCaseCount,
      caseIds.size,
      s"ordered case catalog changed; count=${caseIds.size}, sha256=$actualCatalogSha256")
    assertEquals(
      expectedCatalogSha256,
      actualCatalogSha256,
      s"ordered case catalog changed; count=${caseIds.size}, sha256=$actualCatalogSha256")
  }

  private def sha256(value: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(value.getBytes(StandardCharsets.UTF_8))
      .map(byte => f"$byte%02x")
      .mkString
}
