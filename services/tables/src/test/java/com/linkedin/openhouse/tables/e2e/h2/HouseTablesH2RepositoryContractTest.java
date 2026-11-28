package com.linkedin.openhouse.tables.e2e.h2;

import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.internal.catalog.repository.HouseTableRepository;
import com.linkedin.openhouse.tablestest.HouseTableRepositoryContract;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

/**
 * The House Tables stand-in of the tables service's e2e tests ({@link SpringH2Application}) against
 * the repository contract the real store passes.
 */
@SpringBootTest(classes = SpringH2Application.class)
@ContextConfiguration(initializers = PropertyOverrideContextInitializer.class)
public class HouseTablesH2RepositoryContractTest extends HouseTableRepositoryContract {

  @Autowired private HouseTableRepository repository;

  @Override
  protected HouseTableRepository repository() {
    return repository;
  }
}
