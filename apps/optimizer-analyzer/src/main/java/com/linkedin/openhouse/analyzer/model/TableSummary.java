package com.linkedin.openhouse.analyzer.model;

import java.util.Collections;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Internal representation of a table, decoupled from the Tables Service API response model. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TableSummary {

  private String tableUuid;
  private String databaseId;
  private String tableId;

  @Builder.Default private Map<String, String> tableProperties = Collections.emptyMap();
}
