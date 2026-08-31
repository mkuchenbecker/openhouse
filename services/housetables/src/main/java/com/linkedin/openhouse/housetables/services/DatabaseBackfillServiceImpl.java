package com.linkedin.openhouse.housetables.services;

import com.linkedin.openhouse.common.exception.EntityConcurrentModificationException;
import com.linkedin.openhouse.housetables.api.spec.model.Database;
import com.linkedin.openhouse.housetables.api.spec.model.DatabaseBackfillStatus;
import com.linkedin.openhouse.housetables.model.DatabaseBackfillRow;
import com.linkedin.openhouse.housetables.repository.impl.jdbc.DatabaseBackfillHtsJdbcRepository;
import com.linkedin.openhouse.housetables.repository.impl.jdbc.DatabaseHtsJdbcRepository;
import com.linkedin.openhouse.housetables.repository.impl.jdbc.UserTableHtsJdbcRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/**
 * Default implementation of {@link DatabaseBackfillService}.
 *
 * <p>Both passes walk the same source: the distinct databases of the table store, read through
 * {@link UserTableHtsJdbcRepository#findDistinctDatabaseIdsAfter}, which is the {@code SELECT
 * DISTINCT database_id} that answers an unfiltered {@code /hts/tables/query} today, seeked by the
 * watermark instead of offset. The walk therefore costs one row per database rather than one per
 * table, and one query per page rather than one for the whole store.
 *
 * <p>Case is the store's to fold, not this class's. A database is looked up with {@code
 * existsByDatabaseIdIgnoreCase}, the same lookup every other reader of {@code database_row} uses,
 * so a table store holding {@code Prod} and a namespace store holding {@code prod} is one database
 * to both passes rather than a row this would write twice and a gap verification would report
 * forever.
 */
@Component
@Slf4j
public class DatabaseBackfillServiceImpl implements DatabaseBackfillService {

  /** Enough missing databases to see the shape of a gap, few enough to bound the response. */
  static final int MAX_MISSING_SAMPLE = 20;

  static final int MAX_PAGE_SIZE = 1000;

  /**
   * The seek in {@link UserTableHtsJdbcRepository#findDistinctDatabaseIdsAfter} is only meaningful
   * against this order, so it is fixed here rather than accepted from a caller.
   */
  private static final Sort BY_DATABASE_ID = Sort.by(Sort.Direction.ASC, "databaseId");

  @Autowired UserTableHtsJdbcRepository userTableRepository;

  @Autowired DatabaseHtsJdbcRepository databaseRepository;

  @Autowired DatabaseBackfillHtsJdbcRepository backfillStateRepository;

  @Autowired DatabasesService databasesService;

  @Override
  public DatabaseBackfillStatus backfill(int pageSize) {
    int size = boundedPageSize(pageSize);
    DatabaseBackfillRow state = loadOrCreateState();
    String resumedFrom = state.getWatermark();
    String after = resumedFrom;
    long scanned = 0;
    long registered = 0;
    long alreadyRegistered = 0;

    Slice<String> slice;
    do {
      slice =
          userTableRepository.findDistinctDatabaseIdsAfter(
              after, PageRequest.of(0, size, BY_DATABASE_ID));
      for (String databaseId : slice) {
        scanned++;
        if (register(databaseId)) {
          registered++;
        } else {
          alreadyRegistered++;
        }
        after = databaseId;
      }
      // The watermark advances only once a whole page is registered, so an interruption costs at
      // most one page of re-registration on the next run, and re-registration is free.
      if (slice.hasContent()) {
        state = saveState(state.toBuilder().watermark(after).build());
      }
    } while (slice.hasNext());

    // The scan reached the end of the stream, so there is nothing left to resume: the watermark is
    // cleared, and the next run starts from the beginning rather than from a point it would now
    // read as "everything before here is done".
    state =
        saveState(
            state
                .toBuilder()
                .watermark(null)
                .scanCompleteTimeMs(System.currentTimeMillis())
                .build());
    log.info(
        "Database backfill scanned {} databases, registered {}, found {} already registered.",
        scanned,
        registered,
        alreadyRegistered);
    return toStatus(state)
        .toBuilder()
        .databasesScanned(scanned)
        .databasesRegistered(registered)
        .databasesAlreadyRegistered(alreadyRegistered)
        .resumedFrom(resumedFrom)
        .build();
  }

  @Override
  public DatabaseBackfillStatus verify(int pageSize) {
    int size = boundedPageSize(pageSize);
    String after = null;
    long scanned = 0;
    long missing = 0;
    List<String> sample = new ArrayList<>();

    Slice<String> slice;
    do {
      slice =
          userTableRepository.findDistinctDatabaseIdsAfter(
              after, PageRequest.of(0, size, BY_DATABASE_ID));
      for (String databaseId : slice) {
        scanned++;
        after = databaseId;
        if (!databaseRepository.existsById(databaseId)) {
          missing++;
          if (sample.size() < MAX_MISSING_SAMPLE) {
            sample.add(databaseId);
          }
        }
      }
    } while (slice.hasNext());

    // Soft-deleted tables live in their own table and are not part of this projection, which is
    // the same set the namespace API derives existence from: a database whose only tables are
    // soft-deleted is not derived, so its absence from the store is not a gap.
    //
    // Verification always starts at the beginning and never consults the watermark: it is the pass
    // that has to be able to contradict the scan, so it may not inherit the scan's belief about
    // what is already done.
    long now = System.currentTimeMillis();
    DatabaseBackfillRow state =
        saveState(
            loadOrCreateState()
                .toBuilder()
                .lastVerifyTimeMs(now)
                .missingCount(missing)
                // Set only by a pass that found nothing missing, and cleared by every pass that
                // did. Anything else would leave a marker asserting a completeness the store no
                // longer has, which is worse than no marker at all.
                .verifiedCompleteTimeMs(missing == 0 ? now : null)
                .build());
    if (missing > 0) {
      log.warn(
          "Database backfill verification found {} of {} databases with no row; sample: {}",
          missing,
          scanned,
          sample);
    }
    return toStatus(state).toBuilder().databasesScanned(scanned).missingSample(sample).build();
  }

  @Override
  public DatabaseBackfillStatus status() {
    // A read does not create the state row: a status call on a cluster that has never run the
    // backfill must not leave behind a row saying it has.
    return backfillStateRepository
        .findById(DatabaseBackfillRow.SINGLETON_ID)
        .map(this::toStatus)
        .orElseGet(
            () -> DatabaseBackfillStatus.builder().missingSample(Collections.emptyList()).build());
  }

  /**
   * @return true when this call created the row, false when the database was already registered —
   *     including when another writer registered it between the check and the write.
   */
  private boolean register(String databaseId) {
    if (databaseRepository.existsById(databaseId)) {
      return false;
    }
    try {
      databasesService.putDatabase(
          Database.builder().databaseId(databaseId).properties(new LinkedHashMap<>()).build());
      return true;
    } catch (EntityConcurrentModificationException e) {
      // A versionless put asserts "there is no row"; the store raising a conflict is it telling us
      // somebody else registered the database first, which is the outcome this asked for. That
      // conflict is the whole of the idempotency here, and it is the store's, not this class's.
      return false;
    }
  }

  private DatabaseBackfillRow loadOrCreateState() {
    Optional<DatabaseBackfillRow> existing =
        backfillStateRepository.findById(DatabaseBackfillRow.SINGLETON_ID);
    return existing.orElseGet(
        () ->
            backfillStateRepository.save(
                DatabaseBackfillRow.builder().id(DatabaseBackfillRow.SINGLETON_ID).build()));
  }

  private DatabaseBackfillRow saveState(DatabaseBackfillRow state) {
    return backfillStateRepository.save(state);
  }

  private DatabaseBackfillStatus toStatus(DatabaseBackfillRow state) {
    return DatabaseBackfillStatus.builder()
        .watermark(state.getWatermark())
        .scanCompleteTimeMs(state.getScanCompleteTimeMs())
        .verifiedCompleteTimeMs(state.getVerifiedCompleteTimeMs())
        .lastVerifyTimeMs(state.getLastVerifyTimeMs())
        .missingCount(state.getMissingCount())
        .missingSample(Collections.emptyList())
        .build();
  }

  /**
   * Deliberately asymmetric: a page size below 1 cannot be honoured at all and is the caller's
   * mistake, while one above the cap is a request this can serve — in pages of {@link
   * #MAX_PAGE_SIZE}, which is a resource bound and not a contract the caller needs to know.
   */
  private static int boundedPageSize(int pageSize) {
    if (pageSize < 1) {
      throw new IllegalArgumentException("pageSize must be at least 1, was " + pageSize);
    }
    return Math.min(pageSize, MAX_PAGE_SIZE);
  }
}
