package com.linkedin.openhouse.tables.services;

import static com.linkedin.openhouse.common.utils.PageableUtil.createPageable;

import com.linkedin.openhouse.cluster.configs.ClusterProperties;
import com.linkedin.openhouse.common.exception.NoSuchEntityException;
import com.linkedin.openhouse.common.exception.UnprocessableEntityException;
import com.linkedin.openhouse.tables.api.spec.v0.request.UpdateAclPoliciesRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.response.components.AclPolicy;
import com.linkedin.openhouse.tables.authorization.AuthorizationHandler;
import com.linkedin.openhouse.tables.authorization.Privileges;
import com.linkedin.openhouse.tables.dto.mapper.DatabasesMapper;
import com.linkedin.openhouse.tables.model.DatabaseDto;
import com.linkedin.openhouse.tables.model.DatabasePropertiesUpdateDto;
import com.linkedin.openhouse.tables.model.TableDtoPrimaryKey;
import com.linkedin.openhouse.tables.repository.DatabaseEntityRepository;
import com.linkedin.openhouse.tables.repository.OpenHouseInternalRepository;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/** Default Database Service Implementation for /database REST endpoint. */
@Component
public class DatabasesServiceImpl implements DatabasesService {
  private static final String DATABASE_ENTITY_TYPE = "Database";

  @Autowired OpenHouseInternalRepository openHouseInternalRepository;
  @Autowired DatabaseEntityRepository databaseEntityRepository;
  @Autowired ClusterProperties clusterProperties;
  @Autowired DatabasesMapper databasesMapper;
  @Autowired AuthorizationHandler authorizationHandler;

  @Override
  public List<DatabaseDto> getAllDatabases() {
    List<TableDtoPrimaryKey> tableIdentifiers = openHouseInternalRepository.findAllIds();
    return tableIdentifiers.stream()
        .map(
            tableDtoPrimaryKey ->
                databasesMapper.toDatabaseDto(
                    tableDtoPrimaryKey.getDatabaseId(), clusterProperties.getClusterName()))
        .distinct()
        .collect(Collectors.toList());
  }

  @Override
  public Page<DatabaseDto> getAllDatabases(int page, int size, String sortBy) {
    Pageable pageable = createPageable(page, size, sortBy, null);
    Page<TableDtoPrimaryKey> tableIdentifiers = openHouseInternalRepository.findAllIds(pageable);
    return tableIdentifiers.map(
        tableDtoPrimaryKey ->
            databasesMapper.toDatabaseDto(
                tableDtoPrimaryKey.getDatabaseId(), clusterProperties.getClusterName()));
  }

  @Override
  public void updateDatabaseAclPolicies(
      String databaseId,
      UpdateAclPoliciesRequestBody updateAclPoliciesRequestBody,
      String actingPrincipal) {

    DatabaseDto databaseDto = DatabaseDto.builder().databaseId(databaseId).build();
    checkDatabasePrivilege(databaseDto, actingPrincipal, Privileges.UPDATE_ACL);

    String role = updateAclPoliciesRequestBody.getRole();
    String granteePrincipal = updateAclPoliciesRequestBody.getPrincipal();

    switch (updateAclPoliciesRequestBody.getOperation()) {
      case GRANT:
        authorizationHandler.grantRole(role, granteePrincipal, databaseDto, actingPrincipal);
        break;
      case REVOKE:
        authorizationHandler.revokeRole(role, granteePrincipal, databaseDto, actingPrincipal);
        break;
      default:
        throw new UnsupportedOperationException("Only GRANT and REVOKE are supported");
    }
  }

  @Override
  public List<AclPolicy> getDatabaseAclPolicies(String databaseId, String actingPrincipal) {
    DatabaseDto databaseDto = DatabaseDto.builder().databaseId(databaseId).build();
    return authorizationHandler.listAclPolicies(databaseDto);
  }

  @Override
  public DatabaseDto createDatabase(
      String databaseId, Map<String, String> properties, String actingPrincipal) {
    DatabaseDto requested =
        DatabaseDto.builder()
            .databaseId(databaseId)
            .clusterId(clusterProperties.getClusterName())
            .properties(properties == null ? Collections.emptyMap() : properties)
            .build();
    checkDatabasePrivilege(requested, actingPrincipal, Privileges.SYSTEM_ADMIN);
    return databaseEntityRepository
        .create(requested)
        .toBuilder()
        .clusterId(clusterProperties.getClusterName())
        .build();
  }

  @Override
  public DatabaseDto getDatabase(String databaseId, String actingPrincipal) {
    // Reads require only authentication, which the security filter has already enforced.
    return findDatabaseOrThrow(databaseId);
  }

  @Override
  public void deleteDatabase(String databaseId, String actingPrincipal) {
    checkDatabasePrivilege(
        DatabaseDto.builder().databaseId(databaseId).build(),
        actingPrincipal,
        Privileges.SYSTEM_ADMIN);
    DatabaseDto existing = findDatabaseOrThrow(databaseId);
    // Only the database entity is removed; tables in the database are untouched (E6).
    databaseEntityRepository.delete(existing);
  }

  @Override
  public DatabasePropertiesUpdateDto updateDatabaseProperties(
      String databaseId,
      Map<String, String> updates,
      List<String> removals,
      String actingPrincipal) {
    checkDatabasePrivilege(
        DatabaseDto.builder().databaseId(databaseId).build(),
        actingPrincipal,
        Privileges.SYSTEM_ADMIN);
    Map<String, String> safeUpdates = updates == null ? Collections.emptyMap() : updates;
    List<String> safeRemovals = removals == null ? Collections.emptyList() : removals;
    for (String key : safeRemovals) {
      if (safeUpdates.containsKey(key)) {
        throw new UnprocessableEntityException(
            String.format("Property key [%s] cannot be present in both updates and removals", key));
      }
    }
    return databaseEntityRepository.updateProperties(databaseId, safeUpdates, safeRemovals);
  }

  private DatabaseDto findDatabaseOrThrow(String databaseId) {
    return databaseEntityRepository
        .findByDatabaseIdIgnoreCase(databaseId)
        .map(dto -> dto.toBuilder().clusterId(clusterProperties.getClusterName()).build())
        .orElseThrow(() -> new NoSuchEntityException(DATABASE_ENTITY_TYPE, databaseId));
  }

  /** Throws AccessDeniedException if actingPrincipal is not authorized to act on the database. */
  private void checkDatabasePrivilege(
      DatabaseDto databaseDto, String actingPrincipal, Privileges privilege) {
    if (!authorizationHandler.checkAccessDecision(actingPrincipal, databaseDto, privilege)) {
      throw new AccessDeniedException(
          String.format(
              "Operation on database [%s] failed as user [%s] is unauthorized",
              databaseDto.getDatabaseId(), actingPrincipal));
    }
  }
}
