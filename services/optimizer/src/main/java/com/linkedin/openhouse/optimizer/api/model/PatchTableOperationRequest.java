package com.linkedin.openhouse.optimizer.api.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Request body for {@code PATCH /v1/table-operations/{id}}. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PatchTableOperationRequest {

  /** Target status. Only {@code SUCCESS} and {@code FAILED} are accepted. */
  private OperationStatus status;

  /** Optional result metrics from the Spark job execution. */
  private OperationMetrics metrics;
}
