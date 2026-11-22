package com.linkedin.openhouse.tables.commit;

import com.linkedin.openhouse.tables.model.TableDto;
import java.util.function.Supplier;
import lombok.AccessLevel;
import lombok.Getter;
import org.apache.iceberg.catalog.TableIdentifier;

/**
 * One commit on an existing table, as the commit core takes it: the stored base, the base version
 * the caller built on, the candidate, and the context.
 *
 * <p>The candidate is held in the form the commit rules, their extension points ({@code
 * PreservedKeyChecker}, {@code TablePolicyManager}, {@code ReadBridgeStripProtection}) and the
 * repository read it: a {@link TableDto} spelled the way the native API spells a write. A native
 * request is decoded into it by the native mapper; an Iceberg {@code TableMetadata} candidate is
 * converted into it by {@code CandidateViews}. It is computed on first use, so a native request
 * whose decoding fails still fails at the step where it failed before.
 */
public final class TableCommit {
  @Getter private final TableIdentifier identifier;

  /** The metadata location the candidate was built on. */
  @Getter private final String baseVersion;

  /** The stored table as the service loaded it. */
  @Getter private final TableDto base;

  @Getter private final CommitContext context;

  @Getter(AccessLevel.NONE)
  private final Supplier<TableDto> candidateSupplier;

  private TableDto candidate;

  public TableCommit(
      TableIdentifier identifier,
      String baseVersion,
      TableDto base,
      Supplier<TableDto> candidate,
      CommitContext context) {
    this.identifier = identifier;
    this.baseVersion = baseVersion;
    this.base = base;
    this.candidateSupplier = candidate;
    this.context = context;
  }

  /** The candidate, computed once on first call. */
  public TableDto candidate() {
    if (candidate == null) {
      candidate = candidateSupplier.get();
    }
    return candidate;
  }
}
