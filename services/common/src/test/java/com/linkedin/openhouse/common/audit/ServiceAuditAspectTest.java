package com.linkedin.openhouse.common.audit;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage of {@link ServiceAuditAspect#extractIcebergEnvelopeMessage(String)}: the audited
 * failure message of a pre-serialized Iceberg REST error envelope, and null for everything that is
 * not such a document.
 */
public class ServiceAuditAspectTest {

  @Test
  public void extractsTheMessageOfAWellFormedEnvelope() {
    Assertions.assertEquals(
        "Views are disabled",
        ServiceAuditAspect.extractIcebergEnvelopeMessage(
            "{\"error\":{\"message\":\"Views are disabled\","
                + "\"type\":\"NoSuchViewException\",\"code\":404}}"));
  }

  @Test
  public void aDocumentWithoutAnErrorObjectYieldsNull() {
    Assertions.assertNull(ServiceAuditAspect.extractIcebergEnvelopeMessage("{\"nope\": 1}"));
    Assertions.assertNull(
        ServiceAuditAspect.extractIcebergEnvelopeMessage("{\"error\": \"not an object\"}"));
  }

  @Test
  public void anEnvelopeWithoutAMessageYieldsNull() {
    Assertions.assertNull(
        ServiceAuditAspect.extractIcebergEnvelopeMessage("{\"error\":{\"code\":404}}"));
    Assertions.assertNull(
        ServiceAuditAspect.extractIcebergEnvelopeMessage("{\"error\":{\"message\":null}}"));
    Assertions.assertNull(
        ServiceAuditAspect.extractIcebergEnvelopeMessage("{\"error\":{\"message\":{}}}"));
  }

  @Test
  public void nonJsonAndNonObjectBodiesYieldNull() {
    Assertions.assertNull(ServiceAuditAspect.extractIcebergEnvelopeMessage("not json at all {"));
    Assertions.assertNull(ServiceAuditAspect.extractIcebergEnvelopeMessage("[1, 2, 3]"));
    Assertions.assertNull(ServiceAuditAspect.extractIcebergEnvelopeMessage(""));
  }
}
