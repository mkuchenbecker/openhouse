package com.linkedin.openhouse.tables.services;

import com.linkedin.openhouse.cluster.configs.ClusterProperties;
import com.linkedin.openhouse.common.exception.NoSuchUserTableException;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.Policies;
import com.linkedin.openhouse.tables.commit.CommitContext;
import com.linkedin.openhouse.tables.commit.TableCommit;
import com.linkedin.openhouse.tables.dto.mapper.iceberg.PartitionSpecMapper;
import com.linkedin.openhouse.tables.dto.mapper.iceberg.PoliciesSpecMapper;
import com.linkedin.openhouse.tables.formatversion.FormatVersionRule;
import com.linkedin.openhouse.tables.model.TableDto;
import com.linkedin.openhouse.tables.model.TableDtoPrimaryKey;
import com.linkedin.openhouse.tables.model.TableDtoWithMetadata;
import com.linkedin.openhouse.tables.readbridge.ClientCapabilityGate;
import com.linkedin.openhouse.tables.readbridge.ReadBridgeStripProtection;
import com.linkedin.openhouse.tables.repository.OpenHouseInternalRepository;
import com.linkedin.openhouse.tables.utils.AuthorizationUtils;
import java.util.function.Supplier;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.catalog.TableIdentifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.util.Pair;
import org.springframework.stereotype.Component;

/**
 * The service-layer entry point for a commit on an existing table. The native update routes reach
 * the same core ({@link TableCommitCore}) by decoding their requests; this entry point takes the
 * candidate as Iceberg {@link TableMetadata}, for callers that build one by applying changes to the
 * stored metadata (the planned Iceberg REST Catalog commit route).
 */
@Component
public class TableCommitService {
  @Autowired OpenHouseInternalRepository openHouseInternalRepository;

  @Autowired AuthorizationUtils authorizationUtils;

  @Autowired ClientCapabilityGate clientCapabilityGate;

  @Autowired ReadBridgeStripProtection readBridgeStripProtection;

  @Autowired PartitionSpecMapper partitionSpecMapper;

  @Autowired PoliciesSpecMapper policiesSpecMapper;

  @Autowired ClusterProperties clusterProperties;

  /**
   * Commits {@code candidate} on top of the stored table {@code base}, running every rule the
   * native route of {@code context.getKind()} runs, and persisting through the same guards.
   *
   * @param identifier the table
   * @param baseVersion the metadata location the candidate was built on, as the native API reports
   *     it ({@code tableLocation}); a different stored location is a conflict
   * @param base the stored table, as {@link TablesService#loadTable} returns it
   * @param candidate the complete metadata the caller wants stored
   * @param context the principal, the kind, the native request validation to run first, and the
   *     structured policies as a native request would carry them ({@code null} keeps the stored
   *     ones), which are mapped as the native table PUT maps its request's policies (default
   *     retention patterns, replication schedules) where that PUT maps them, after the lock and
   *     privilege checks
   * @return the stored table, and {@code false}
   */
  public Pair<TableDto, Boolean> commit(
      TableIdentifier identifier,
      String baseVersion,
      TableDtoWithMetadata base,
      TableMetadata candidate,
      CommitContext context) {
    if (context.getValidation() != null) {
      context.getValidation().run();
    }
    TableDto stored = base.getTableDto();
    Policies requested = context.getRequestedPolicies();
    TableDto view =
        CandidateViews.toView(
            stored,
            base.getMetadata(),
            baseVersion,
            candidate,
            requested != null ? requested : stored.getPolicies(),
            partitionSpecMapper,
            context.isReplace(),
            new FormatVersionRule(clusterProperties)
                .isAllowedUpgrade(base.getMetadata().formatVersion(), candidate.formatVersion()));
    Supplier<TableDto> candidateView =
        requested == null
            ? () -> view
            : () -> view.toBuilder().policies(policiesSpecMapper.mapPolicies(requested)).build();
    return commit(new TableCommit(identifier, baseVersion, stored, candidateView, context));
  }

  /**
   * The stored table a commit builds on, loaded as the native update routes load it: the stored
   * table and its metadata from one load, with no read checks (a write runs the write checks).
   *
   * @throws NoSuchUserTableException if there is no such table
   */
  public TableDtoWithMetadata loadBase(TableIdentifier identifier) {
    String databaseId = identifier.namespace().toString();
    String tableId = identifier.name();
    return openHouseInternalRepository
        .findWithMetadataById(
            TableDtoPrimaryKey.builder().databaseId(databaseId).tableId(tableId).build())
        .orElseThrow(() -> new NoSuchUserTableException(databaseId, tableId));
  }

  /**
   * Whether committing {@code candidate} on {@code base} changes snapshots or refs, and so is a
   * commit of the native snapshots route's kind ({@link
   * com.linkedin.openhouse.tables.commit.CommitKind#SNAPSHOTS_UPDATE}).
   */
  public static boolean changesSnapshots(TableMetadata base, TableMetadata candidate) {
    return CandidateViews.snapshotsChanged(base, candidate);
  }

  /** Commits an already decoded {@link TableCommit}. */
  public Pair<TableDto, Boolean> commit(TableCommit commit) {
    return new TableCommitCore(
            openHouseInternalRepository,
            authorizationUtils,
            clientCapabilityGate,
            readBridgeStripProtection)
        .commit(commit);
  }
}
