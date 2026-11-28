package com.linkedin.openhouse.tables.e2e.namespace;

import com.linkedin.openhouse.HouseTablesSpringApplication;
import com.linkedin.openhouse.housetables.client.api.NamespaceApi;
import com.linkedin.openhouse.housetables.client.invoker.ApiClient;
import com.linkedin.openhouse.internal.catalog.repository.HouseNamespaceRepository;
import com.linkedin.openhouse.internal.catalog.repository.HouseNamespaceRepositoryImpl;
import com.linkedin.openhouse.tablestest.HouseNamespaceRepositoryContract;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;

/**
 * The repository contract the embedded stand-in ({@code HouseNamespacesInMemoryRepository}) is held
 * to, run against the real repository over a real House Tables service on H2, so that the stand-in
 * cannot drift from the store.
 */
@SpringBootTest(
    classes = HouseTablesSpringApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "cluster.housetables.database.type=IN_MEMORY",
      "cluster.housetables.database.url=jdbc:h2:mem:namespace_contract;MODE=MYSQL;DB_CLOSE_DELAY=-1",
      "cluster.storage.root-path=build/namespace-contract-storage",
      "spring.jpa.hibernate.ddl-auto=none",
      "spring.sql.init.mode=always",
      "spring.jpa.defer-datasource-initialization=true",
      "spring.autoconfigure.exclude="
          + "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,"
          + "org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration"
    })
@DirtiesContext
class HouseNamespaceRepositoryImplContractTest extends HouseNamespaceRepositoryContract {
  @LocalServerPort private int port;
  private HouseNamespaceRepository repository;

  @BeforeEach
  void connect() {
    ApiClient client = new ApiClient();
    client.setBasePath("http://localhost:" + port);
    repository = new HouseNamespaceRepositoryImpl(new NamespaceApi(client));
  }

  @Override
  protected HouseNamespaceRepository repository() {
    return repository;
  }
}
