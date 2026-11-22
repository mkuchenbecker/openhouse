package com.linkedin.openhouse.tables.commit;

import com.linkedin.openhouse.tables.api.spec.v0.request.components.Policies;
import java.util.function.BooleanSupplier;
import lombok.Builder;
import lombok.Value;

/**
 * What a commit's rules need that table metadata cannot carry. Request-scoped facts (declared
 * client capabilities, the column-default bypass, the action-type declaration) are read from the
 * request beans as before and are not repeated here.
 */
@Value
@Builder
public class CommitContext {
  /** The native write this commit is shaped like. */
  CommitKind kind;

  /** The authenticated principal making the commit. */
  String principal;

  /**
   * The structured policies as the caller sent them, before any mapping; {@code null} when the
   * caller sent none. Read by the lock-state flip check ({@link CommitKind#TABLE_UPDATE} only).
   */
  Policies requestedPolicies;

  /**
   * The cluster id the caller addressed, used only in the wording of a conflict on a {@link
   * CommitKind#SNAPSHOTS_UPDATE}.
   */
  String clusterId;

  /**
   * The native table PUT's no-op test: true when the request, mapped onto the stored table, equals
   * the stored table, in which case nothing is written. {@code null} for commits that have none.
   */
  BooleanSupplier unchanged;
}
