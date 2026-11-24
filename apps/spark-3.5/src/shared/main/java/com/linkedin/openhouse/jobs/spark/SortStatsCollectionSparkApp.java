package com.linkedin.openhouse.jobs.spark;

import com.google.common.annotations.VisibleForTesting;
import com.linkedin.openhouse.common.metrics.DefaultOtelConfig;
import com.linkedin.openhouse.common.metrics.OtelEmitter;
import com.linkedin.openhouse.jobs.spark.state.StateManager;
import com.linkedin.openhouse.jobs.util.AppsOtelEmitter;
import java.lang.reflect.Field;
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
import org.apache.iceberg.HasTableOperations;
import org.apache.iceberg.Schema;
import org.apache.iceberg.SortOrder;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.spark.ExtendedParser;
import org.apache.iceberg.spark.Spark3Util;
import org.apache.iceberg.spark.actions.SparkActions;
import org.apache.spark.sql.AnalysisException;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.catalyst.parser.ParserInterface;

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
      long avgRecordSize =
          spark
              .sql(
                  String.format(
                      "select sum(file_size_in_bytes) * 1.0 / sum(record_count) as avg_bytes_per_record "
                          + "from openhouse.%s.data_files limit 10",
                      fqtn))
              .first()
              .getDecimal(0)
              .longValue();
      int numOfRowsNeeded = (int) (TARGET_FILE_SIZE / avgRecordSize);
      log.info(
          "Avg record size for table {} is {}, copy {} rows for sort stats collection",
          fqtn,
          avgRecordSize,
          numOfRowsNeeded);
      // create a temporary table with limited rows for the given partition
      // if the partition doesn't provide enough rows, the whole partition will be copied
      Table source = ops.getTable(fqtn);
      spark.sql(
          createTempTableStatement(
              tempTableName,
              fqtn,
              datepartition,
              numOfRowsNeeded,
              ((HasTableOperations) source).operations().current().formatVersion(),
              source.properties().get(TableProperties.DEFAULT_FILE_FORMAT)));
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
   * so the copy takes the service's defaults. A format v3 source gives a v3 copy, with the source's
   * write format if it names one: the service refuses v3 column types (variant, for one) in a v2
   * table, and a v3 type may need the format the source uses (variant is written in Parquet).
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
   * Rewrites the data files of {@code tempTableName} with the sort strategy, {@link #SORT_ORDER}
   * and the app's options. Where Iceberg's own parse of a sort order works on this session (Iceberg
   * 1.12, on both Spark lines), this is the {@code rewrite_data_files} call the app always made.
   *
   * <p>LinkedIn's Iceberg 1.5.2 parses the procedure's {@code sort_order} with the session's
   * outermost parser only, and fails unless that is Iceberg's; the OpenHouse SQL extensions wrap
   * Iceberg's parser when they are listed after Iceberg's, as jobs.yaml lists them. There, and only
   * there, the order is parsed with Iceberg's parser below OpenHouse's and given to the rewrite
   * action the procedure runs (same table, options and sort order; no filter, as the call has
   * none).
   */
  @VisibleForTesting
  static void rewriteSorted(SparkSession spark, String tempTableName) throws Exception {
    ExtendedParser icebergParser =
        sessionParsesSortOrder(spark) ? null : icebergParserBelow(spark.sessionState().sqlParser());
    if (icebergParser == null) {
      spark.sql(
          String.format(
              "call openhouse.system.rewrite_data_files("
                  + "table => '%s', options => map(%s), strategy => 'sort', sort_order => '%s')",
              tempTableName, getRewriteOptions(), SORT_ORDER));
      return;
    }
    log.info(
        "Session parser {} is not Iceberg's and this Iceberg does not look below it; rewriting {}"
            + " with the sort order parsed by {}",
        spark.sessionState().sqlParser().getClass().getName(),
        tempTableName,
        icebergParser.getClass().getName());
    Table table = Spark3Util.loadIcebergTable(spark, "openhouse." + tempTableName);
    SparkActions.get(spark)
        .rewriteDataFiles(table)
        .options(rewriteOptions())
        .sort(sortOrder(icebergParser, table.schema()))
        .execute();
  }

  /**
   * Whether Iceberg's {@code ExtendedParser.parseSortOrder}, the call {@code rewrite_data_files}
   * makes for {@code sort_order}, finds a parser on this session. Parsing has no side effect.
   */
  @VisibleForTesting
  static boolean sessionParsesSortOrder(SparkSession spark) {
    try {
      ExtendedParser.parseSortOrder(spark, SORT_ORDER);
      return true;
    } catch (IllegalStateException e) {
      return false;
    }
  }

  /**
   * The first Iceberg {@link ExtendedParser} in the chain of parsers starting at {@code parser},
   * following each parser's delegate field (as Iceberg 1.12's {@code parseSortOrder} does), or null
   * if there is none.
   */
  @VisibleForTesting
  static ExtendedParser icebergParserBelow(ParserInterface parser) {
    ParserInterface current = parser;
    while (current != null) {
      if (current instanceof ExtendedParser) {
        return (ExtendedParser) current;
      }
      ParserInterface next = null;
      try {
        for (Class<?> c = current.getClass(); c != null && next == null; c = c.getSuperclass()) {
          for (Field field : c.getDeclaredFields()) {
            field.setAccessible(true);
            Object value = field.get(current);
            if (value instanceof ParserInterface && value != current) {
              next = (ParserInterface) value;
              break;
            }
          }
        }
      } catch (ReflectiveOperationException | RuntimeException e) {
        log.warn("Cannot read the delegate of parser {}", current.getClass().getName(), e);
        return null;
      }
      current = next;
    }
    return null;
  }

  /**
   * {@link #SORT_ORDER} for {@code schema}, built from {@code parser}'s parse as {@code
   * rewrite_data_files} builds it.
   */
  @VisibleForTesting
  static SortOrder sortOrder(ExtendedParser parser, Schema schema) throws AnalysisException {
    SortOrder.Builder builder = SortOrder.builderFor(schema);
    for (ExtendedParser.RawOrderField field : parser.parseSortOrder(SORT_ORDER)) {
      builder.sortBy(field.term(), field.direction(), field.nullOrder());
    }
    return builder.build();
  }

  private static Map<String, String> rewriteOptions() {
    Map<String, String> options = new HashMap();
    options.put("target-file-size-bytes", String.valueOf(TARGET_FILE_SIZE));
    options.put("rewrite-all", "true");
    options.put("partial-progress.enabled", "true");
    return options;
  }

  private static String getRewriteOptions() {
    Map<String, String> options = rewriteOptions();

    return options.entrySet().stream()
        .map(entry -> String.format("'%s', '%s'", entry.getKey(), entry.getValue()))
        .collect(Collectors.joining(", "));
  }
}
