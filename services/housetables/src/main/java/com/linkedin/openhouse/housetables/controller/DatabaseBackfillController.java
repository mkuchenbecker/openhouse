package com.linkedin.openhouse.housetables.controller;

import com.linkedin.openhouse.housetables.api.spec.model.DatabaseBackfillStatus;
import com.linkedin.openhouse.housetables.services.DatabaseBackfillService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The operator's trigger for the database backfill, under the {@code /hts/databases} route family.
 *
 * <p>Nothing here is on a timer and nothing runs at startup. A full scan of the table store on boot
 * was rejected in review of the namespace design, and it would run on every replica of every
 * cluster on every restart, for a job that has to succeed once per cluster.
 *
 * <p>What authorizes a call is the perimeter, which is what authorizes every other {@code /hts}
 * route: House Tables serves no end-user traffic, carries no authentication filter, and is
 * reachable only from inside the deployment. These two routes grant strictly less than the {@code
 * PUT /hts/databases} sitting beside them — that one writes any row a caller names, these write
 * only rows the table store already proves must exist, with empty properties, and never overwrite
 * one.
 *
 * <p>The routes are synchronous on purpose: an operator running a one-shot migration needs to see
 * what it did, and a job runner that needs to poll can call {@code GET} instead. A run that
 * outlives the client's timeout is not lost — the watermark is durable and the next call resumes
 * after it.
 */
@RestController
public class DatabaseBackfillController {

  private static final String BACKFILL_ENDPOINT = "/hts/databases/backfill";
  private static final String BACKFILL_VERIFY_ENDPOINT = "/hts/databases/backfill/verify";

  /**
   * One round trip per this many databases. Large enough that a cluster with thousands of databases
   * is a handful of queries, small enough that a page is never a memory problem.
   */
  private static final String DEFAULT_PAGE_SIZE = "500";

  @Autowired private DatabaseBackfillService databaseBackfillService;

  @Operation(
      summary = "Read the state of the database backfill.",
      description =
          "Returns the durable backfill state without running anything. verifiedCompleteTimeMs is"
              + " the only field that asserts that every database in the table store has a row.",
      tags = {"Database"})
  @ApiResponses(value = {@ApiResponse(responseCode = "200", description = "Backfill GET: OK")})
  @GetMapping(
      value = BACKFILL_ENDPOINT,
      produces = {"application/json"})
  public ResponseEntity<DatabaseBackfillStatus> getBackfillStatus() {
    return new ResponseEntity<>(databaseBackfillService.status(), HttpStatus.OK);
  }

  @Operation(
      summary = "Run the database backfill.",
      description =
          "Registers every database in the table store that has no row yet, resuming after the"
              + " watermark of an interrupted run. Idempotent: a database that already has a row is"
              + " left exactly as it is.",
      tags = {"Database"})
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Backfill POST: OK"),
        @ApiResponse(responseCode = "400", description = "Backfill POST: BAD_REQUEST")
      })
  @PostMapping(
      value = BACKFILL_ENDPOINT,
      produces = {"application/json"})
  public ResponseEntity<DatabaseBackfillStatus> runBackfill(
      @RequestParam(value = "pageSize", defaultValue = DEFAULT_PAGE_SIZE) int pageSize) {
    return new ResponseEntity<>(databaseBackfillService.backfill(pageSize), HttpStatus.OK);
  }

  @Operation(
      summary = "Verify the database backfill.",
      description =
          "Reads the store back and records whether every database in the table store has a row."
              + " Records completeness only when nothing is missing, and withdraws a previously"
              + " recorded completeness when something is.",
      tags = {"Database"})
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Backfill verify POST: OK"),
        @ApiResponse(responseCode = "400", description = "Backfill verify POST: BAD_REQUEST")
      })
  @PostMapping(
      value = BACKFILL_VERIFY_ENDPOINT,
      produces = {"application/json"})
  public ResponseEntity<DatabaseBackfillStatus> verifyBackfill(
      @RequestParam(value = "pageSize", defaultValue = DEFAULT_PAGE_SIZE) int pageSize) {
    return new ResponseEntity<>(databaseBackfillService.verify(pageSize), HttpStatus.OK);
  }
}
