package com.linkedin.openhouse.tables.resthandler;

import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.apache.iceberg.BaseTable;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableOperations;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.rest.requests.UpdateTableRequest;
import org.apache.iceberg.rest.responses.LoadTableResponse;
import org.apache.iceberg.util.Tasks;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Server-side Iceberg REST-native commit: applies a typed {@code (requirements, updates)} request
 * to the current table state, letting the catalog service re-derive the next {@link TableMetadata}
 * from a fresh base on every attempt.
 *
 * <p>This is an adapted copy of {@code org.apache.iceberg.rest.CatalogHandlers#commit} (which is
 * package-visible only through {@code updateTable}) running on top of the unmodified {@code
 * OpenHouseInternalTableOperations}: with no legacy smuggled properties present, {@code doCommit}
 * degrades to exactly the primitive this loop needs — stamp catalog-owned {@code openhouse.*}
 * bookkeeping, write metadata.json, and CAS the HTS row pointer.
 *
 * <p>Two conflict classes emerge, mirroring stock Iceberg REST semantics:
 *
 * <ul>
 *   <li><b>Requirement failure</b> (e.g. {@code assert-ref-snapshot-id} no longer matches the fresh
 *       base): wrapped in {@link ValidationFailureException} so the retry loop does NOT retry;
 *       unwrapped to {@link CommitFailedException} for the caller → HTTP 409. The client must
 *       refresh and re-derive its commit.
 *   <li><b>Store-level race</b> (requirements held but a concurrent commit won the HTS pointer CAS
 *       between refresh and save): surfaces as {@link CommitFailedException} from the operations
 *       layer and is retried here server-side — the loop refreshes, re-validates the requirements,
 *       re-applies the updates, and commits again, invisibly to the client.
 * </ul>
 */
@Component
@Slf4j
public class IcebergRestCommitService {

  @Autowired private Catalog catalog;

  @Autowired private RestUpdateValidator restUpdateValidator;

  /**
   * Commits typed metadata updates to an existing table.
   *
   * @param databaseId OpenHouse databaseId (single-level REST namespace)
   * @param tableId table name
   * @param request deserialized Iceberg REST {@code UpdateTableRequest}
   * @return spec {@code LoadTableResponse} carrying the committed metadata and its location
   */
  public LoadTableResponse commit(String databaseId, String tableId, UpdateTableRequest request) {
    TableIdentifier tableIdentifier = TableIdentifier.of(databaseId, tableId);

    restUpdateValidator.validateRequestShape(tableIdentifier, request.updates());

    // Throws NoSuchTableException (→404) when the table does not exist.
    Table table = catalog.loadTable(tableIdentifier);
    if (!(table instanceof BaseTable)) {
      throw new IllegalStateException(
          "Cannot commit: catalog did not produce a BaseTable for " + tableIdentifier);
    }
    TableOperations ops = ((BaseTable) table).operations();

    commit(tableIdentifier, ops, request);

    return LoadTableResponse.builder().withTableMetadata(ops.current()).build();
  }

  private void commit(
      TableIdentifier tableIdentifier, TableOperations ops, UpdateTableRequest request) {
    AtomicBoolean isRetry = new AtomicBoolean(false);
    try {
      Tasks.foreach(ops)
          .retry(TableProperties.COMMIT_NUM_RETRIES_DEFAULT)
          .exponentialBackoff(
              TableProperties.COMMIT_MIN_RETRY_WAIT_MS_DEFAULT,
              TableProperties.COMMIT_MAX_RETRY_WAIT_MS_DEFAULT,
              TableProperties.COMMIT_TOTAL_RETRY_TIME_MS_DEFAULT,
              2.0 /* exponential */)
          .onlyRetryOn(CommitFailedException.class)
          .run(
              taskOps -> {
                TableMetadata base = isRetry.get() ? taskOps.refresh() : taskOps.current();
                isRetry.set(true);

                // Policy gates that must see the freshest state (e.g. a concurrent lock). A
                // BadRequestException here is terminal — not a CommitFailedException — so the
                // loop does not retry it.
                restUpdateValidator.validateAgainstBase(tableIdentifier, base);

                // Validate requirements against the fresh base. A failed requirement is a client
                // conflict, never something a server-side retry can fix: wrap it so it escapes
                // the retry loop.
                try {
                  request.requirements().forEach(requirement -> requirement.validate(base));
                } catch (CommitFailedException e) {
                  throw new ValidationFailureException(e);
                }

                // The server re-derives the next metadata from the fresh base — the client has
                // no authority over absolute metadata content on this path.
                TableMetadata.Builder metadataBuilder = TableMetadata.buildFrom(base);
                request.updates().forEach(update -> update.applyTo(metadataBuilder));

                TableMetadata updated = metadataBuilder.build();
                if (updated.changes().isEmpty()) {
                  // Do not commit if the metadata has not changed.
                  return;
                }

                // Writes metadata.json and CASes the HTS row pointer.
                taskOps.commit(base, updated);
              });
    } catch (ValidationFailureException e) {
      log.info(
          "REST commit requirement validation failed for table {}: {}",
          tableIdentifier,
          e.wrapped().getMessage());
      throw e.wrapped();
    }
  }

  /**
   * Exception used to avoid retrying commits when requirement assertions fail; local copy of the
   * package-private {@code CatalogHandlers.ValidationFailureException}.
   *
   * <p>A failed REST assertion is reported as {@link CommitFailedException} to the client, but
   * assertion checks run inside the block that the loop retries on {@link CommitFailedException}.
   * This wrapper carries the failure past the retry filter and is unwrapped outside the loop.
   */
  static class ValidationFailureException extends RuntimeException {
    private final CommitFailedException wrapped;

    ValidationFailureException(CommitFailedException cause) {
      super(cause);
      this.wrapped = cause;
    }

    CommitFailedException wrapped() {
      return wrapped;
    }
  }
}
