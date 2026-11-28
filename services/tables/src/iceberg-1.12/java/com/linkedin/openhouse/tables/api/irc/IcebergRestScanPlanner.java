package com.linkedin.openhouse.tables.api.irc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.Scan;
import org.apache.iceberg.Schema;
import org.apache.iceberg.SnapshotRef;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableScan;
import org.apache.iceberg.TableUtil;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.rest.PlanStatus;
import org.apache.iceberg.rest.requests.PlanTableScanRequest;
import org.apache.iceberg.rest.responses.ErrorResponse;
import org.apache.iceberg.rest.responses.PlanTableScanResponse;
import org.apache.iceberg.types.Types;

/**
 * Plans a scan for the IRC scan planning route, synchronously and without state (decision D44): the
 * scan the request describes is built on the loaded Iceberg {@link Table} and planned at once, and
 * every file scan task is returned in the one {@code completed} response, with the delete files the
 * tasks reference. Nothing is kept: no plan, no task, no page.
 *
 * <p>What the request sets, and what is done with it:
 *
 * <ul>
 *   <li>{@code snapshot-id}: the scan's snapshot, with the schema {@code use-snapshot-schema} asks
 *       for. With {@code true} (time travel) it is the snapshot's schema, as {@code
 *       TableScan.useSnapshot} gives it. With {@code false} (how a client scans a branch: Iceberg's
 *       {@code RESTTableScan} sends the branch's head and {@code false}) it is the table's schema:
 *       the scan reads the branch whose head is that snapshot, as {@code TableScan.useRef} does. A
 *       snapshot no branch points at has no scan with the table's schema, so it is read with its
 *       own schema.
 *   <li>{@code start-snapshot-id} and {@code end-snapshot-id}: an incremental append scan, the
 *       start exclusive and the end inclusive, as the specification says (Iceberg's reference
 *       handler takes the start inclusive). No Iceberg 1.12 client sends them: its {@code
 *       appendsBetween} plans on the client.
 *   <li>{@code select}, {@code filter}, {@code case-sensitive}, {@code stats-fields}: the scan's
 *       own {@code select}, {@code filter}, {@code caseSensitive}, {@code includeColumnStats}. A
 *       filter that does not bind to the scan's schema is a 400. A selected name the schema lacks
 *       is ignored, as Iceberg's {@code Schema.select} ignores it when the client plans, except for
 *       a column added after the snapshot a client pins ({@link #lateColumnProjection}).
 *   <li>{@code min-rows-requested}: accepted and ignored. It is a hint the specification lets a
 *       server ignore; Spark fills it from a LIMIT, and cutting the task list by it (as Iceberg's
 *       reference handler does, by files) can return fewer rows than the client asked for when rows
 *       are deleted.
 * </ul>
 *
 * <p>The tasks are whole files ({@code planFiles}), as client-side planning returns them; the
 * client splits them. The plan id is random and is never stored or looked up: the specification
 * requires one with {@code completed}, and Iceberg's client uses it only to cancel a plan (a route
 * this server does not list, so the client never calls it) and to name the plan when credentials
 * come with it (this server sends none).
 *
 * <p>One plan is bounded (work item V11c): it holds at most a configured number of file scan tasks,
 * and a scan that plans more is answered {@code failed} ({@link #plan}). The bound is on what one
 * request makes the service hold; the time one plan may take, and how many run at once, are bounded
 * where the route runs it ({@link IcebergRestBoundedPlans}): planning stops when its thread is
 * interrupted.
 */
final class IcebergRestScanPlanner {

  private IcebergRestScanPlanner() {}

  /**
   * The bound on file scan tasks in one plan when the cluster property sets none (its default in
   * {@code ClusterProperties}). See the V11c measurement: on the local test fixture, a plan of
   * 10,000 tasks answered 2.8 MB and the heap's high-water mark while writing it was about 25 MB.
   */
  static final int DEFAULT_MAX_FILE_SCAN_TASKS = 10_000;

  /** The error type of a plan answered {@code failed} for having more tasks than the bound. */
  static final String TOO_LARGE = "ScanPlanTooLargeException";

  /** The error type of a plan answered {@code failed} because the table is format version 3. */
  static final String FORMAT_V3 = "ScanPlanFormatVersionException";

  /** The error type of a plan answered {@code failed} for taking longer than the time bound. */
  static final String TIMED_OUT = "ScanPlanTimeoutException";

  /**
   * Plans the scan {@code request} describes on {@code table}. At most {@code maxTasks} file scan
   * tasks are held: when the scan plans one more, planning stops, what was planned is dropped, and
   * the answer is {@code failed} with no task and no plan id (decision of V11c: Iceberg 1.12's
   * client then fails the scan with an {@code IllegalStateException} that carries the message, and
   * does not retry or fall back, so a client never reads part of a table as if it were all).
   */
  static PlanTableScanResponse plan(Table table, PlanTableScanRequest request, int maxTasks) {
    if (TableUtil.formatVersion(table) >= 3) {
      return formatV3(table);
    }
    Scan<?, FileScanTask, ?> scan = configure(table, scanOf(table, request), request);
    List<FileScanTask> tasks = new ArrayList<>();
    try (CloseableIterable<FileScanTask> planned = scan.planFiles()) {
      for (FileScanTask task : planned) {
        if (tasks.size() >= maxTasks) {
          return tooLarge(table, maxTasks);
        }
        if (Thread.currentThread().isInterrupted()) {
          // The plan took longer than its time bound and is already answered: stop.
          return timedOut(table, -1);
        }
        tasks.add(task);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return PlanTableScanResponse.builder(table.specs())
        .withPlanStatus(PlanStatus.COMPLETED)
        .withPlanId(UUID.randomUUID().toString())
        .withFileScanTasks(tasks)
        .build();
  }

  /**
   * A format v3 table is not planned (work item V11e): the specification's scan task carries no
   * data or file sequence number, and Iceberg 1.12's client reads none, so a server-planned read
   * would return a null inherited {@code _last_updated_sequence_number} and a copy-on-write rewrite
   * planned here would store the rewrite's sequence number for every row it keeps. The answer is
   * {@code failed}, as for a plan above the bound: the client fails the scan, and a write fails
   * before it writes anything.
   */
  private static PlanTableScanResponse formatV3(Table table) {
    return PlanTableScanResponse.builder(table.specs())
        .withPlanStatus(PlanStatus.FAILED)
        .withErrorResponse(
            ErrorResponse.builder()
                .responseCode(422)
                .withType(FORMAT_V3)
                .withMessage(
                    "This service does not plan scans of format version 3 tables: a planned scan"
                        + " task carries no sequence number, so row lineage"
                        + " (_last_updated_sequence_number) would be read as null and a"
                        + " copy-on-write rewrite would store wrong lineage for the rows it keeps."
                        + " Plan it on the client (catalog property scan-planning-mode=client).")
                .build())
        .build();
  }

  private static PlanTableScanResponse tooLarge(Table table, int maxTasks) {
    return PlanTableScanResponse.builder(table.specs())
        .withPlanStatus(PlanStatus.FAILED)
        .withErrorResponse(
            ErrorResponse.builder()
                .responseCode(422)
                .withType(TOO_LARGE)
                .withMessage(
                    String.format(
                        "The scan plans more than %d file scan tasks, the most this service plans"
                            + " in one request (cluster property %s). Plan it on the client"
                            + " (catalog property scan-planning-mode=client), or narrow it with a"
                            + " filter.",
                        maxTasks, IcebergRestScanController.MAX_FILE_SCAN_TASKS))
                .build())
        .build();
  }

  /**
   * The answer to a plan that took longer than {@code timeoutMs}: {@code failed}, as for a plan
   * above the task bound, since the same scan would take as long again; the client fails the scan
   * and does not retry it.
   */
  static PlanTableScanResponse timedOut(Table table, long timeoutMs) {
    return PlanTableScanResponse.builder(table.specs())
        .withPlanStatus(PlanStatus.FAILED)
        .withErrorResponse(
            ErrorResponse.builder()
                .responseCode(422)
                .withType(TIMED_OUT)
                .withMessage(
                    String.format(
                        "The scan took longer to plan than the %d ms this service plans one scan"
                            + " for (cluster property %s). Plan it on the client (catalog"
                            + " property scan-planning-mode=client), or narrow it with a filter.",
                        timeoutMs, IcebergRestScanController.TIMEOUT_MS))
                .build())
        .build();
  }

  private static Scan<?, FileScanTask, ?> scanOf(Table table, PlanTableScanRequest request) {
    if (request.startSnapshotId() != null && request.endSnapshotId() != null) {
      return table
          .newIncrementalAppendScan()
          .fromSnapshotExclusive(request.startSnapshotId())
          .toSnapshot(request.endSnapshotId());
    }
    TableScan scan = table.newScan();
    Long snapshotId = request.snapshotId();
    if (snapshotId == null) {
      return scan;
    }
    if (!request.useSnapshotSchema()) {
      for (Map.Entry<String, SnapshotRef> ref : table.refs().entrySet()) {
        if (ref.getValue().isBranch() && ref.getValue().snapshotId() == snapshotId) {
          return scan.useRef(ref.getKey());
        }
      }
    }
    return scan.useSnapshot(snapshotId);
  }

  /**
   * The projection a client that asks for the snapshot's schema meant when it names a column the
   * snapshot's schema lacks (work item V11e). Spark 4.2 pins a read of main to the current snapshot
   * and projects the table's current schema, so Iceberg 1.12's {@code RESTTableScan} sends {@code
   * use-snapshot-schema=true} with names of the current schema. Planning on the client, that scan's
   * schema is its projection, and the filter binds to the table's specs when the snapshot is the
   * current one; here, selecting those names from the snapshot's schema would drop the new column
   * and leave a filter on it unbound (a 400). So, for a point-in-time scan with {@code
   * use-snapshot-schema=true}: when a selected name is missing from the snapshot's schema and every
   * selected name is in the table's current schema, the scan projects those columns of the current
   * schema, as the client did. Otherwise null: the names are selected from the scan's schema as
   * before. A time-travel filter on a newer column still fails, as it does on the client, whose
   * specs are then bound to the snapshot's schema.
   */
  static Schema lateColumnProjection(Table table, Schema scanSchema, PlanTableScanRequest request) {
    if (!request.useSnapshotSchema()
        || request.snapshotId() == null
        || request.startSnapshotId() != null
        || request.endSnapshotId() != null) {
      return null;
    }
    boolean caseSensitive = request.caseSensitive();
    Schema current = table.schema();
    boolean missing = false;
    for (String name : request.select()) {
      if (find(scanSchema, name, caseSensitive) == null) {
        if (find(current, name, caseSensitive) == null) {
          return null;
        }
        missing = true;
      }
    }
    if (!missing) {
      return null;
    }
    return caseSensitive
        ? current.select(request.select())
        : current.caseInsensitiveSelect(request.select());
  }

  private static Types.NestedField find(Schema schema, String name, boolean caseSensitive) {
    return caseSensitive ? schema.findField(name) : schema.caseInsensitiveFindField(name);
  }

  private static <T extends Scan<T, FileScanTask, ?>> T configure(
      Table table, Scan<?, FileScanTask, ?> untyped, PlanTableScanRequest request) {
    @SuppressWarnings("unchecked")
    T scan = (T) untyped;
    if (request.select() != null) {
      Schema current = lateColumnProjection(table, scan.schema(), request);
      scan = current != null ? scan.project(current) : scan.select(request.select());
    }
    if (request.filter() != null) {
      scan = scan.filter(request.filter());
    }
    if (request.statsFields() != null) {
      scan = scan.includeColumnStats(request.statsFields());
    }
    return scan.caseSensitive(request.caseSensitive());
  }
}
