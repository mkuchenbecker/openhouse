package com.linkedin.openhouse.tables.services;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.linkedin.openhouse.internal.catalog.model.SoftDeletedTableDto;
import com.linkedin.openhouse.internal.catalog.model.SoftDeletedTablePrimaryKey;
import com.linkedin.openhouse.tables.api.spec.v0.request.CreateUpdateLockRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.request.CreateUpdateTableRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.request.UpdateAclPoliciesRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.LockReason;
import com.linkedin.openhouse.tables.api.spec.v0.response.components.AclPolicy;
import com.linkedin.openhouse.tables.model.TableDto;
import com.linkedin.openhouse.tables.model.TableDtoPrimaryKey;
import com.linkedin.openhouse.tables.repository.OpenHouseInternalRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.util.Pair;

/**
 * An implementation of {@link TablesService} or {@link OpenHouseInternalRepository} written against
 * the methods those interfaces have always had still compiles: every method added to them since has
 * a default, which refuses. This class is such an implementation of each; it compiles only while
 * that holds.
 */
class ExtensionPointDefaultsTest {

  @Test
  void aTablesServiceWithOnlyItsOriginalMethodsRefusesTheNewOnes() {
    TablesService service = new OriginalTablesService();
    assertThrows(UnsupportedOperationException.class, () -> service.loadTable("d", "t", "p"));
    assertThrows(
        UnsupportedOperationException.class,
        () -> service.createTable(null, "p", UUID.randomUUID()));
    assertThrows(UnsupportedOperationException.class, () -> service.stageCreateTable(null, "p"));
  }

  @Test
  void aRepositoryWithOnlyItsOriginalMethodsRefusesTheNewOnes() {
    OpenHouseInternalRepository repository = new OriginalRepository();
    assertThrows(
        UnsupportedOperationException.class,
        () -> repository.findWithMetadataById(TableDtoPrimaryKey.builder().build()));
    assertThrows(UnsupportedOperationException.class, () -> repository.stageCreate(null));
  }

  /** The methods {@link TablesService} has always had, and no other. */
  private static class OriginalTablesService implements TablesService {
    @Override
    public TableDto getTable(String databaseId, String tableId, String actingPrincipal) {
      return null;
    }

    @Override
    public boolean tableExists(String databaseId, String tableId) {
      return false;
    }

    @Override
    public List<TableDto> searchTables(String databaseId) {
      return null;
    }

    @Override
    public Page<TableDto> searchTables(
        String databaseId,
        int page,
        int size,
        String sortBy,
        List<String> fields,
        String actingPrincipal) {
      return null;
    }

    @Override
    public Pair<TableDto, Boolean> putTable(
        CreateUpdateTableRequestBody createUpdateTableRequestBody,
        String tableCreatorUpdater,
        Boolean failOnExist) {
      return null;
    }

    @Override
    public void deleteTable(String databaseId, String tableId, String actingPrincipal) {}

    @Override
    public void renameTable(
        String fromDatabaseId,
        String fromTableId,
        String toDatabaseId,
        String toTableId,
        String actingPrincipal) {}

    @Override
    public void updateAclPolicies(
        String databaseId,
        String tableId,
        UpdateAclPoliciesRequestBody updateAclPoliciesRequestBody,
        String actingPrincipal) {}

    @Override
    public List<AclPolicy> getAclPolicies(
        String databaseId, String tableId, String actingPrincipal) {
      return null;
    }

    @Override
    public List<AclPolicy> getAclPolicies(
        String databaseId, String tableId, String actingPrincipal, String userPrincipal) {
      return null;
    }

    @Override
    public void createLock(
        String databaseId,
        String tableId,
        CreateUpdateLockRequestBody createUpdateLockRequestBody,
        String tableCreatorUpdator) {}

    @Override
    public void deleteLock(String databaseId, String tableId, String actingPrincipal) {}

    @Override
    public void deleteLock(
        String databaseId, String tableId, LockReason reason, String actingPrincipal) {}

    @Override
    public Page<SoftDeletedTableDto> searchSoftDeletedTables(
        String databaseId, String tableId, int page, int size, String sortBy) {
      return null;
    }

    @Override
    public void purgeSoftDeletedTables(
        String databaseId, String tableId, long purgeAfterMs, String actingPrincipal) {}

    @Override
    public void restoreTable(
        String databaseId, String tableId, long deletedAtMs, String actingPrincipal) {}
  }

  /** The methods {@link OpenHouseInternalRepository} has always had, and no other. */
  private static class OriginalRepository implements OpenHouseInternalRepository {
    @Override
    public Optional<TableDto> findTableRefById(TableDtoPrimaryKey tableDtoPrimaryKey) {
      return Optional.empty();
    }

    @Override
    public List<TableDtoPrimaryKey> findAllIds() {
      return null;
    }

    @Override
    public Page<TableDtoPrimaryKey> findAllIds(Pageable pageable) {
      return null;
    }

    @Override
    public List<TableDto> searchTables(String databaseId) {
      return null;
    }

    @Override
    public Page<TableDto> searchTables(String databaseId, Pageable pageable) {
      return null;
    }

    @Override
    public Page<TableDto> searchTables(String databaseId, Pageable pageable, List<String> fields) {
      return null;
    }

    @Override
    public void rename(TableDtoPrimaryKey from, TableDtoPrimaryKey to) {}

    @Override
    public Page<SoftDeletedTableDto> searchSoftDeletedTables(
        String databaseId, String tableId, Pageable pageable) {
      return null;
    }

    @Override
    public void purgeSoftDeletedTableById(
        TableDtoPrimaryKey tableDtoPrimaryKey, long purgeAfterMs) {}

    @Override
    public void restoreTable(SoftDeletedTablePrimaryKey softDeletedTablePrimaryKey) {}

    @Override
    public Iterable<TableDto> findAll(Sort sort) {
      return null;
    }

    @Override
    public Page<TableDto> findAll(Pageable pageable) {
      return null;
    }

    @Override
    public <S extends TableDto> S save(S entity) {
      return null;
    }

    @Override
    public <S extends TableDto> Iterable<S> saveAll(Iterable<S> entities) {
      return null;
    }

    @Override
    public Optional<TableDto> findById(TableDtoPrimaryKey id) {
      return Optional.empty();
    }

    @Override
    public boolean existsById(TableDtoPrimaryKey id) {
      return false;
    }

    @Override
    public Iterable<TableDto> findAll() {
      return null;
    }

    @Override
    public Iterable<TableDto> findAllById(Iterable<TableDtoPrimaryKey> ids) {
      return null;
    }

    @Override
    public long count() {
      return 0;
    }

    @Override
    public void deleteById(TableDtoPrimaryKey id) {}

    @Override
    public void delete(TableDto entity) {}

    @Override
    public void deleteAllById(Iterable<? extends TableDtoPrimaryKey> ids) {}

    @Override
    public void deleteAll(Iterable<? extends TableDto> entities) {}

    @Override
    public void deleteAll() {}
  }
}
