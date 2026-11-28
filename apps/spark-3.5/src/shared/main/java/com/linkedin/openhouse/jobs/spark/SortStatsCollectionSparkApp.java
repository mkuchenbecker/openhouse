package com.linkedin.openhouse.jobs.spark;

import com.google.common.annotations.VisibleForTesting;
import com.linkedin.openhouse.common.metrics.DefaultOtelConfig;
import com.linkedin.openhouse.common.metrics.OtelEmitter;
import com.linkedin.openhouse.jobs.spark.state.StateManager;
import com.linkedin.openhouse.jobs.util.AppsOtelEmitter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.iceberg.BaseMetadataTable;
import org.apache.iceberg.BaseTable;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.spark.source.SparkTable;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan;
import org.apache.spark.sql.execution.datasources.v2.DataSourceV2Relation;
import scala.collection.JavaConverters;

@Slf4j
public class SortStatsCollectionSparkApp extends BaseTableSparkApp {
  private static final int TARGET_FILE_SIZE = 512 * 1024 * 1024; // 512MB

  @VisibleForTesting
  static final String SORT_ORDER = "header.memberId asc nulls last, header.time asc nulls last";

  private static final String COMPRESSION_RATE_KEY = "sort-compression-rate";

  public SortStatsCollectionSparkApp(
      String jobId, StateManager stateManager, String fqtn, OtelEmitter otelEmitter) {
    super(jobId, stateManager, fqtn, otelEmitter);
  }

  public static void main(String[] args) {
    OtelEmitter otelEmitter =
        new AppsOtelEmitter(Arrays.asList(DefaultOtelConfig.getOpenTelemetry()));
    createApp(args, otelEmitter).run();
  }

  public static SortStatsCollectionSparkApp createApp(String[] args, OtelEmitter otelEmitter) {
    List<Option> extraOptions = new ArrayList<>();
    extraOptions.add(new Option("t", "tableName", true, "Fully-qualified table name"));
    CommandLine cmdLine = createCommandLine(args, extraOptions);
    return new SortStatsCollectionSparkApp(
        getJobId(cmdLine),
        createStateManager(cmdLine, otelEmitter),
        cmdLine.getOptionValue("tableName"),
        otelEmitter);
  }

  @Override
  protected void runInner(Operations ops) throws Exception {
    SparkSession spark = ops.spark();
    String tempTableName = getTempTableName(fqtn);
    String datepartition = getDatePartition();
    try {
      // sample average record size from table and calculate rows needed for target file size
      Dataset<Row> recordSize =
          spark.sql(
              String.format(
                  "select sum(file_size_in_bytes) * 1.0 / sum(record_count) as avg_bytes_per_record "
                      + "from openhouse.%s.data_files limit 10",
                  fqtn));
      long avgRecordSize = recordSize.first().getDecimal(0).longValue();
      int numOfRowsNeeded = (int) (TARGET_FILE_SIZE / avgRecordSize);
      log.info(
          "Avg record size for table {} is {}, copy {} rows for sort stats collection",
          fqtn,
          avgRecordSize,
          numOfRowsNeeded);
      // create a temporary table with limited rows for the given partition
      // if the partition doesn't provide enough rows, the whole partition will be copied
      BaseTable source = sourceTableOf(recordSize);
      spark.sql(
          createTempTableStatement(
              tempTableName,
              fqtn,
              datepartition,
              numOfRowsNeeded,
              source == null ? 0 : source.operations().current().formatVersion(),
              source == null
                  ? null
                  : source.properties().get(TableProperties.DEFAULT_FILE_FORMAT)));
      double sizeBefore =
          spark
              .sql(
                  String.format(
                      "select sum(file_size_in_bytes)/1000/1000 from openhouse.%s.data_files",
                      tempTableName))
              .first()
              .getDouble(0);
      // call rewrite procedure to rewrite data files with sort strategy
      rewriteSorted(spark, tempTableName);
      double sizeAfter =
          spark
              .sql(
                  String.format(
                      "select sum(file_size_in_bytes)/1000/1000 from openhouse.%s.data_files",
                      tempTableName))
              .first()
              .getDouble(0);
      // calculate compression rate and store it as table property
      double compressionRate = (sizeBefore - sizeAfter) / sizeBefore * 100;
      spark.sql(
          String.format(
              "alter table openhouse.%s set tblproperties ('%s'='%f')",
              fqtn, COMPRESSION_RATE_KEY, compressionRate));
      log.info(
          "Sort stats collection for table {} completed. Size before: {} MB, size after: {} MB, compression rate: {}%",
          fqtn, sizeBefore, sizeAfter, String.format("%.2f", compressionRate));
    } catch (Exception e) {
      log.error("Error during sort stats collection for table {}", fqtn, e);
      throw e;
    } finally {
      spark.sql(String.format("drop table if exists openhouse.%s", tempTableName));
    }
  }

  /**
   * The statement that copies up to {@code rows} rows of {@code datepartition} of {@code fqtn} into
   * the temporary table. A format v2 (or v1) source gives exactly the statement the app always ran,
   * so the copy takes the service's defaults; so does an unknown version (0). A format v3 source
   * gives a v3 copy, with the source's write format if it names one: the service refuses v3 column
   * types (variant, for one) in a v2 table, and a v3 type may need the format the source uses
   * (variant is written in Parquet).
   */
  @VisibleForTesting
  static String createTempTableStatement(
      String tempTableName,
      String fqtn,
      String datepartition,
      int rows,
      int formatVersion,
      String writeFormat) {
    String properties = "";
    if (formatVersion >= 3) {
      properties =
          String.format(" tblproperties ('%s'='%d'", TableProperties.FORMAT_VERSION, formatVersion)
              + (writeFormat == null
                  ? ""
                  : String.format(", '%s'='%s'", TableProperties.DEFAULT_FILE_FORMAT, writeFormat))
              + ")";
    }
    return String.format(
        "create table openhouse.%s%s as select * from spark_catalog.%s where datepartition = '%s' limit %d",
        tempTableName, properties, fqtn, datepartition, rows);
  }

  private String getTempTableName(String fqtn) {
    return fqtn + "_sample";
  }

  private String getDatePartition() {
    LocalDateTime yesterday = LocalDateTime.now().minusDays(1);
    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH");
    return yesterday.format(formatter);
  }

  /**
   * The Iceberg table behind the {@code data_files} metadata table that {@code metadataQuery}
   * reads, as Spark resolved it for that query, or null if its plan names none. Read from the query
   * the app already ran, so finding it costs no catalog and no table load.
   */
  @VisibleForTesting
  static BaseTable sourceTableOf(Dataset<Row> metadataQuery) {
    for (LogicalPlan leaf :
        JavaConverters.seqAsJavaList(metadataQuery.queryExecution().analyzed().collectLeaves())) {
      if (leaf instanceof DataSourceV2Relation
          && ((DataSourceV2Relation) leaf).table() instanceof SparkTable) {
        Table table = ((SparkTable) ((DataSourceV2Relation) leaf).table()).table();
        if (table instanceof BaseMetadataTable) {
          return ((BaseMetadataTable) table).table();
        }
      }
    }
    return null;
  }

  /**
   * Rewrites the data files of {@code tempTableName} with the sort strategy, {@link #SORT_ORDER}
   * and the app's options: the {@code rewrite_data_files} call the app has always made.
   */
  @VisibleForTesting
  static void rewriteSorted(SparkSession spark, String tempTableName) {
    spark.sql(
        String.format(
            "call openhouse.system.rewrite_data_files("
                + "table => '%s', options => map(%s), strategy => 'sort', sort_order => '%s')",
            tempTableName, getRewriteOptions(), SORT_ORDER));
  }

  private static String getRewriteOptions() {
    Map<String, String> options = new HashMap();
    options.put("target-file-size-bytes", String.valueOf(TARGET_FILE_SIZE));
    options.put("rewrite-all", "true");
    options.put("partial-progress.enabled", "true");

    return options.entrySet().stream()
        .map(entry -> String.format("'%s', '%s'", entry.getKey(), entry.getValue()))
        .collect(Collectors.joining(", "));
  }
}
