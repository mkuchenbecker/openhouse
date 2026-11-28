package com.linkedin.openhouse.tables.api.irc;

import static com.linkedin.openhouse.common.security.AuthenticationUtils.extractAuthenticatedUserPrincipal;

import com.linkedin.openhouse.cluster.configs.ClusterProperties;
import com.linkedin.openhouse.tables.authorization.Privileges;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.iceberg.Table;
import org.apache.iceberg.exceptions.ServiceUnavailableException;
import org.apache.iceberg.rest.PlanStatus;
import org.apache.iceberg.rest.requests.PlanTableScanRequest;
import org.apache.iceberg.rest.responses.ImmutableLoadCredentialsResponse;
import org.apache.iceberg.rest.responses.PlanTableScanResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.annotation.Secured;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The IRC routes that act on what a table load gives a client: scan planning and storage
 * credentials. Each runs exactly the checks of a table load ({@link
 * IcebergRestApiHandler#loadIcebergTable}), so a caller that cannot load a table cannot plan it or
 * ask for its credentials, and gets the load's status and message.
 *
 * <ul>
 *   <li>{@code POST .../tables/{table}/plan}: planned synchronously, answered {@code completed}
 *       with every file scan task ({@link IcebergRestScanPlanner}), or {@code failed} when the scan
 *       plans more tasks than the cluster property {@value #MAX_FILE_SCAN_TASKS} allows, when it
 *       takes longer to plan than {@value #TIMEOUT_MS} allows, or when the table is format version
 *       3 (its scan tasks would carry no sequence number). At most {@value #MAX_CONCURRENT_PLANS}
 *       plans run at once; one asked for beyond that is answered 503 with a {@code Retry-After}
 *       header, the spec's "not ready to handle the request" ({@link IcebergRestBoundedPlans}). A
 *       plan is audited as the table read it is, as the load is. Nothing is stored, so the routes
 *       that read or cancel a stored plan, and the one that pages its tasks, are not served and not
 *       listed: they answer the 406 every unserved spec route answers. Iceberg's client calls them
 *       only for a {@code submitted} plan, for {@code plan-tasks}, or when the cancel route is
 *       listed, none of which this server produces.
 *   <li>{@code GET .../tables/{table}/credentials}: an empty {@code storage-credentials} list. The
 *       service holds no credential it could narrow to one table; the specification lets a server
 *       supply access "via any or none". The {@code planId} parameter is accepted and ignored.
 * </ul>
 *
 * <p>The server never asks a client to plan on the server: nothing it sends sets {@code
 * scan-planning-mode}, so only a client configured for server planning sends a plan request. The
 * access-delegation header on load and create is read by no route.
 *
 * <p>Served only when the cluster turns scan planning on ({@link
 * ClusterProperties#IRC_SCAN_PLANNING_ENABLED}, off by default, where the privilege the plan route
 * asks for is discussed): otherwise these routes are not registered, so both answer the 406 of
 * every route this server does not serve, and the config answer does not list them. The credentials
 * route goes with planning: the specification ties its {@code planId} to a plan, and the only other
 * client of it, the refresh of vended credentials, is never pointed at it (this server vends none).
 *
 * <p>Compiled only on the Iceberg 1.12 line ({@code src/iceberg-1.12}), whose request and response
 * classes these routes use; LinkedIn's Iceberg 1.5.2 reference line has none of them, and there
 * both routes answer 406 as before. Registered only where those classes exist.
 */
@Hidden
@Slf4j
@RestController
@ConditionalOnClass(name = IcebergRestScanController.SCAN_API_CLASS)
@ConditionalOnProperty(name = ClusterProperties.IRC_SCAN_PLANNING_ENABLED, havingValue = "true")
public class IcebergRestScanController {

  /** The Iceberg class the planning route needs, and whose presence registers these routes. */
  static final String SCAN_API_CLASS = "org.apache.iceberg.rest.requests.PlanTableScanRequest";

  private static final String V1 = IcebergRestCatalogController.BASE_PATH + "/v1";

  /**
   * The cluster property bounding the file scan tasks of one plan. A scan that plans more is
   * answered {@code failed}. Not set means {@link
   * IcebergRestScanPlanner#DEFAULT_MAX_FILE_SCAN_TASKS}; it must be positive.
   */
  static final String MAX_FILE_SCAN_TASKS = "cluster.tables.irc.scan-planning.max-file-scan-tasks";

  /** The cluster property bounding how many plans run at once; it must be positive. */
  static final String MAX_CONCURRENT_PLANS =
      "cluster.tables.irc.scan-planning.max-concurrent-plans";

  /** The cluster property bounding how long one plan may take; it must be positive. */
  static final String TIMEOUT_MS = "cluster.tables.irc.scan-planning.timeout-ms";

  /** The seconds a client is told to wait before it asks again for a plan refused as busy. */
  static final String RETRY_AFTER_SECONDS = "1";

  @Autowired private IcebergRestApiHandler handler;

  @Autowired private ClusterProperties clusterProperties;

  private int maxFileScanTasks;

  private long timeoutMs;

  private IcebergRestBoundedPlans plans;

  /** Refuses to start with a bound that is not positive, rather than read it as something else. */
  @PostConstruct
  void start() {
    maxFileScanTasks =
        (int)
            requirePositive(
                MAX_FILE_SCAN_TASKS,
                clusterProperties.getClusterTablesIrcScanPlanningMaxFileScanTasks());
    int maxConcurrent =
        (int)
            requirePositive(
                MAX_CONCURRENT_PLANS,
                clusterProperties.getClusterTablesIrcScanPlanningMaxConcurrentPlans());
    timeoutMs =
        requirePositive(TIMEOUT_MS, clusterProperties.getClusterTablesIrcScanPlanningTimeoutMs());
    plans = new IcebergRestBoundedPlans(maxConcurrent, timeoutMs);
  }

  @PreDestroy
  void stop() {
    if (plans != null) {
      plans.close();
    }
  }

  /** {@code value}, the value of the cluster property {@code property}, if it is positive. */
  static long requirePositive(String property, long value) {
    if (value <= 0) {
      throw new IllegalStateException(
          String.format(
              "%s is %d: with %s on it must be positive",
              property, value, ClusterProperties.IRC_SCAN_PLANNING_ENABLED));
    }
    return value;
  }

  /**
   * Plans a scan of a table. Secured like the load route; the parameter names are the ones the
   * authorization interceptor reads.
   */
  @ApiResponses(@ApiResponse(responseCode = "200", description = "IRC plan table scan: OK"))
  @PostMapping(V1 + "/namespaces/{namespace}/tables/{table}/plan")
  @Secured(value = Privileges.Privilege.GET_TABLE_METADATA)
  public ResponseEntity<byte[]> planTableScan(
      @PathVariable("namespace") String databaseId,
      @PathVariable("table") String tableId,
      @RequestBody(required = false) byte[] body) {
    String database = IcebergRestApiHandler.tableDatabaseId(databaseId, tableId);
    // The load's checks first: a caller that cannot load the table gets the load's answer, whatever
    // the body says.
    Table table =
        handler.loadIcebergTable(database, tableId, extractAuthenticatedUserPrincipal()).getTable();
    PlanTableScanRequest request = IcebergRestJson.read(body, PlanTableScanRequest.class);
    PlanTableScanResponse response;
    try {
      response =
          plans.run(
              () -> IcebergRestScanPlanner.plan(table, request, maxFileScanTasks),
              () -> IcebergRestScanPlanner.timedOut(table, timeoutMs));
    } catch (IcebergRestBoundedPlans.Busy busy) {
      log.debug("IRC plan of {}.{} refused: {}", database, tableId, busy.getMessage());
      ResponseEntity<byte[]> error =
          IcebergRestJson.error(
              IcebergRestErrors.toError(new ServiceUnavailableException("%s", busy.getMessage())));
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
          .headers(error.getHeaders())
          .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
          .body(error.getBody());
    }
    if (response.planStatus() == PlanStatus.FAILED) {
      // Debug, not warn: the client is answered the refusal, and a client that retries a refused
      // plan would otherwise fill the log.
      log.debug(
          "IRC plan of {}.{} refused ({}): {}",
          database,
          tableId,
          response.errorResponse().type(),
          response.errorResponse().message());
    }
    return IcebergRestJson.ok(response);
  }

  /**
   * The storage credentials for a table: none. Secured like the load route, and checked as a load.
   */
  @ApiResponses(@ApiResponse(responseCode = "200", description = "IRC load credentials: OK"))
  @GetMapping(V1 + "/namespaces/{namespace}/tables/{table}/credentials")
  @Secured(value = Privileges.Privilege.GET_TABLE_METADATA)
  public ResponseEntity<byte[]> loadCredentials(
      @PathVariable("namespace") String databaseId,
      @PathVariable("table") String tableId,
      @RequestParam(value = "planId", required = false) String planId) {
    String database = IcebergRestApiHandler.tableDatabaseId(databaseId, tableId);
    handler.loadIcebergTable(database, tableId, extractAuthenticatedUserPrincipal());
    return IcebergRestJson.ok(ImmutableLoadCredentialsResponse.builder().build());
  }
}
