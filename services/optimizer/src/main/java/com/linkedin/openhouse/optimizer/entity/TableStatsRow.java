package com.linkedin.openhouse.optimizer.entity;

import com.linkedin.openhouse.optimizer.api.model.TableStats;
import com.linkedin.openhouse.optimizer.config.TablePropertiesConverter;
import com.linkedin.openhouse.optimizer.config.TableStatsConverter;
import java.util.Map;
import javax.persistence.Column;
import javax.persistence.Convert;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * JPA entity representing a per-table stats snapshot in the optimizer DB.
 *
 * <p>Written by the Tables Service on every Iceberg commit. Read by the Analyzer directly via JPA
 * to enumerate tables and check scheduling eligibility.
 */
@Entity
@Table(name = "table_stats")
@Getter
@EqualsAndHashCode
@Builder(toBuilder = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
public class TableStatsRow {

  @Id
  @Column(name = "table_uuid", nullable = false, length = 36)
  private String tableUuid;

  @Column(name = "database_id", nullable = false, length = 255)
  private String databaseId;

  @Column(name = "table_name", nullable = false, length = 255)
  private String tableName;

  @Convert(converter = TableStatsConverter.class)
  @Column(name = "stats")
  private TableStats stats;

  @Convert(converter = TablePropertiesConverter.class)
  @Column(name = "table_properties")
  private Map<String, String> tableProperties;
}
