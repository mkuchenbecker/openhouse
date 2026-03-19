package com.linkedin.openhouse.tables.api.spec.v0.request.components;

import io.swagger.v3.oas.annotations.media.Schema;
import javax.validation.Valid;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * PerformanceTierConfig controls the HDFS block replication factor for a table. Higher tiers use
 * more storage but provide better read performance.
 *
 * <p>When {@code auto} is true the system manages {@code resolved}; when false the customer pins
 * it.
 */
@Builder(toBuilder = true)
@EqualsAndHashCode
@Getter
@AllArgsConstructor(access = AccessLevel.PROTECTED)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PerformanceTierConfig {

  @Schema(
      description =
          "When true the system (autotuner) manages the resolved tier. When false the customer "
              + "pins the resolved tier and the system will not change it.",
      example = "true")
  @Valid
  boolean auto;

  @Schema(
      description =
          "The effective performance tier. Always present. For auto=true this is set by the "
              + "system; for auto=false this is the customer-specified tier.",
      example = "STANDARD")
  @Valid
  PerformanceTier resolved;
}
