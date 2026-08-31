package com.linkedin.openhouse.tables.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.linkedin.openhouse.common.exception.NamespaceStoreNotBackfilledException;
import com.linkedin.openhouse.internal.catalog.repository.NamespaceStoreCompleteness;
import com.linkedin.openhouse.internal.catalog.repository.exception.NamespaceStoreCompletenessUnavailableException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The gate's contract is about what it costs and which way it moves, so that is what this covers:
 * the marker it reads, the calls it does not make, and the direction it cannot travel.
 */
public class NamespaceStoreReadGateTest {

  /**
   * The whole point of the marker split. A backfill that ran to the end of the stream sets {@code
   * scanCompleteTimeMs} and proves nothing: it may have raced a database created behind it, and a
   * registration may have failed after it read one. Only {@code verifiedCompleteTimeMs} says a pass
   * read the store back and found nothing missing, and it is the only field this asks for -- so a
   * source that has the first and not the second still refuses.
   */
  @Test
  void aScanThatMerelyRanDoesNotOpenTheGate() {
    NamespaceStoreReadGate gate = gateOver(Optional::empty);
    assertThatThrownBy(gate::requireStoreIsAuthoritative)
        .isInstanceOf(NamespaceStoreNotBackfilledException.class);
  }

  /** The refusal has to be actionable by whoever reads it, which means naming both calls. */
  @Test
  void theRefusalNamesTheBackfillAndTheVerification() {
    NamespaceStoreReadGate gate = gateOver(Optional::empty);
    assertThatThrownBy(gate::requireStoreIsAuthoritative)
        .hasMessageContaining("POST /hts/databases/backfill")
        .hasMessageContaining("POST /hts/databases/backfill/verify")
        .hasMessageContaining("verifiedCompleteTimeMs");
  }

  /**
   * The hot-path cost, stated as a count. A per-request read of a marker that can only change once
   * per cluster is not a price a namespace read should pay, so after the first success there are no
   * further reads at all -- not a cached one, not a cheap one, none.
   */
  @Test
  void aServingGateNeverReadsTheMarkerAgain() {
    AtomicInteger reads = new AtomicInteger();
    NamespaceStoreReadGate gate =
        gateOver(
            () -> {
              reads.incrementAndGet();
              return Optional.of(1L);
            });

    for (int i = 0; i < 100; i++) {
      gate.requireStoreIsAuthoritative();
    }
    assertThat(reads.get()).isEqualTo(1);
  }

  /**
   * And the cost while refusing. Every refused request would otherwise be a House Tables round trip
   * on a service that is already failing; the remembered refusal keeps that to one per interval.
   */
  @Test
  void aRefusingGateReadsTheMarkerAtMostOncePerInterval() {
    AtomicInteger reads = new AtomicInteger();
    NamespaceStoreReadGate gate =
        gateOver(
            () -> {
              reads.incrementAndGet();
              return Optional.empty();
            });

    for (int i = 0; i < 100; i++) {
      assertThatThrownBy(gate::requireStoreIsAuthoritative)
          .isInstanceOf(NamespaceStoreNotBackfilledException.class)
          .hasMessageContaining("/hts/databases/backfill");
    }
    assertThat(reads.get()).isEqualTo(1);
  }

  /**
   * One way only. A gate that could withdraw service would turn a House Tables blip, or a
   * verification pass that has not been re-run, into an outage of every namespace read on a store
   * that is in fact complete. Serving is therefore terminal: the source here goes bad immediately
   * after the first success and is never consulted again.
   */
  @Test
  void aServingGateNeverGoesBackToRefusing() {
    AtomicReference<Optional<Long>> answer = new AtomicReference<>(Optional.of(1L));
    NamespaceStoreReadGate gate = gateOver(answer::get);

    gate.requireStoreIsAuthoritative();
    answer.set(Optional.empty());
    for (int i = 0; i < 10; i++) {
      assertThatCode(gate::requireStoreIsAuthoritative).doesNotThrowAnyException();
    }
  }

  /** But it does go the other way, once, without a restart. */
  @Test
  void aRefusingGateStartsServingWhenTheMarkerAppears() throws Exception {
    AtomicReference<Optional<Long>> answer = new AtomicReference<>(Optional.empty());
    NamespaceStoreReadGate gate = gateOver(answer::get);

    assertThatThrownBy(gate::requireStoreIsAuthoritative)
        .isInstanceOf(NamespaceStoreNotBackfilledException.class);
    answer.set(Optional.of(1L));
    // The refusal is remembered for the recheck interval, so nothing is expected before it expires.
    Thread.sleep(NamespaceStoreReadGate.REFUSAL_RECHECK_INTERVAL_MS + 100);
    assertThatCode(gate::requireStoreIsAuthoritative).doesNotThrowAnyException();
  }

  /**
   * An unreachable House Tables is not evidence of a complete store. Treating a failed read as a
   * pass would open the gate exactly when the service can least justify it.
   */
  @Test
  void anUnreadableMarkerRefusesRatherThanAssumes() {
    NamespaceStoreReadGate gate =
        gateOver(
            () -> {
              throw new NamespaceStoreCompletenessUnavailableException(
                  "hts down", new IllegalStateException("connection refused"));
            });

    assertThatThrownBy(gate::requireStoreIsAuthoritative)
        .isInstanceOf(NamespaceStoreNotBackfilledException.class)
        .hasMessageContaining("could not be read")
        .hasMessageContaining("/hts/databases/backfill")
        .hasCauseInstanceOf(NamespaceStoreCompletenessUnavailableException.class);
  }

  private static NamespaceStoreReadGate gateOver(NamespaceStoreCompleteness completeness) {
    NamespaceStoreReadGate gate = new NamespaceStoreReadGate();
    gate.namespaceStoreCompleteness = completeness;
    return gate;
  }
}
