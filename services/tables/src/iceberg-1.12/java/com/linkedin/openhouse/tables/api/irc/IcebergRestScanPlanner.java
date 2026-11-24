package com.linkedin.openhouse.tables.api.irc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.Scan;
import org.apache.iceberg.SnapshotRef;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableScan;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.rest.PlanStatus;
import org.apache.iceberg.rest.requests.PlanTableScanRequest;
import org.apache.iceberg.rest.responses.PlanTableScanResponse;

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
 *       is ignored, as Iceberg's {@code Schema.select} ignores it when the client plans.
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
 */
final class IcebergRestScanPlanner {

  private IcebergRestScanPlanner() {}

  static PlanTableScanResponse plan(Table table, PlanTableScanRequest request) {
    Scan<?, FileScanTask, ?> scan = configure(scanOf(table, request), request);
    List<FileScanTask> tasks = new ArrayList<>();
    try (CloseableIterable<FileScanTask> planned = scan.planFiles()) {
      planned.forEach(tasks::add);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return PlanTableScanResponse.builder(table.specs())
        .withPlanStatus(PlanStatus.COMPLETED)
        .withPlanId(UUID.randomUUID().toString())
        .withFileScanTasks(tasks)
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

  private static <T extends Scan<T, FileScanTask, ?>> T configure(
      Scan<?, FileScanTask, ?> untyped, PlanTableScanRequest request) {
    @SuppressWarnings("unchecked")
    T scan = (T) untyped;
    if (request.select() != null) {
      scan = scan.select(request.select());
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
