package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.internal.catalog.model.HouseTablePrimaryKey;
import com.linkedin.openhouse.internal.catalog.repository.HouseTableRepository;
import com.linkedin.openhouse.tables.mock.properties.AuthorizationPropertiesInitializer;
import com.linkedin.openhouse.tablestest.HouseTablesH2Repository;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The per-database live table quota as an HTTP caller sees it, on the create paths that reach the
 * catalog: a staged create and a restore. Configured like {@link TableCountLimitTest}, with a limit
 * of two. Each test uses its own database so nothing leaks between tests.
 */
@SpringBootTest(properties = "cluster.tables.max-tables-per-database=2")
@AutoConfigureMockMvc
@ContextConfiguration(
    initializers = {
      PropertyOverrideContextInitializer.class,
      AuthorizationPropertiesInitializer.class
    })
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
public class CommitPathTableCountLimitTest {

  @Autowired private HouseTablesH2Repository houseTablesH2Store;
  private static final String LIMIT_MESSAGE_TAIL =
      "has 2 live tables, reaching the configured limit of 2.";

  @Autowired MockMvc mvc;
  @Autowired HouseTableRepository houseTables;

  @AfterEach
  void cleanUp() {
    houseTablesH2Store
        .softDeletedTables()
        .keySet()
        .removeIf(k -> k.getDatabaseId().startsWith("cl_"));
  }

  private void fill(String db) throws Exception {
    post(mvc, newTable(db, "t1")).andExpect(status().isCreated());
    post(mvc, newTable(db, "t2")).andExpect(status().isCreated());
  }

  private void dropAll(String db, String... tables) throws Exception {
    for (String t : tables) {
      delete(mvc, db, t);
    }
  }

  /**
   * DELETE over HTTP is a hard delete (the tables service purges), so a soft-deleted row can only
   * be made in the store, the way the house tables service does it for a non-purge delete.
   */
  private void softDelete(String db, String table) {
    houseTables.deleteById(
        HouseTablePrimaryKey.builder().databaseId(db).tableId(table).build(), false);
  }

  /** The deletion timestamp the soft-deleted listing reports for the table. */
  private long deletedAtMsOf(String db, String table) throws Exception {
    List<Number> deletedAt =
        read(
            json(softDeleted(mvc, db)),
            "$.pageResults.content[?(@.tableId=='" + table + "')].deletedAtMs");
    return deletedAt.get(0).longValue();
  }

  private int softDeletedListingCount(String db, String table) throws Exception {
    List<Object> rows =
        read(json(softDeleted(mvc, db)), "$.pageResults.content[?(@.tableId=='" + table + "')]");
    return rows.size();
  }

  @Test
  void stagedCreateAtTheLimitIsRejectedWith422AndNothingIsCreated() throws Exception {
    String db = "cl_staged";
    fill(db);

    expectError(
        post(mvc, newTable(db, "t3").toBuilder().stageCreate(true).build()),
        422,
        "Database " + db + " " + LIMIT_MESSAGE_TAIL);

    get(mvc, db, "t3").andExpect(status().isNotFound());
    // a non-staged create and a PUT-create are refused the same way
    expectError(post(mvc, newTable(db, "t3")), 422, "Database " + db + " " + LIMIT_MESSAGE_TAIL);
    expectError(put(mvc, newTable(db, "t3")), 422, "Database " + db + " " + LIMIT_MESSAGE_TAIL);
    get(mvc, db, "t3").andExpect(status().isNotFound());

    // dropping one table frees a slot, which a staged create then takes (staged tables are not
    // visible to GET, so only the status is observable)
    delete(mvc, db, "t1").andExpect(status().isNoContent());
    post(mvc, newTable(db, "t3").toBuilder().stageCreate(true).build())
        .andExpect(status().isCreated());
    get(mvc, db, "t3").andExpect(status().isNotFound());

    dropAll(db, "t2");
  }

  @Test
  void restoreAtTheLimitIsRejectedWith422AndSucceedsAfterADrop() throws Exception {
    String db = "cl_restore";
    fill(db);
    softDelete(db, "t1");
    long deletedAtMs = deletedAtMsOf(db, "t1");
    post(mvc, newTable(db, "t3")).andExpect(status().isCreated());

    expectError(
        restore(mvc, db, "t1", deletedAtMs), 422, "Database " + db + " " + LIMIT_MESSAGE_TAIL);

    get(mvc, db, "t1").andExpect(status().isNotFound());
    assertEquals(1, softDeletedListingCount(db, "t1"));
    assertEquals(deletedAtMs, deletedAtMsOf(db, "t1"));

    delete(mvc, db, "t3").andExpect(status().isNoContent());
    restore(mvc, db, "t1", deletedAtMs).andExpect(status().isNoContent());
    // The restored table counts as live again: the database is full and a create is refused. A
    // soft delete keeps the files, so the restored table loads.
    get(mvc, db, "t1").andExpect(status().isOk());
    expectError(post(mvc, newTable(db, "t4")), 422, "Database " + db + " " + LIMIT_MESSAGE_TAIL);
    assertEquals(0, softDeletedListingCount(db, "t1"));

    dropAll(db, "t1", "t2");
  }
}
