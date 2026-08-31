package com.linkedin.openhouse.internal.catalog.repository;

import com.linkedin.openhouse.housetables.client.api.DatabaseApi;
import com.linkedin.openhouse.housetables.client.model.DatabaseBackfillStatus;
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
    DatabaseBackfillStatus status =
        databaseApi.getBackfillStatus().block(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS));
    return Optional.ofNullable(status == null ? null : status.getVerifiedCompleteTimeMs());
  }
}
