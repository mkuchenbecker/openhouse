package com.linkedin.openhouse.internal.catalog.repository;

import com.linkedin.openhouse.housetables.client.api.DatabaseApi;
import com.linkedin.openhouse.housetables.client.model.DatabaseBackfillStatus;
import com.linkedin.openhouse.internal.catalog.repository.exception.NamespaceStoreCompletenessUnavailableException;
import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/**
 * Reads the backfill marker from House Tables, which owns it, through the generated client.
 *
 * <p>The timeout is much shorter than the one the namespace repository uses for its own reads. A
 * caller blocked here is a caller whose request is about to be refused; making it wait thirty
 * seconds first turns a clear refusal into an apparent hang.
 */
@Repository
public class NamespaceStoreCompletenessImpl implements NamespaceStoreCompleteness {

  private static final int REQUEST_TIMEOUT_SECONDS = 10;

  @Autowired private DatabaseApi databaseApi;

  @Override
  public Optional<Long> verifiedCompleteTimeMs() {
    DatabaseBackfillStatus status;
    try {
      status = databaseApi.getBackfillStatus().block(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS));
    } catch (RuntimeException e) {
      // The only translation on this hop: whatever the transport raises becomes the one outcome
      // this repository publishes for "cannot tell", with the cause kept.
      throw new NamespaceStoreCompletenessUnavailableException(
          "Could not read the state of the database backfill from House Tables", e);
    }
    if (status == null) {
      throw new NamespaceStoreCompletenessUnavailableException(
          "House Tables answered the backfill status request with no body", null);
    }
    return Optional.ofNullable(status.getVerifiedCompleteTimeMs());
  }
}
