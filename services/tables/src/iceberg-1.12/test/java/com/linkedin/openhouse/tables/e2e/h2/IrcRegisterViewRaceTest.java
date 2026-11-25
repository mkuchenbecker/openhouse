package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.IrcHttp.assertIrcError;
import static com.linkedin.openhouse.tables.e2e.h2.NativeViewHttp.enableViews;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.internal.catalog.CatalogConstants;
import com.linkedin.openhouse.internal.catalog.model.HouseTable;
import com.linkedin.openhouse.internal.catalog.model.HouseTablePrimaryKey;
import com.linkedin.openhouse.internal.catalog.repository.HouseTableRepository;
import com.linkedin.openhouse.internal.catalog.repository.exception.HouseTableConcurrentUpdateException;
import com.linkedin.openhouse.tables.toggle.repository.ToggleStatusesRepository;
import java.util.Collections;
import java.util.UUID;
import java.util.function.Consumer;
import org.apache.iceberg.catalog.Namespace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * A create of the name that lands between the register route's capture and its row write: the row
 * write is refused (409 from House Tables, {@link HouseTableConcurrentUpdateException}), and the
 * route answers 409 {@code AlreadyExistsException} naming the kind that won, a view or a table, as
 * the capture would have. The competing write is made, for real, at the moment of the row write.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IrcReadRoutesTest.StubDefaults.class)
@ContextConfiguration(initializers = {PropertyOverrideContextInitializer.class})
public class IrcRegisterViewRaceTest {
  private String DB = "irc_rv_race";

  @Autowired MockMvc mvc;
  @Autowired ToggleStatusesRepository toggles;
  @SpyBean HouseTableRepository houseTables;

  @AfterEach
  void cleanUp() throws Exception {
    reset(houseTables);
    NativeViewHttp.delete(mvc, DB, "v");
    houseTables.findEntityById(key("v")).ifPresent(row -> houseTables.deleteById(key("v")));
    CommitPathHttp.delete(mvc, DB, "t");
  }

  private HouseTablePrimaryKey key(String name) {
    return HouseTablePrimaryKey.builder().databaseId(DB).tableId(name).build();
  }

  /** A dropped view's file, and the row a competing write of the name stores. */
  private String droppedView() throws Exception {
    enableViews(toggles, DB);
    CommitPathHttp.post(mvc, CommitPathHttp.newTable(DB, "t")).andExpect(status().isCreated());
    String location =
        IrcViewWrite.parsed(
                IrcViewWrite.create(
                    mvc,
                    DB,
                    IrcViewWrite.createRequest(
                        "v",
                        IrcViewRoutesTest.SCHEMA,
                        "SELECT 1",
                        Namespace.of(DB),
                        Collections.emptyMap())))
            .metadataLocation();
    assertEquals(204, IrcViewWrite.drop(mvc, DB, "v").getResponse().getStatus());
    return location;
  }

  private HouseTable competitor(String location) {
    return HouseTable.builder()
        .databaseId(DB)
        .tableId("v")
        .tableUUID(UUID.randomUUID().toString())
        .tableVersion(CatalogConstants.INITIAL_VERSION)
        .tableLocation(location.replace(".metadata.json", "-other.metadata.json"))
        .tableCreator("competitor")
        .creationTime(1L)
        .storageType("hdfs")
        .build();
  }

  /** At the registration's row write, {@code win} stores the competitor first; then the 409. */
  private MvcResult registerLosingTo(String location, Consumer<HouseTable> win) throws Exception {
    doAnswer(
            invocation -> {
              win.accept(competitor(location));
              throw new HouseTableConcurrentUpdateException("key taken", null);
            })
        .when(houseTables)
        .saveView(argThat(row -> row != null && location.equals(row.getTableLocation())));
    return IrcHttp.perform(
        mvc,
        IrcWrite.post(
            IrcRegisterViewRouteTest.registerPath(DB),
            IrcRegisterViewRouteTest.request("v", location)));
  }

  @Test
  void aViewCreatedMeanwhileIs409NamingAView() throws Exception {
    DB = "irc_rv_race_view";
    String location = droppedView();
    MvcResult result = registerLosingTo(location, houseTables::saveView);
    assertEquals(
        "View already exists: " + DB + ".v",
        assertIrcError(result, 409, "AlreadyExistsException").message());
    assertEquals("competitor", houseTables.findEntityById(key("v")).get().getTableCreator());
  }

  @Test
  void aTableCreatedMeanwhileIs409NamingATable() throws Exception {
    DB = "irc_rv_race_table";
    String location = droppedView();
    MvcResult result = registerLosingTo(location, row -> houseTables.save(row));
    assertEquals(
        "Name already exists as a table: " + DB + ".v",
        assertIrcError(result, 409, "AlreadyExistsException").message());
    assertEquals(
        false,
        CatalogConstants.ENTITY_TYPE_VIEW.equals(
            houseTables.findEntityById(key("v")).get().getEntityType()));
  }
}
