package com.linkedin.openhouse.analyzer.entity;

import com.linkedin.openhouse.analyzer.config.TablePropertiesConverter;
import com.linkedin.openhouse.analyzer.config.TableStatsConverter;
import com.linkedin.openhouse.analyzer.model.TableStats;
import java.util.Map;
import javax.persistence.Column;
import javax.persistence.Convert;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Read-only JPA entity for the optimizer {@code table_stats} table. Written by the Tables Service
 * via the optimizer REST endpoint; read by the Analyzer to enumerate tables and check stats.
 */
@Entity
@Table(name = "table_stats")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TableStatsEntity {

  @Id
  @Column(name = "table_uuid")
  private String tableUuid;

  @Column(name = "database_id")
  private String databaseId;

  @Column(name = "table_name")
  private String tableName;

  @Convert(converter = TableStatsConverter.class)
  @Column(name = "stats")
  private TableStats stats;

  @Convert(converter = TablePropertiesConverter.class)
  @Column(name = "table_properties")
  private Map<String, String> tableProperties;
}
