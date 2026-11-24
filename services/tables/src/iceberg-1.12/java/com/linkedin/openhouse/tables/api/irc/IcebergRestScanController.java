package com.linkedin.openhouse.tables.api.irc;

import static com.linkedin.openhouse.common.security.AuthenticationUtils.extractAuthenticatedUserPrincipal;

import com.linkedin.openhouse.tables.authorization.Privileges;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import lombok.extern.slf4j.Slf4j;
import org.apache.iceberg.Table;
import org.apache.iceberg.rest.PlanStatus;
import org.apache.iceberg.rest.requests.PlanTableScanRequest;
import org.apache.iceberg.rest.responses.ImmutableLoadCredentialsResponse;
import org.apache.iceberg.rest.responses.PlanTableScanResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.annotation.Secured;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The IRC routes that act on what a table load gives a client: scan planning (decision D44) and
 * storage credentials (decision D45). Each runs exactly the checks of a table load ({@link
 * IcebergRestApiHandler#loadIcebergTable}), so a caller that cannot load a table cannot plan it or
 * ask for its credentials, and gets the load's status and message.
 *
 * <ul>
 *   <li>{@code POST .../tables/{table}/plan}: planned synchronously, answered {@code completed}
 *       with every file scan task ({@link IcebergRestScanPlanner}), or {@code failed} when the scan
 *       plans more tasks than the cluster property {@value #MAX_FILE_SCAN_TASKS} allows. Nothing is
 *       stored, so the routes that read or cancel a stored plan, and the one that pages its tasks,
 *       are not served and not listed: they answer the 406 every unserved spec route answers.
 *       Iceberg's client calls them only for a {@code submitted} plan, for {@code plan-tasks}, or
 *       when the cancel route is listed, none of which this server produces.
 *   <li>{@code GET .../tables/{table}/credentials}: an empty {@code storage-credentials} list. The
 *       service holds no credential it could narrow to one table; the specification lets a server
 *       supply access "via any or none". The {@code planId} parameter is accepted and ignored.
 * </ul>
 *
 * <p>The server never asks a client to plan on the server: nothing it sends sets {@code
 * scan-planning-mode}, so only a client configured for server planning sends a plan request. The
 * access-delegation header on load and create is read by no route.
 *
 * <p>Compiled only on the Iceberg 1.12 line ({@code src/iceberg-1.12}), whose request and response
 * classes these routes use; LinkedIn's Iceberg 1.5.2 reference line has none of them, and there
 * both routes answer 406 as before. Registered only where those classes exist, as the write routes
 * are.
 */
@Hidden
@Slf4j
@RestController
@ConditionalOnClass(name = IcebergRestScanController.SCAN_API_CLASS)
public class IcebergRestScanController {

  /** The Iceberg class the planning route needs, and whose presence registers these routes. */
  static final String SCAN_API_CLASS = "org.apache.iceberg.rest.requests.PlanTableScanRequest";

  private static final String V1 = IcebergRestCatalogController.BASE_PATH + "/v1";

  /**
   * The cluster property bounding the file scan tasks of one plan (work item V11c). A scan that
   * plans more is answered {@code failed}; not set, or not positive, means {@link
   * IcebergRestScanPlanner#DEFAULT_MAX_FILE_SCAN_TASKS}.
   */
  static final String MAX_FILE_SCAN_TASKS = "cluster.tables.irc.scan-planning.max-file-scan-tasks";

  @Autowired private IcebergRestApiHandler handler;

  @Value("${" + MAX_FILE_SCAN_TASKS + ":0}")
  private int maxFileScanTasks;

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
    Table table = handler.loadIcebergTable(database, tableId, extractAuthenticatedUserPrincipal());
    PlanTableScanRequest request = IcebergRestJson.read(body, PlanTableScanRequest.class);
    int bound = IcebergRestScanPlanner.bound(maxFileScanTasks);
    PlanTableScanResponse response = IcebergRestScanPlanner.plan(table, request, bound);
    if (response.planStatus() == PlanStatus.FAILED) {
      log.warn("IRC plan of {}.{} refused: more than {} file scan tasks", database, tableId, bound);
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
