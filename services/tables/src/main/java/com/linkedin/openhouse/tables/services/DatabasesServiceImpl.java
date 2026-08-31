package com.linkedin.openhouse.tables.services;

import com.linkedin.openhouse.cluster.configs.ClusterProperties;
import com.linkedin.openhouse.common.exception.RequestValidationFailureException;
import com.linkedin.openhouse.internal.catalog.model.HouseNamespace;
import com.linkedin.openhouse.internal.catalog.repository.HouseNamespaceRepository;
import com.linkedin.openhouse.tables.api.spec.v0.request.UpdateAclPoliciesRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.response.components.AclPolicy;
import com.linkedin.openhouse.tables.authorization.AuthorizationHandler;
import com.linkedin.openhouse.tables.authorization.Privileges;
import com.linkedin.openhouse.tables.dto.mapper.DatabasesMapper;
import com.linkedin.openhouse.tables.model.DatabaseDto;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * Default Database Service Implementation for /database REST endpoint.
 *
 * <p>The listing reads the namespace store. It used to derive the set of databases from the table
 * store — {@code SELECT DISTINCT database_id} in all but name — which made two answers to "does
 * this database exist" that could disagree: a database created through {@code POST /v1/namespaces}
 * held no tables and so was invisible here, while a database whose last table was dropped stayed
 * visible until the drop landed. One store answers both surfaces now, so a database created through
 * either one appears in both, and it stops appearing when it is dropped rather than when its last
 * table is.
 */
@Component
public class DatabasesServiceImpl implements DatabasesService {
  @Autowired HouseNamespaceRepository houseNamespaceRepository;
  @Autowired ClusterProperties clusterProperties;
  @Autowired DatabasesMapper databasesMapper;
  @Autowired AuthorizationHandler authorizationHandler;
  @Autowired NamespaceStoreReadGate namespaceStoreReadGate;

  /** The one property a database has, and therefore the only thing a page of them can sort on. */
  private static final String SORTABLE_PROPERTY = "databaseId";

  @Override
  public List<DatabaseDto> getAllDatabases() {
    namespaceStoreReadGate.requireStoreIsAuthoritative();
    return databases();
  }

  @Override
  public Page<DatabaseDto> getAllDatabases(int page, int size, String sortBy) {
    if (!StringUtils.isEmpty(sortBy) && !SORTABLE_PROPERTY.equals(sortBy)) {
      // Previously this string reached a sort over house_table columns, so "tableId" was accepted
      // and sorted a list of databases by a column that says nothing about them. A database row has
      // one property; anything else is a request the service cannot honour and should not pretend
      // to.
      throw new RequestValidationFailureException(
          String.format("Databases can only be sorted by %s, not %s", SORTABLE_PROPERTY, sortBy));
    }
    namespaceStoreReadGate.requireStoreIsAuthoritative();
    // Paged in memory over one row per database. The read it replaces paged over one row per
    // *table* and mapped each to its database, so a page of 50 could hold two databases fifty
    // times; this is both smaller and actually a page of databases.
    List<DatabaseDto> all = databases();
    all.sort((left, right) -> left.getDatabaseId().compareTo(right.getDatabaseId()));
    PageRequest pageRequest = PageRequest.of(page, size);
    int from = Math.min((int) pageRequest.getOffset(), all.size());
    int to = Math.min(from + size, all.size());
    return new PageImpl<>(all.subList(from, to), pageRequest, all.size());
  }

  /**
   * Every stored namespace is a database: {@code cluster.tables.namespace.max-depth} is pinned to
   * one at startup, so no deeper namespace can have been written.
   */
  private List<DatabaseDto> databases() {
    List<DatabaseDto> databases = new ArrayList<>();
    for (HouseNamespace namespace : houseNamespaceRepository.findAll()) {
      databases.add(
          databasesMapper.toDatabaseDto(
              namespace.getNamespaceId(), clusterProperties.getClusterName()));
    }
    return databases;
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
