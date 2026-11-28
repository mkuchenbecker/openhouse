package com.linkedin.openhouse.tables.e2e.namespace;

import com.linkedin.openhouse.HouseTablesSpringApplication;
import com.linkedin.openhouse.housetables.client.api.UserTableApi;
import com.linkedin.openhouse.housetables.client.invoker.ApiClient;
import com.linkedin.openhouse.internal.catalog.mapper.HouseTableMapperImpl;
import com.linkedin.openhouse.internal.catalog.repository.HouseTableRepository;
import com.linkedin.openhouse.internal.catalog.repository.HouseTableRepositoryImpl;
import com.linkedin.openhouse.tablestest.HouseTableRepositoryContract;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The repository contract the embedded House Tables stand-in ({@code HouseTablesH2Repository}) is
 * held to, run against the real repository over a real House Tables service, so that the stand-in
 * cannot drift from the store.
 *
 * <p>The service runs on H2 with its own bootstrap schema ({@code schema.sql}), in MySQL mode and
 * with {@code IGNORECASE=TRUE}: the production tables are {@code utf8mb4_0900_ai_ci}, so their keys
 * ignore letter case, and H2's default would make a key that differs only in case a second row.
 */
@SpringBootTest(
    classes = HouseTablesSpringApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "cluster.housetables.database.type=IN_MEMORY",
      "cluster.housetables.database.url="
          + "jdbc:h2:mem:house_table_contract;MODE=MYSQL;IGNORECASE=TRUE;DB_CLOSE_DELAY=-1",
      "cluster.storage.root-path=build/house-table-contract-storage",
      "spring.jpa.hibernate.ddl-auto=none",
      "spring.sql.init.mode=always",
      "spring.jpa.defer-datasource-initialization=true",
      "spring.autoconfigure.exclude="
          + "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,"
          + "org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration"
    })
@DirtiesContext
class HouseTableRepositoryImplContractTest extends HouseTableRepositoryContract {
  @LocalServerPort private int port;
  private HouseTableRepository repository;

  @BeforeEach
  void connect() {
    ApiClient client = new ApiClient();
    client.setBasePath("http://localhost:" + port);
    HouseTableRepositoryImpl real = new HouseTableRepositoryImpl();
    ReflectionTestUtils.setField(real, "apiInstance", new UserTableApi(client));
    ReflectionTestUtils.setField(real, "houseTableMapper", new HouseTableMapperImpl());
    repository = real;
  }

  @Override
  protected HouseTableRepository repository() {
    return repository;
  }
}
