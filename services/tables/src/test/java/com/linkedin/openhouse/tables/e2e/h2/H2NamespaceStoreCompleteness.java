package com.linkedin.openhouse.tables.e2e.h2;

import com.linkedin.openhouse.internal.catalog.repository.NamespaceStoreCompleteness;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * The stand-in for the backfill marker in /tables e2e tests, alongside {@link
 * NamespacesH2Repository}: there is no House Tables here to hold it.
 *
 * <p>It reports the store verified by default, which is the truth for these contexts rather than a
 * convenience. The H2 namespace store is created empty at the start of each run and every database
 * in it is registered by the write that created it, so there is no database it can be missing.
 *
 * <p>{@code test.namespace-store.verified=false} makes it report an unbackfilled store. Set it
 * through {@code @SpringBootTest(properties = ...)}: the gate latches once and never unlatches, so
 * a test of the refusal needs a context of its own, and a distinct property value is what gives it
 * one.
 */
@Component
@Primary
public class H2NamespaceStoreCompleteness implements NamespaceStoreCompleteness {

  @Value("${test.namespace-store.verified:true}")
  private boolean verified;

  @Override
  public Optional<Long> verifiedCompleteTimeMs() {
    return verified ? Optional.of(1L) : Optional.empty();
  }
}
