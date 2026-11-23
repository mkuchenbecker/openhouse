package com.linkedin.openhouse.tables.services;

import com.linkedin.openhouse.common.api.spec.TableUri;
import com.linkedin.openhouse.common.exception.EntityConcurrentModificationException;
import com.linkedin.openhouse.common.exception.OpenHouseCommitStateUnknownException;
import com.linkedin.openhouse.common.exception.RequestValidationFailureException;
import com.linkedin.openhouse.common.exception.UnsupportedClientOperationException;
import com.linkedin.openhouse.tables.authorization.Privileges;
import com.linkedin.openhouse.tables.commit.CommitContext;
import com.linkedin.openhouse.tables.commit.CommitKind;
import com.linkedin.openhouse.tables.commit.TableCommit;
import com.linkedin.openhouse.tables.formatversion.FormatVersionRule;
import com.linkedin.openhouse.tables.model.TableDto;
import com.linkedin.openhouse.tables.readbridge.ClientCapabilityGate;
import com.linkedin.openhouse.tables.readbridge.ColumnDefaultException;
import com.linkedin.openhouse.tables.readbridge.ReadBridgeStripProtection;
import com.linkedin.openhouse.tables.repository.OpenHouseInternalRepository;
import com.linkedin.openhouse.tables.utils.AuthorizationUtils;
import java.util.Optional;
import org.apache.iceberg.exceptions.BadRequestException;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.exceptions.CommitStateUnknownException;
import org.springframework.data.util.Pair;

/**
 * The commit core for a write to an existing table: every service-layer rule, in the order the
 * native routes have always run them, then persistence through the repository, which runs the
 * repository and catalog rules and the three guards (the base-version check, the catalog's retry
 * cache and the house-table compare-and-set).
 *
 * <p>Both native update routes ({@code PUT .../tables/{t}} and {@code PUT .../snapshots}) decode
 * their request into a {@link TableCommit} and call {@link #commit}. So does {@link
 * TableCommitService} for a candidate given as Iceberg {@code TableMetadata}.
 *
 * <p>Built from the calling service's collaborators, so it holds no state of its own.
 */
final class TableCommitCore {
  private final OpenHouseInternalRepository repository;
  private final AuthorizationUtils authorizationUtils;
  private final ClientCapabilityGate clientCapabilityGate;
  private final ReadBridgeStripProtection readBridgeStripProtection;

  TableCommitCore(
      OpenHouseInternalRepository repository,
      AuthorizationUtils authorizationUtils,
      ClientCapabilityGate clientCapabilityGate,
      ReadBridgeStripProtection readBridgeStripProtection) {
    this.repository = repository;
    this.authorizationUtils = authorizationUtils;
    this.clientCapabilityGate = clientCapabilityGate;
    this.readBridgeStripProtection = readBridgeStripProtection;
  }

  /**
   * Runs every rule and persists the candidate.
   *
   * @return the stored table and {@code false} (a commit to an existing table never creates one)
   */
  Pair<TableDto, Boolean> commit(TableCommit commit) {
    CommitContext context = commit.getContext();
    CommitKind kind = context.getKind();
    TableDto base = commit.getBase();
    String databaseId = commit.getIdentifier().namespace().toString();
    String tableId = commit.getIdentifier().name();
    String principal = context.getPrincipal();

    if (kind == CommitKind.TABLE_UPDATE) {
      checkIfLockPoliciesUpdated(base, context);
    }
    if (LockPolicyValidator.isLegacyLocked(base)) {
      throw new UnsupportedClientOperationException(
          UnsupportedClientOperationException.Operation.LOCKED_TABLE_OPERATION,
          kind == CommitKind.TABLE_UPDATE
              ? String.format(
                  "Table %s.%s is in locked state and cannot be updated.", databaseId, tableId)
              : String.format(
                  "Table %s.%s is in locked state and cannot be written to", databaseId, tableId));
    }
    authorizationUtils.checkTableWritePathPrivileges(
        base, principal, Privileges.UPDATE_TABLE_METADATA);
    authorizationUtils.checkSystemOnlyLockAccess(base, principal);

    // An optimization to avoid persisting an unchanged table (native table PUT only).
    if (kind == CommitKind.TABLE_UPDATE
        && context.getUnchanged() != null
        && context.getUnchanged().getAsBoolean()) {
      return Pair.of(base, /*creation didn't occur*/ false);
    }
    TableDto candidate = commit.candidate();
    clientCapabilityGate.checkWrite(
        databaseId, tableId, Optional.of(base), candidate.getTableProperties());
    candidate = LockPolicyValidator.prepare(base, candidate);
    try {
      // On a format v3 table column defaults are Iceberg's own and are stored as committed (work
      // item W6); below v3 every initial-default is a read-bridge overlay and is dropped.
      if (!FormatVersionRule.writesFormatV3(base, candidate)) {
        candidate = readBridgeStripProtection.prepare(candidate);
      }
    } catch (ColumnDefaultException e) {
      throw new UnsupportedClientOperationException(
          UnsupportedClientOperationException.Operation.COLUMN_DEFAULT, e.getMessage(), e);
    }
    return kind == CommitKind.TABLE_UPDATE
        ? Pair.of(saveTableUpdate(repository, candidate), false)
        : Pair.of(saveSnapshotsUpdate(commit, candidate), false);
  }

  /**
   * The table PUT's lock rule: a request may not flip the stored lock. A stored table without a
   * lock state object fails with a NullPointerException, as it always has.
   */
  private static void checkIfLockPoliciesUpdated(TableDto tableDto, CommitContext context) {
    if (context.getRequestedPolicies() != null
        && context.getRequestedPolicies().getLockState() != null
        && context.getRequestedPolicies().getLockState().isLocked()
            != tableDto.getPolicies().getLockState().isLocked()) {
      throw new IllegalArgumentException(
          String.format(
              "Lock state cannot be updated for Table %s.%s",
              tableDto.getDatabaseId(), tableDto.getTableId()));
    }
  }

  /** Persists a table PUT (or a lock change) and words its failures as the table routes do. */
  static TableDto saveTableUpdate(OpenHouseInternalRepository repository, TableDto tableDtoToSave) {
    try {
      return repository.save(tableDtoToSave);
    } catch (BadRequestException e) {
      throw new RequestValidationFailureException(e.getMessage(), e);
    } catch (CommitFailedException ce) {
      throw new EntityConcurrentModificationException(
          tableDtoToSave.getTableUri(),
          String.format(
              "databaseId : %s, tableId : %s, version: %s %s",
              tableDtoToSave.getDatabaseId(),
              tableDtoToSave.getTableId(),
              tableDtoToSave.getTableVersion(),
              "The requested table has been modified/created by other processes."),
          ce);
    } catch (CommitStateUnknownException commitStateUnknownException) {
      throw new OpenHouseCommitStateUnknownException(
          tableDtoToSave.getTableUri(),
          String.format(
              "databaseId : %s, tableId : %s, version: %s %s",
              tableDtoToSave.getDatabaseId(),
              tableDtoToSave.getTableId(),
              tableDtoToSave.getTableVersion(),
              "Commit regarding to the requested table is not acknowledged."),
          commitStateUnknownException);
    }
  }

  private TableDto saveSnapshotsUpdate(TableCommit commit, TableDto tableDtoToSave) {
    return saveSnapshots(
        repository,
        tableDtoToSave,
        commit.getIdentifier().namespace().toString(),
        commit.getIdentifier().name(),
        commit.getContext().getClusterId(),
        commit.getBaseVersion());
  }

  /** Persists a snapshots PUT and words its failures as the snapshots route does. */
  static TableDto saveSnapshots(
      OpenHouseInternalRepository repository,
      TableDto tableDtoToSave,
      String databaseId,
      String tableId,
      String clusterId,
      String baseTableVersion) {
    try {
      return repository.save(tableDtoToSave);
    } catch (BadRequestException e) {
      throw new RequestValidationFailureException(e.getMessage(), e);
    } catch (CommitFailedException ce) {
      throw new EntityConcurrentModificationException(
          TableUri.builder()
              .tableId(tableId)
              .databaseId(databaseId)
              .clusterId(clusterId)
              .build()
              .toString(),
          String.format(
              "databaseId : %s, tableId : %s, version: %s %s",
              databaseId,
              tableId,
              baseTableVersion,
              "The requested table has been modified/created by other processes."),
          ce);
    }
  }
}
