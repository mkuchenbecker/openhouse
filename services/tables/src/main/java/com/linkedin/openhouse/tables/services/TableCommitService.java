package com.linkedin.openhouse.tables.services;

import com.linkedin.openhouse.tables.api.spec.v0.request.components.Policies;
import com.linkedin.openhouse.tables.commit.CommitContext;
import com.linkedin.openhouse.tables.commit.TableCommit;
import com.linkedin.openhouse.tables.dto.mapper.iceberg.PartitionSpecMapper;
import com.linkedin.openhouse.tables.model.TableDto;
import com.linkedin.openhouse.tables.model.TableDtoWithMetadata;
import com.linkedin.openhouse.tables.readbridge.ClientCapabilityGate;
import com.linkedin.openhouse.tables.readbridge.ReadBridgeStripProtection;
import com.linkedin.openhouse.tables.repository.OpenHouseInternalRepository;
import com.linkedin.openhouse.tables.utils.AuthorizationUtils;
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

  /**
   * Commits {@code candidate} on top of the stored table {@code base}, running every rule the
   * native route of {@code context.getKind()} runs, and persisting through the same guards.
   *
   * @param identifier the table
   * @param baseVersion the metadata location the candidate was built on, as the native API reports
   *     it ({@code tableLocation}); a different stored location is a conflict
   * @param base the stored table, as {@link TablesService#loadTable} returns it
   * @param candidate the complete metadata the caller wants stored
   * @param context the principal, the kind, and the structured policies to store ({@code null}
   *     keeps the stored ones)
   * @return the stored table, and {@code false}
   */
  public Pair<TableDto, Boolean> commit(
      TableIdentifier identifier,
      String baseVersion,
      TableDtoWithMetadata base,
      TableMetadata candidate,
      CommitContext context) {
    TableDto stored = base.getTableDto();
    Policies policies =
        context.getRequestedPolicies() != null
            ? context.getRequestedPolicies()
            : stored.getPolicies();
    TableDto view =
        CandidateViews.toView(
            stored, base.getMetadata(), baseVersion, candidate, policies, partitionSpecMapper);
    return commit(new TableCommit(identifier, baseVersion, stored, () -> view, context));
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
