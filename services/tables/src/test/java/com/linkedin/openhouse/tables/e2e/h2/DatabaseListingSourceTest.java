package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.model.TableModelConstants.GET_TABLE_RESPONSE_BODY;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.linkedin.openhouse.cluster.storage.StorageManager;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.internal.catalog.repository.HouseNamespaceRepository;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * {@code GET /v1/databases} used to be a {@code SELECT DISTINCT database_id} over the table store
 * under another name, which made it a second, disagreeing answer to which databases exist. These
 * pin the two ways it disagreed.
 */
@SpringBootTest(
    classes = SpringH2Application.class,
    properties = "cluster.tables.iceberg-rest.enabled=true")
@AutoConfigureMockMvc
@ContextConfiguration(initializers = PropertyOverrideContextInitializer.class)
@WithMockUser(username = "testUser")
public class DatabaseListingSourceTest {

  @Autowired MockMvc mvc;

  @Autowired StorageManager storageManager;

  @Autowired HouseNamespaceRepository houseNamespaceRepository;

  /**
   * The listing is now the contents of a store other tests write to and never clean up, so this
   * class owns that store for the length of each of its tests.
   */
  @BeforeEach
  public void emptyTheNamespaceStore() {
    houseNamespaceRepository.deleteAll();
  }

  /**
   * The asymmetry this closes: a namespace created through the Iceberg REST surface held no tables,
   * so the database listing derived from tables could not see it. One store answers both surfaces
   * now.
   */
  @Test
  public void aNamespaceCreatedOverRestAppearsInTheDatabaseListing() throws Exception {
    mvc.perform(
            MockMvcRequestBuilders.post("/v1/iceberg/namespaces")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"namespace\":[\"restmade\"],\"properties\":{}}")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk());

    mvc.perform(MockMvcRequestBuilders.get("/v1/databases").accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.results[*].databaseId", Matchers.contains("restmade")));
  }

  /**
   * The paged listing is now a page of databases. It used to page over table rows and map each to
   * its database without deduplicating, so {@code /v2/databases?size=50} could answer with the same
   * database fifty times while a database with no tables on that page never appeared at all.
   */
  @Test
  public void thePagedListingPagesOverDatabasesNotTables() throws Exception {
    for (String databaseId : new String[] {"pagedb1", "pagedb2", "pagedb3"}) {
      mvc.perform(
              MockMvcRequestBuilders.post("/v1/iceberg/namespaces")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"namespace\":[\"" + databaseId + "\"],\"properties\":{}}")
                  .accept(MediaType.APPLICATION_JSON))
          .andExpect(status().isOk());
    }

    mvc.perform(
            MockMvcRequestBuilders.get("/v2/databases")
                .param("page", "0")
                .param("size", "2")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath(
                "$.pageResults.content[*].databaseId", Matchers.contains("pagedb1", "pagedb2")))
        .andExpect(jsonPath("$.pageResults.totalElements").value(3));

    mvc.perform(
            MockMvcRequestBuilders.get("/v2/databases")
                .param("page", "1")
                .param("size", "2")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.pageResults.content[*].databaseId", Matchers.contains("pagedb3")));
  }

  /**
   * {@code sortBy} used to reach a sort over house_table columns, so {@code sortBy=tableId} was
   * accepted and ordered a list of databases by a column that says nothing about one. A database
   * row has a single property; a request to order by anything else is one the service cannot
   * honour, and answering it in an order the caller did not ask for is the same class of silent
   * wrong answer as an empty listing.
   */
  @Test
  public void anUnsortableFieldIsRefusedRatherThanIgnored() throws Exception {
    mvc.perform(
            MockMvcRequestBuilders.get("/v2/databases")
                .param("sortBy", "tableId")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest());

    mvc.perform(
            MockMvcRequestBuilders.get("/v2/databases")
                .param("sortBy", "databaseId")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk());
  }

  /**
   * The other direction. A database used to exist for exactly as long as it held a table, so
   * dropping the last table erased it from the listing without anybody dropping the database. It
   * exists until it is dropped now, which is what makes {@code DELETE /v1/iceberg/namespaces/{ns}}
   * mean anything.
   */
  @Test
  public void aDatabaseOutlivesItsLastTable() throws Exception {
    RequestAndValidateHelper.createTableAndValidateResponse(
        GET_TABLE_RESPONSE_BODY, mvc, storageManager);
    String databaseId = GET_TABLE_RESPONSE_BODY.getDatabaseId();

    RequestAndValidateHelper.deleteTableAndValidateResponse(mvc, GET_TABLE_RESPONSE_BODY);

    mvc.perform(MockMvcRequestBuilders.get("/v1/databases").accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.results[*].databaseId", Matchers.contains(databaseId)));

    mvc.perform(MockMvcRequestBuilders.delete("/v1/iceberg/namespaces/" + databaseId))
        .andExpect(status().isNoContent());

    mvc.perform(MockMvcRequestBuilders.get("/v1/databases").accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(content().json("{\"results\":[]}"));
  }
}
