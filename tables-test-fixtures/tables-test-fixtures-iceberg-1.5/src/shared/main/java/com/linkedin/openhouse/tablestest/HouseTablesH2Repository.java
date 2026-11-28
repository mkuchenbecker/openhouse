package com.linkedin.openhouse.tablestest;

import com.linkedin.openhouse.internal.catalog.model.HouseTable;
import com.linkedin.openhouse.internal.catalog.model.HouseTablePrimaryKey;
import com.linkedin.openhouse.internal.catalog.repository.HouseTableRepository;
import java.util.Optional;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

/**
 * The {@link HouseTableRepository} the embedded tables services of the tests inject in place of the
 * real one, which calls a House Tables service. With {@link Primary}, it is the default injection.
 *
 * <p>It answers as the real store answers ({@code HouseTableRepositoryContract} holds both to the
 * same rules, the real one over a real House Tables service): every operation is implemented by
 * {@link HouseTablesH2StoreImpl}, a fragment Spring Data composes into this repository, over rows
 * in the application context's own H2 database; Spring Data supplies only the methods the tables
 * service never calls. The few places where it cannot answer as the real store does are listed on
 * {@link HouseTablesH2StoreImpl}.
 */
@Repository
@Primary
public interface HouseTablesH2Repository extends HouseTableRepository, HouseTablesH2Store {

  /* Declared again only to tell the compiler which of two identical inherited declarations a call
   * names; Spring Data routes each to the implementation either way. */

  @Override
  <S extends HouseTable> S save(S entity);

  @Override
  Optional<HouseTable> findById(HouseTablePrimaryKey key);

  @Override
  boolean existsById(HouseTablePrimaryKey key);

  @Override
  Iterable<HouseTable> findAll();

  @Override
  Page<HouseTable> findAll(Pageable pageable);

  @Override
  void deleteById(HouseTablePrimaryKey key);

  @Override
  void delete(HouseTable entity);

  @Override
  void deleteAll();

  @Override
  <S extends HouseTable> Iterable<S> saveAll(Iterable<S> entities);

  @Override
  long count();

  @Override
  Iterable<HouseTable> findAll(Sort sort);

  @Override
  Iterable<HouseTable> findAllById(Iterable<HouseTablePrimaryKey> keys);

  @Override
  void deleteAllById(Iterable<? extends HouseTablePrimaryKey> keys);

  @Override
  void deleteAll(Iterable<? extends HouseTable> entities);
}
