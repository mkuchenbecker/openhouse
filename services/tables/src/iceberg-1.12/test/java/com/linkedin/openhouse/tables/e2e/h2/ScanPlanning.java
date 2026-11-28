package com.linkedin.openhouse.tables.e2e.h2;

import com.linkedin.openhouse.common.security.DummyTokenInterceptor.DummySecurityJWT;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import javax.servlet.Filter;
import javax.servlet.http.HttpServletRequest;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.ContentFile;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.PartitionKey;
import org.apache.iceberg.Schema;
import org.apache.iceberg.StructLike;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableScan;
import org.apache.iceberg.data.GenericFileWriterFactory;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.IcebergGenerics;
import org.apache.iceberg.data.InternalRecordWrapper;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.deletes.BaseDVFileWriter;
import org.apache.iceberg.deletes.EqualityDeleteWriter;
import org.apache.iceberg.deletes.PositionDelete;
import org.apache.iceberg.deletes.PositionDeleteWriter;
import org.apache.iceberg.expressions.Expression;
import org.apache.iceberg.expressions.ExpressionParser;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.io.DataWriter;
import org.apache.iceberg.io.OutputFileFactory;
import org.apache.iceberg.rest.RESTCatalog;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * What the scan planning tests share: two unmodified Iceberg 1.12 {@link RESTCatalog}s on the
 * service, one planning on the client (its default) and one configured with {@code
 * scan-planning-mode=server}; a record of the requests the service received; writers for data
 * files, position and equality delete files and deletion vectors; and the comparison of two plans.
 *
 * <p>A plan is compared as the set of its tasks, each as: data file path, start, length, partition
 * spec, record count, the column stats kept, its delete files (path, content, format, record count,
 * referenced data file, content offset and size, equality field ids), and its residual filter as
 * JSON.
 */
final class ScanPlanning {

  /** The cluster property that serves the scan planning routes, which are off by default. */
  static final String ENABLED =
      com.linkedin.openhouse.cluster.configs.ClusterProperties.IRC_SCAN_PLANNING_ENABLED + "=true";

  private ScanPlanning() {}

  /** The catalog property that makes a 1.12 client plan on the server. */
  static final String SCAN_PLANNING_MODE = "scan-planning-mode";

  /** Every request the service received, as {@code METHOD path}, in order. */
  static final List<String> REQUESTS = new CopyOnWriteArrayList<>();

  /** Records every request the service receives. */
  @TestConfiguration
  static class RecordRequests {
    @Bean
    Filter scanPlanningRequestRecorder() {
      return (request, response, chain) -> {
        HttpServletRequest http = (HttpServletRequest) request;
        REQUESTS.add(http.getMethod() + " " + http.getRequestURI());
        chain.doFilter(request, response);
      };
    }
  }

  /** The plan requests received so far. */
  static List<String> planRequests() {
    return REQUESTS.stream().filter(r -> r.endsWith("/plan")).collect(Collectors.toList());
  }

  static RESTCatalog catalog(int port, String principal, Map<String, String> extra) {
    Map<String, String> properties = new HashMap<>();
    properties.put("uri", "http://localhost:" + port + "/iceberg");
    properties.put("token", token(principal));
    properties.putAll(extra);
    return (RESTCatalog)
        CatalogUtil.loadCatalog(
            RESTCatalog.class.getName(), "openhouse", properties, new Configuration());
  }

  /** A bearer token the service's dummy token interceptor accepts for {@code principal}. */
  static String token(String principal) {
    try {
      return new DummySecurityJWT(principal).buildNoopJWT();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** A catalog that plans on the client, as a default-configured client does. */
  static RESTCatalog clientPlanning(int port) {
    return catalog(port, "irc_planner", Collections.emptyMap());
  }

  /** A catalog configured to plan on the server. */
  static RESTCatalog serverPlanning(int port) {
    return catalog(port, "irc_planner", Collections.singletonMap(SCAN_PLANNING_MODE, "server"));
  }

  // ---------------------------------------------------------------- comparing plans

  static List<String> plan(TableScan scan) {
    List<String> tasks = new ArrayList<>();
    try (CloseableIterable<FileScanTask> planned = scan.planFiles()) {
      planned.forEach(task -> tasks.add(describe(task)));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    Collections.sort(tasks);
    return tasks;
  }

  static String describe(FileScanTask task) {
    DataFile file = task.file();
    List<String> deletes =
        task.deletes().stream().map(ScanPlanning::describe).sorted().collect(Collectors.toList());
    return String.join(
        " | ",
        file.location(),
        "start=" + task.start(),
        "length=" + task.length(),
        "spec=" + file.specId(),
        "partition=" + file.partition(),
        "records=" + file.recordCount(),
        "stats=" + statsOf(file),
        "deletes=" + deletes,
        "residual=" + ExpressionParser.toJson(task.residual()));
  }

  private static String describe(DeleteFile delete) {
    return String.join(
        ",",
        delete.location(),
        String.valueOf(delete.content()),
        String.valueOf(delete.format()),
        "records=" + delete.recordCount(),
        "referenced=" + delete.referencedDataFile(),
        "offset=" + delete.contentOffset(),
        "size=" + delete.contentSizeInBytes(),
        "equality=" + delete.equalityFieldIds());
  }

  private static String statsOf(ContentFile<?> file) {
    return "lower="
        + (file.lowerBounds() == null ? null : new TreeMap<>(file.lowerBounds()).keySet())
        + ",upper="
        + (file.upperBounds() == null ? null : new TreeMap<>(file.upperBounds()).keySet())
        + ",values="
        + (file.valueCounts() == null ? null : new TreeMap<>(file.valueCounts()).keySet());
  }

  /** The rows a scan of {@code table}, as {@code configure} narrows it, reads, sorted. */
  static List<String> rows(Table table, Expression filter, String... columns) {
    IcebergGenerics.ScanBuilder builder = IcebergGenerics.read(table).where(filter);
    if (columns.length > 0) {
      builder = builder.select(columns);
    }
    List<String> rows = new ArrayList<>();
    try (CloseableIterable<Record> records = builder.build()) {
      records.forEach(record -> rows.add(record.toString()));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    Collections.sort(rows);
    return rows;
  }

  /** Narrows a scan; the identity when there is nothing to narrow. */
  static UnaryOperator<TableScan> asIs() {
    return scan -> scan;
  }

  // ---------------------------------------------------------------- writing

  private static final AtomicInteger TASK = new AtomicInteger();

  private static OutputFileFactory files(Table table, FileFormat format) {
    return OutputFileFactory.builderFor(table, 1, TASK.incrementAndGet()).format(format).build();
  }

  static Record record(Schema schema, Object... values) {
    GenericRecord record = GenericRecord.create(schema);
    for (int i = 0; i < values.length; i++) {
      record.set(i, values[i]);
    }
    return record;
  }

  /** The partition {@code record} belongs to in {@code table}'s spec; null if unpartitioned. */
  static StructLike partitionOf(Table table, Record record) {
    if (table.spec().isUnpartitioned()) {
      return null;
    }
    PartitionKey key = new PartitionKey(table.spec(), table.schema());
    key.partition(new InternalRecordWrapper(table.schema().asStruct()).wrap(record));
    return key;
  }

  /** A Parquet data file of {@code records}, which share one partition. */
  static DataFile dataFile(Table table, Record... records) {
    StructLike partition = partitionOf(table, records[0]);
    GenericFileWriterFactory factory =
        new GenericFileWriterFactory.Builder(table).dataFileFormat(FileFormat.PARQUET).build();
    DataWriter<Record> writer =
        factory.newDataWriter(
            files(table, FileFormat.PARQUET).newOutputFile(table.spec(), partition),
            table.spec(),
            partition);
    try (DataWriter<Record> closing = writer) {
      Arrays.stream(records).forEach(closing::write);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return writer.toDataFile();
  }

  /** A Parquet position delete file (format v2) removing {@code positions} of {@code data}. */
  static DeleteFile positionDeletes(Table table, DataFile data, long... positions) {
    StructLike partition = data.partition();
    GenericFileWriterFactory factory =
        new GenericFileWriterFactory.Builder(table).deleteFileFormat(FileFormat.PARQUET).build();
    PositionDeleteWriter<Record> writer =
        factory.newPositionDeleteWriter(
            files(table, FileFormat.PARQUET).newOutputFile(table.spec(), partition),
            table.spec(),
            partition);
    try (PositionDeleteWriter<Record> closing = writer) {
      for (long position : positions) {
        closing.write(PositionDelete.<Record>create().set(data.location(), position));
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return writer.toDeleteFile();
  }

  /** A Parquet equality delete file on {@code column}, deleting {@code keys}. */
  static DeleteFile equalityDeletes(
      Table table, StructLike partition, String column, Object... keys) {
    Schema deleteSchema = table.schema().select(column);
    GenericFileWriterFactory factory =
        new GenericFileWriterFactory.Builder(table)
            .deleteFileFormat(FileFormat.PARQUET)
            .equalityFieldIds(new int[] {table.schema().findField(column).fieldId()})
            .equalityDeleteRowSchema(deleteSchema)
            .build();
    EqualityDeleteWriter<Record> writer =
        factory.newEqualityDeleteWriter(
            files(table, FileFormat.PARQUET).newOutputFile(table.spec(), partition),
            table.spec(),
            partition);
    try (EqualityDeleteWriter<Record> closing = writer) {
      for (Object key : keys) {
        closing.write(record(deleteSchema, key));
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return writer.toDeleteFile();
  }

  /** A deletion vector (format v3) removing {@code positions} of {@code data}. */
  static List<DeleteFile> deletionVector(Table table, DataFile data, long... positions) {
    BaseDVFileWriter writer = new BaseDVFileWriter(files(table, FileFormat.PUFFIN), path -> null);
    try (BaseDVFileWriter closing = writer) {
      for (long position : positions) {
        closing.delete(data.location(), position, table.spec(), data.partition());
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return writer.result().deleteFiles();
  }
}
