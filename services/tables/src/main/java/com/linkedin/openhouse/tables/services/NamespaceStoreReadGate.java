package com.linkedin.openhouse.tables.services;

import com.linkedin.openhouse.common.exception.NamespaceStoreNotBackfilledException;
import com.linkedin.openhouse.internal.catalog.repository.NamespaceStoreCompleteness;
import com.linkedin.openhouse.internal.catalog.repository.exception.NamespaceStoreCompletenessUnavailableException;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Refuses namespace reads until the namespace store has been shown to hold every database the
 * cluster has.
 *
 * <p>Reads no longer fall back to the table store, so on a cluster whose store was never backfilled
 * every listing would come back empty and every existence check would come back false. That answer
 * is well-formed, plausible and wrong, and a client cannot tell it from a genuinely empty cluster;
 * a job that reconciles against it would drop or recreate everything. Refusing is the only failure
 * mode here that a caller can act on, so this fails the request instead, with the remedy in the
 * message.
 *
 * <p><b>What it reads.</b> {@code verifiedCompleteTimeMs} from {@code GET /hts/databases/backfill},
 * and nothing else. Not {@code scanCompleteTimeMs}: a scan that ran proves only that the walk
 * reached the end of the stream, not that the store is complete now — it may have raced a database
 * created behind it, or a registration may have failed after it read one. Only a pass that read the
 * store back and found {@code derived \ stored} empty sets the field this reads, and any pass that
 * finds a gap clears it.
 *
 * <p><b>What it costs.</b> Nothing, once the store is serving: {@link #requireStoreIsAuthoritative}
 * is then a single volatile read and issues no call at all. Before that it costs at most one House
 * Tables round trip per {@link #REFUSAL_RECHECK_INTERVAL_MS} per process — every other refused
 * request answers from the remembered refusal without any I/O — and the checks are serialized, so a
 * cluster hammering a refusing service produces one status call at a time rather than one per
 * request. The cost of the gate therefore falls entirely on requests that are about to fail, and
 * the recheck interval bounds how long after an operator verifies the store this process keeps
 * refusing.
 *
 * <p><b>How it is invalidated.</b> It is not, and that is the design. The latch moves once, from
 * refusing to serving, and no code path moves it back; the marker is not re-read after it has been
 * seen set. So the only stale state possible is stale-<em>true</em>: a verification that later
 * finds a gap clears the marker while this process keeps serving. What it serves then is store-only
 * answers — exactly what this slice ships without a gate at all — and the condition that produced
 * the gap cannot arise from a table write any more, because a write whose registration fails now
 * fails. Recovery is a restart, which re-reads and refuses. The alternative, a gate that can
 * withdraw service from a store that is in fact complete, converts an operational blip into an
 * outage of every namespace read; a one-way latch cannot.
 */
@Component
@Slf4j
public class NamespaceStoreReadGate {

  /**
   * How long a refusal is remembered before the marker is read again. Short enough that an operator
   * who has just verified the store does not wonder whether it took, long enough that a cluster
   * retrying against a refusing service does not turn every retry into a House Tables call.
   */
  static final long REFUSAL_RECHECK_INTERVAL_MS = 5_000L;

  private static final String REMEDY =
      "the namespace store has not been verified complete, so this service cannot tell which"
          + " databases exist and will not answer with a guess. Run the database backfill against"
          + " House Tables -- POST /hts/databases/backfill until it reports no watermark, then POST"
          + " /hts/databases/backfill/verify -- and retry once verify reports"
          + " verifiedCompleteTimeMs.";

  @Autowired NamespaceStoreCompleteness namespaceStoreCompleteness;

  /**
   * The latch. Volatile so the serving state, once set, is visible to every request thread without
   * taking the lock below.
   */
  private volatile boolean serving = false;

  /**
   * Held only by the thread actually reading the marker. Threads that cannot take it are refused
   * from the remembered state rather than made to wait for a network call they would then also be
   * refused by.
   */
  private final java.util.concurrent.locks.ReentrantLock checkLock =
      new java.util.concurrent.locks.ReentrantLock();

  /** When the remembered refusal expires. */
  private volatile long recheckAtMs = Long.MIN_VALUE;

  /** Why the last check refused, replayed until it expires. */
  private volatile String rememberedRefusal = "Cannot serve namespace reads: " + REMEDY;

  /**
   * @throws NamespaceStoreNotBackfilledException if the namespace store has not been verified
   *     complete, or if that fact cannot be read right now. An unreachable House Tables is not
   *     evidence of completeness.
   */
  void requireStoreIsAuthoritative() {
    if (serving) {
      return;
    }
    if (System.currentTimeMillis() < recheckAtMs || !checkLock.tryLock()) {
      throw new NamespaceStoreNotBackfilledException(rememberedRefusal);
    }
    try {
      if (serving) {
        return;
      }
      long now = System.currentTimeMillis();
      if (now < recheckAtMs) {
        throw new NamespaceStoreNotBackfilledException(rememberedRefusal);
      }
      recheckAtMs = now + REFUSAL_RECHECK_INTERVAL_MS;
      Optional<Long> verifiedAt;
      try {
        verifiedAt = namespaceStoreCompleteness.verifiedCompleteTimeMs();
      } catch (NamespaceStoreCompletenessUnavailableException e) {
        rememberedRefusal =
            "Cannot serve namespace reads: the state of the database backfill could not be read"
                + " from House Tables, and an unreadable marker is not a complete store. "
                + REMEDY;
        throw new NamespaceStoreNotBackfilledException(rememberedRefusal, e);
      }
      if (!verifiedAt.isPresent()) {
        rememberedRefusal = "Cannot serve namespace reads: " + REMEDY;
        throw new NamespaceStoreNotBackfilledException(rememberedRefusal);
      }
      log.info(
          "Namespace store verified complete at {}; serving namespace reads from it from here on.",
          verifiedAt.get());
      serving = true;
    } finally {
      checkLock.unlock();
    }
  }
}
