package com.linkedin.openhouse.tables.api.irc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkedin.openhouse.common.security.DummyTokenInterceptor.DummySecurityJWT;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.tables.e2e.h2.SpringH2Application;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import javax.servlet.Filter;
import javax.servlet.http.HttpServletRequest;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.AppendFiles;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.Metrics;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.rest.requests.PlanTableScanRequest;
import org.apache.iceberg.rest.responses.PlanTableScanResponse;
import org.apache.iceberg.types.Conversions;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;

/**
 * What one plan costs the service, measured on the local test fixture (H2 stores, local file
 * system): for tables of a given number of data files (each with column stats for three columns, in
 * appends of 1,000 files, so one manifest per 1,000 files), and for a request without and with
 * {@code stats-fields}: the time the plan route takes on the server, what the serving thread (and
 * every thread of the JVM, the client's included) allocates, and the size of the answer; then, in
 * this JVM on the same table, the heap the planned tasks and the written answer hold (the used heap
 * after a full GC, less the used heap before) and the heap's high-water mark while the answer is
 * written (live objects and garbage). The numbers are printed as {@code PLAN-COST} lines.
 *
 * <p>The sizes are {@code 1000,10000} unless the system property {@value #SIZES} or the environment
 * variable {@code IRC_PLAN_COST_SIZES} names others (a measurement also ran 20,000 and 50,000).
 * Each size asserts that the route answered every task in one answer at or below the default bound
 * of one plan, and {@code failed} with no task above it, and that the bytes it sent are the bytes
 * the in-JVM measurement wrote.
 */
@SpringBootTest(
    classes = SpringH2Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties =
        com.linkedin.openhouse.cluster.configs.ClusterProperties.IRC_SCAN_PLANNING_ENABLED
            + "=true")
@ContextConfiguration(initializers = {PropertyOverrideContextInitializer.class})
@Import(IrcScanPlanCostTest.MeasureRequests.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class IrcScanPlanCostTest {

  static final String SIZES = "irc.plan.cost.sizes";
  private static final String DB = "irc_plan_cost";
  private static final Schema SCHEMA =
      new Schema(
          Types.NestedField.optional(1, "id", Types.LongType.get()),
          Types.NestedField.optional(2, "name", Types.StringType.get()),
          Types.NestedField.optional(3, "p", Types.StringType.get()));
  private static final PartitionSpec SPEC = PartitionSpec.builderFor(SCHEMA).identity("p").build();

  /** Per plan request on the server: {nanoseconds, bytes the serving thread allocated}. */
  static final List<long[]> MEASURED = new CopyOnWriteArrayList<>();

  /**
   * Measures every plan request on the thread that serves it. Imported by this test only: not a
   * {@code @TestConfiguration}, which component scanning of this package would add to every other
   * context of the suite.
   */
  static class MeasureRequests {
    @Bean
    Filter planCostMeter() {
      com.sun.management.ThreadMXBean threads =
          (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
      return (request, response, chain) -> {
        if (!((HttpServletRequest) request).getRequestURI().endsWith("/plan")) {
          chain.doFilter(request, response);
          return;
        }
        long allocated = threads.getCurrentThreadAllocatedBytes();
        long start = System.nanoTime();
        try {
          chain.doFilter(request, response);
        } finally {
          MEASURED.add(
              new long[] {
                System.nanoTime() - start, threads.getCurrentThreadAllocatedBytes() - allocated
              });
        }
      };
    }
  }

  @LocalServerPort private int port;
  private RESTCatalog catalog;
  private final List<TableIdentifier> created = new ArrayList<>();

  @BeforeEach
  void start() {
    Map<String, String> properties = new HashMap<>();
    properties.put("uri", "http://localhost:" + port + "/iceberg");
    properties.put("token", token());
    catalog =
        (RESTCatalog)
            CatalogUtil.loadCatalog(
                RESTCatalog.class.getName(), "openhouse", properties, new Configuration());
  }

  @AfterEach
  void stop() throws Exception {
    try {
      for (TableIdentifier identifier : created) {
        catalog.dropTable(identifier);
      }
    } finally {
      catalog.close();
    }
  }

  private static String token() {
    try {
      return new DummySecurityJWT("irc_planner").buildNoopJWT();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static List<Integer> sizes() {
    String sizes = System.getProperty(SIZES, System.getenv("IRC_PLAN_COST_SIZES"));
    if (sizes == null) {
      sizes = "1000,10000";
    }
    return Arrays.stream(sizes.split(","))
        .map(String::trim)
        .map(Integer::parseInt)
        .collect(Collectors.toList());
  }

  private static ByteBuffer bound(Types.NestedField field, Object value) {
    return Conversions.toByteBuffer(field.type(), value);
  }

  /** A table of {@code files} data files, ten partitions, one manifest per 1,000 files. */
  private Table table(String name, int files) {
    TableIdentifier identifier = TableIdentifier.of(DB, name);
    created.add(identifier);
    Table table = catalog.createTable(identifier, SCHEMA, SPEC);
    for (int from = 0; from < files; from += 1000) {
      AppendFiles append = table.newAppend();
      for (int i = from; i < Math.min(files, from + 1000); i++) {
        Map<Integer, Long> counts = new HashMap<>();
        Map<Integer, ByteBuffer> lower = new HashMap<>();
        Map<Integer, ByteBuffer> upper = new HashMap<>();
        for (Types.NestedField field : SCHEMA.columns()) {
          counts.put(field.fieldId(), 100L);
        }
        lower.put(1, bound(SCHEMA.findField(1), i * 100L));
        upper.put(1, bound(SCHEMA.findField(1), i * 100L + 99));
        lower.put(2, bound(SCHEMA.findField(2), "name-" + i + "-a"));
        upper.put(2, bound(SCHEMA.findField(2), "name-" + i + "-z"));
        lower.put(3, bound(SCHEMA.findField(3), "p" + (i % 10)));
        upper.put(3, bound(SCHEMA.findField(3), "p" + (i % 10)));
        append.appendFile(
            DataFiles.builder(SPEC)
                .withPath(table.location() + "/data/p=p" + (i % 10) + "/file-" + i + ".parquet")
                .withFormat(FileFormat.PARQUET)
                .withPartitionPath("p=p" + (i % 10))
                .withFileSizeInBytes(100_000L + i)
                .withMetrics(new Metrics(100L, counts, counts, new HashMap<>(), null, lower, upper))
                .build());
      }
      append.commit();
    }
    return table;
  }

  private HttpResponse<byte[]> postPlan(String table, String json) throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://localhost:"
                            + port
                            + "/iceberg/v1/namespaces/"
                            + DB
                            + "/tables/"
                            + table
                            + "/plan"))
                .header("Authorization", "Bearer " + token())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build(),
            HttpResponse.BodyHandlers.ofByteArray());
  }

  private static long usedAfterGc() {
    for (int i = 0; i < 3; i++) {
      System.gc();
    }
    Runtime runtime = Runtime.getRuntime();
    return runtime.totalMemory() - runtime.freeMemory();
  }

  private static void resetPeaks() {
    for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
      if (pool.getType() == MemoryType.HEAP) {
        pool.resetPeakUsage();
      }
    }
  }

  /** The sum of the heap pools' peaks since {@link #resetPeaks}: live objects and garbage. */
  private static long peakHeap() {
    long peak = 0;
    for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
      if (pool.getType() == MemoryType.HEAP) {
        peak += pool.getPeakUsage().getUsed();
      }
    }
    return peak;
  }

  private static long median(List<Long> values) {
    List<Long> sorted = new ArrayList<>(values);
    Collections.sort(sorted);
    return sorted.get(sorted.size() / 2);
  }

  private static final String ALL_STATS = "{\"stats-fields\":[\"id\",\"name\",\"p\"]}";

  @Test
  void measureWhatOnePlanCosts() throws Exception {
    System.out.println(
        "PLAN-COST heap max "
            + Runtime.getRuntime().maxMemory() / (1024 * 1024)
            + " MB, "
            + Runtime.getRuntime().availableProcessors()
            + " processors");
    for (int files : sizes()) {
      String name = "files_" + files;
      Table table = table(name, files);
      for (String body : new String[] {"{}", ALL_STATS}) {
        System.out.println(
            "PLAN-COST "
                + files
                + " files, request "
                + body
                + ": "
                + measure(table, name, files, body));
      }
    }
  }

  /** One size and request: the route three times after a warm-up, then the heap in this JVM. */
  private String measure(Table table, String name, int files, String body) throws Exception {
    ObjectMapper reader = new ObjectMapper();
    List<Long> times = new ArrayList<>();
    List<Long> allocated = new ArrayList<>();
    List<Long> allocatedAllThreads = new ArrayList<>();
    JsonNode answer = null;
    long answerBytes = -1;
    for (int run = 0; run < 4; run++) {
      MEASURED.clear();
      long before = allThreadsAllocated();
      HttpResponse<byte[]> response = postPlan(name, body);
      // The filter records a request once its chain returns, which can be after the client has
      // the whole answer.
      long deadline = System.nanoTime() + 10_000_000_000L;
      while (MEASURED.isEmpty() && System.nanoTime() < deadline) {
        Thread.sleep(10);
      }
      long all = allThreadsAllocated() - before;
      assertEquals(200, response.statusCode(), new String(response.body()));
      answer = reader.readTree(response.body());
      answerBytes = response.body().length;
      if (run > 0) { // the first run warms up
        times.add(MEASURED.get(0)[0] / 1_000_000);
        allocated.add(MEASURED.get(0)[1] / 1024);
        allocatedAllThreads.add(all / 1024);
      }
    }
    String status = answer.get("status").asText();
    int tasks = answer.has("file-scan-tasks") ? answer.get("file-scan-tasks").size() : 0;

    // What the answer holds at once, planned in this JVM on the table the client loaded.
    PlanTableScanRequest request =
        IcebergRestJson.read(body.getBytes(StandardCharsets.UTF_8), PlanTableScanRequest.class);
    long base = usedAfterGc();
    int bound = IcebergRestScanPlanner.DEFAULT_MAX_FILE_SCAN_TASKS;
    PlanTableScanResponse planned = IcebergRestScanPlanner.plan(table, request, bound);
    long withTasks = usedAfterGc() - base;
    resetPeaks();
    ResponseEntity<byte[]> written = IcebergRestJson.ok(planned);
    long peak = peakHeap() - base;
    long withBytes = usedAfterGc() - base;
    String line =
        String.format(
            "%s, %d tasks, median %d ms, serving thread allocated %d KB (all threads %d KB),"
                + " answer %d KB; live after planning %d KB, with the written answer %d KB,"
                + " heap high-water while writing %d KB",
            status,
            tasks,
            median(times),
            median(allocated),
            median(allocatedAllThreads),
            answerBytes / 1024,
            withTasks / 1024,
            withBytes / 1024,
            peak / 1024);
    // At or below the default bound every task comes in one answer; above it, none.
    if (files <= bound) {
      assertEquals("completed", status);
      assertEquals(files, tasks);
      assertEquals(files, planned.fileScanTasks().size());
    } else {
      assertEquals("failed", status);
      assertEquals(0, tasks);
      assertEquals(null, planned.fileScanTasks());
    }
    assertEquals(written.getBody().length, answerBytes);
    return line;
  }

  private static long allThreadsAllocated() {
    com.sun.management.ThreadMXBean threads =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    long sum = 0;
    for (long allocated : threads.getThreadAllocatedBytes(threads.getAllThreadIds())) {
      sum += Math.max(0, allocated);
    }
    return sum;
  }

  /** The default bound is 10,000 tasks: 10,000 files plan, 10,001 are answered failed. */
  @Test
  void theDefaultBoundIsTenThousandFileScanTasks() throws Exception {
    assertEquals(10_000, IcebergRestScanPlanner.DEFAULT_MAX_FILE_SCAN_TASKS);
    table("files_10001", 10_001);
    JsonNode answer = new ObjectMapper().readTree(postPlan("files_10001", "{}").body());
    assertEquals("failed", answer.get("status").asText(), answer.toString());
    assertEquals(IcebergRestScanPlanner.TOO_LARGE, answer.get("error").get("type").asText());
    assertTrue(
        answer.get("error").get("message").asText().contains("more than 10000 file scan tasks"),
        answer.toString());
    // A filter that prunes it to the bound plans.
    JsonNode pruned =
        new ObjectMapper()
            .readTree(
                postPlan(
                        "files_10001",
                        "{\"filter\":{\"type\":\"not-eq\",\"term\":\"p\",\"value\":\"p0\"}}")
                    .body());
    assertEquals("completed", pruned.get("status").asText());
    assertEquals(9_000, pruned.get("file-scan-tasks").size());
  }
}
