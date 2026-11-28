package com.linkedin.openhouse.spark42.irc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.linkedin.openhouse.common.security.DummyTokenInterceptor;
import com.linkedin.openhouse.housetables.client.model.ToggleStatus;
import com.linkedin.openhouse.tables.authorization.AuthorizationHandler;
import com.linkedin.openhouse.tables.authorization.OpaAuthorizationHandler;
import com.linkedin.openhouse.tables.authorization.Privileges;
import com.linkedin.openhouse.tables.model.DatabaseDto;
import com.linkedin.openhouse.tables.model.TableDto;
import com.linkedin.openhouse.tables.services.ViewsFeatureGate;
import com.linkedin.openhouse.tables.toggle.model.TableToggleStatus;
import com.linkedin.openhouse.tables.toggle.repository.ToggleStatusesRepository;
import com.linkedin.openhouse.tablestest.SpringH2TestApplication;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.MetadataTableType;
import org.apache.iceberg.MetadataTableUtils;
import org.apache.iceberg.PartitionKey;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.StructLike;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.data.GenericFileWriterFactory;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.InternalRecordWrapper;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.encryption.EncryptedFiles;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.io.DataWriter;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.types.Types;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The tables service for the Spark 4.2 IRC read test, in its own JVM: the service exactly as the
 * Spark 3.5 tests embed it ({@link SpringH2TestApplication}, in-memory H2 house tables, local file
 * system storage in a {@link ServiceWarehouse}), with the dummy token interceptor and an
 * authorization handler that denies one principal.
 *
 * <p>Once up, it seeds the tables through the existing OpenHouse Java client over the native API:
 * the client creates each table, data files are written with Iceberg's generic writers to the
 * table's own location, and the client commits each append, so every snapshot is a native commit.
 * This is what Spark 3.5 with the OpenHouse catalog does for an {@code INSERT}, without Spark 3.5.
 * What the Java client then shows for each table (name, properties, metadata tables) is written
 * next to the seed facts, so the Spark 4.2 test can compare the IRC view with the native one.
 *
 * <p>Last it writes {@code ready.json} (port, warehouse, tokens, snapshot ids, the native view) and
 * serves until its standard input closes, which happens when the test JVM stops it or dies.
 *
 * <p>Usage: {@code IrcReadTestService <work directory>}.
 */
public final class IrcReadTestService {

  static final String DB = "irc42";

  /** A database whose views feature gate is open (views are off by default, natively). */
  static final String VIEWS_DB = "irc42_views";

  static final String WRITER = "irc_writer";
  static final String READER = "irc_reader";
  static final String STRANGER = "irc_stranger";

  private static final Schema PARTED_SCHEMA =
      new Schema(
          Types.NestedField.optional(1, "id", Types.LongType.get()),
          Types.NestedField.optional(2, "name", Types.StringType.get()),
          Types.NestedField.optional(3, "p", Types.StringType.get()));
  private static final Schema FLAT_SCHEMA =
      new Schema(
          Types.NestedField.optional(1, "id", Types.LongType.get()),
          Types.NestedField.optional(2, "v", Types.DoubleType.get()));

  private IrcReadTestService() {}

  public static void main(String[] args) throws Exception {
    Path work = Paths.get(args[0]).toAbsolutePath();
    // Not under the work directory: a metadata location has to fit House Tables' 255 characters.
    Path warehouse = ServiceWarehouse.create();
    ConfigurableApplicationContext context =
        new SpringApplication(SpringH2TestApplication.class, DenyStrangerConfig.class)
            .run(
                "--server.port=0",
                "--server.shutdown=immediate",
                "--server.tomcat.basedir=" + work.resolve("tomcat"),
                "--cluster.security.token.interceptor.classname="
                    + DummyTokenInterceptor.class.getName(),
                "--cluster.storages.default-type=local",
                "--cluster.storages.types.local.endpoint=file://",
                "--cluster.storages.types.local.rootpath=" + warehouse,
                // Every request, unbuffered, so a test can see which routes a client called.
                "--server.tomcat.accesslog.enabled=true",
                "--server.tomcat.accesslog.directory=" + work.resolve("access"),
                "--server.tomcat.accesslog.prefix=access",
                "--server.tomcat.accesslog.suffix=.log",
                "--server.tomcat.accesslog.rotate=false",
                "--server.tomcat.accesslog.buffered=false",
                "--server.tomcat.accesslog.pattern=%m %U %s");
    // Open the views feature gate for VIEWS_DB, as a `views` toggle rule does.
    context
        .getBean(ToggleStatusesRepository.class)
        .save(
            TableToggleStatus.builder()
                .databaseId(VIEWS_DB)
                .tableId(ViewsFeatureGate.GATE_PROBE_TABLE_ID)
                .featureId("views")
                .toggleStatusEnum(ToggleStatus.StatusEnum.ACTIVE)
                .build());
    int port = ((WebServerApplicationContext) context).getWebServer().getPort();

    Map<String, Object> ready = new LinkedHashMap<>();
    ready.put("port", port);
    ready.put("warehouse", warehouse.toString());
    Map<String, String> tokens = new LinkedHashMap<>();
    for (String principal : Arrays.asList(WRITER, READER, STRANGER)) {
      tokens.put(principal, new DummyTokenInterceptor.DummySecurityJWT(principal).buildNoopJWT());
    }
    ready.put("tokens", tokens);

    Catalog openhouse = javaClient(port, tokens.get(WRITER));
    Map<String, Object> tables = new LinkedHashMap<>();
    tables.put("parted", seedParted(openhouse));
    tables.put("flat", seedFlat(openhouse, "flat", FileFormat.PARQUET));
    tables.put("flat_orc", seedFlat(openhouse, "flat_orc", FileFormat.ORC));
    ready.put("tables", tables);

    Path tmp = work.resolve("ready.json.tmp");
    new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(tmp.toFile(), ready);
    Files.move(tmp, work.resolve("ready.json"), StandardCopyOption.ATOMIC_MOVE);
    System.out.println("IRC read test service ready on port " + port);

    // Serve until the parent closes our standard input (it stopped us, or it died).
    InputStream in = System.in;
    while (in.read() != -1) {
      // ignore
    }
    SpringApplication.exit(context);
    System.exit(0);
  }

  /** The existing OpenHouse Java client, as the Spark 3.5 tests configure it. */
  private static Catalog javaClient(int port, String token) {
    Map<String, String> properties = new HashMap<>();
    properties.put("uri", "http://localhost:" + port);
    properties.put("cluster", "local-cluster");
    properties.put("auth-token", token);
    return CatalogUtil.loadCatalog(
        "com.linkedin.openhouse.javaclient.OpenHouseCatalog",
        "openhouse",
        properties,
        new Configuration());
  }

  /** Partitioned by {@code p}, Parquet: (1,a,x),(2,b,y) then (3,c,x). */
  private static Map<String, Object> seedParted(Catalog openhouse) throws IOException {
    PartitionSpec spec = PartitionSpec.builderFor(PARTED_SCHEMA).identity("p").build();
    Table table =
        openhouse.createTable(
            TableIdentifier.of(DB, "parted"),
            PARTED_SCHEMA,
            spec,
            Map.of(TableProperties.DEFAULT_FILE_FORMAT, "parquet", "irc.seeded", "parted"));
    List<Long> snapshots = new ArrayList<>();
    table
        .newAppend()
        .appendFile(write(table, FileFormat.PARQUET, row(table, 1L, "a", "x")))
        .appendFile(write(table, FileFormat.PARQUET, row(table, 2L, "b", "y")))
        .commit();
    table.refresh();
    snapshots.add(table.currentSnapshot().snapshotId());
    table
        .newAppend()
        .appendFile(write(table, FileFormat.PARQUET, row(table, 3L, "c", "x")))
        .commit();
    table.refresh();
    snapshots.add(table.currentSnapshot().snapshotId());
    return describe(openhouse, "parted", snapshots);
  }

  /** Unpartitioned, in {@code format}: (1,1.5),(2,2.5) then (3,3.5). */
  private static Map<String, Object> seedFlat(Catalog openhouse, String name, FileFormat format)
      throws IOException {
    Table table =
        openhouse.createTable(
            TableIdentifier.of(DB, name),
            FLAT_SCHEMA,
            PartitionSpec.unpartitioned(),
            Map.of(
                TableProperties.DEFAULT_FILE_FORMAT,
                format.name().toLowerCase(),
                "irc.seeded",
                name));
    List<Long> snapshots = new ArrayList<>();
    table
        .newAppend()
        .appendFile(write(table, format, row(table, 1L, 1.5), row(table, 2L, 2.5)))
        .commit();
    table.refresh();
    snapshots.add(table.currentSnapshot().snapshotId());
    table.newAppend().appendFile(write(table, format, row(table, 3L, 3.5))).commit();
    table.refresh();
    snapshots.add(table.currentSnapshot().snapshotId());
    return describe(openhouse, name, snapshots);
  }

  private static Record row(Table table, Object... values) {
    GenericRecord record = GenericRecord.create(table.schema());
    for (int i = 0; i < values.length; i++) {
      record.set(i, values[i]);
    }
    return record;
  }

  /** One data file of {@code rows} (all in one partition) at the table's own data location. */
  private static DataFile write(Table table, FileFormat format, Record... rows) throws IOException {
    PartitionKey partition = new PartitionKey(table.spec(), table.schema());
    partition.partition(new InternalRecordWrapper(table.schema().asStruct()).wrap(rows[0]));
    String fileName = format.addExtension(UUID.randomUUID().toString());
    OutputFile out =
        table
            .io()
            .newOutputFile(
                table.spec().isUnpartitioned()
                    ? table.locationProvider().newDataLocation(fileName)
                    : table.locationProvider().newDataLocation(table.spec(), partition, fileName));
    DataWriter<Record> writer =
        new GenericFileWriterFactory.Builder(table)
            .dataFileFormat(format)
            .build()
            .newDataWriter(
                EncryptedFiles.plainAsEncryptedOutput(out),
                table.spec(),
                table.spec().isUnpartitioned() ? null : partition);
    try (DataWriter<Record> closing = writer) {
      for (Record row : rows) {
        closing.write(row);
      }
    }
    return writer.toDataFile();
  }

  /** What the existing Java client shows for the table, freshly loaded. */
  private static Map<String, Object> describe(Catalog openhouse, String name, List<Long> snapshots)
      throws IOException {
    Table table = openhouse.loadTable(TableIdentifier.of(DB, name));
    Map<String, Object> described = new LinkedHashMap<>();
    described.put("snapshotIds", snapshots);
    described.put("name", table.name());
    described.put("location", table.location());
    described.put("uuid", table.uuid().toString());
    described.put("properties", new LinkedHashMap<>(table.properties()));
    described.put("schema", table.schema().toString());
    described.put("spec", table.spec().toString());
    Map<String, Object> metadataTables = new LinkedHashMap<>();
    for (MetadataTableType type :
        Arrays.asList(
            MetadataTableType.SNAPSHOTS, MetadataTableType.HISTORY, MetadataTableType.FILES)) {
      metadataTables.put(type.name().toLowerCase(), rows(table, type));
    }
    described.put("metadataTables", metadataTables);
    return described;
  }

  /**
   * The column names and every row of a metadata table, each value as JSON can carry it: numbers,
   * strings and booleans as they are, maps and structs as maps and lists, binary as null.
   */
  private static Map<String, Object> rows(Table table, MetadataTableType type) throws IOException {
    Table metadataTable = MetadataTableUtils.createMetadataTableInstance(table, type);
    List<Types.NestedField> columns = metadataTable.schema().columns();
    List<Map<String, Object>> rows = new ArrayList<>();
    try (CloseableIterable<FileScanTask> tasks = metadataTable.newScan().planFiles()) {
      for (FileScanTask task : tasks) {
        try (CloseableIterable<StructLike> taskRows = task.asDataTask().rows()) {
          for (StructLike row : taskRows) {
            Map<String, Object> values = new LinkedHashMap<>();
            for (int i = 0; i < columns.size(); i++) {
              values.put(columns.get(i).name(), jsonValue(row.get(i, Object.class)));
            }
            rows.add(values);
          }
        }
      }
    }
    Map<String, Object> result = new LinkedHashMap<>();
    List<String> names = new ArrayList<>();
    columns.forEach(c -> names.add(c.name()));
    result.put("columns", names);
    result.put("rows", rows);
    return result;
  }

  private static Object jsonValue(Object value) {
    if (value == null
        || value instanceof Number
        || value instanceof Boolean
        || value instanceof String) {
      return value;
    }
    if (value instanceof CharSequence) {
      return value.toString();
    }
    if (value instanceof Map) {
      Map<String, Object> map = new LinkedHashMap<>();
      ((Map<?, ?>) value).forEach((k, v) -> map.put(String.valueOf(k), jsonValue(v)));
      return map;
    }
    if (value instanceof StructLike) {
      StructLike struct = (StructLike) value;
      List<Object> fields = new ArrayList<>();
      for (int i = 0; i < struct.size(); i++) {
        fields.add(jsonValue(struct.get(i, Object.class)));
      }
      return fields;
    }
    if (value instanceof List) {
      List<Object> list = new ArrayList<>();
      ((List<?>) value).forEach(v -> list.add(jsonValue(v)));
      return list;
    }
    return null;
  }

  /** Denies {@link #STRANGER} everything and allows every other principal, as OPA is absent. */
  @org.springframework.context.annotation.Configuration
  static class DenyStrangerConfig {
    @Bean
    @Primary
    AuthorizationHandler denyStrangerAuthorizationHandler() {
      return new OpaAuthorizationHandler() {
        @Override
        public boolean checkAccessDecision(String principal, TableDto table, Privileges privilege) {
          return !STRANGER.equals(principal);
        }

        @Override
        public boolean checkAccessDecision(
            String principal, DatabaseDto database, Privileges privilege) {
          return !STRANGER.equals(principal);
        }
      };
    }
  }
}
