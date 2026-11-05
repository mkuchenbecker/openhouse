package com.linkedin.openhouse.housetables.e2e.database;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Pins the springdoc contract of the {@code Database} tag. The generated HTS client ({@code
 * client/hts}) derives {@code DatabaseApi} and its method names from the tag and operationIds
 * below, and the Tables Service compiles against those names, so a change here is a client-breaking
 * change.
 */
@SpringBootTest
@ContextConfiguration(initializers = PropertyOverrideContextInitializer.class)
@AutoConfigureMockMvc
public class DatabasesOpenApiContractTest {

  private static final String DATABASES = "$.paths['/hts/databases']";
  private static final String DATABASE_PROPERTIES = "$.paths['/hts/databases/properties']";

  @Autowired MockMvc mvc;

  @Test
  public void testDatabaseTagPathsOperationIdsAndStatusCodes() throws Exception {
    mvc.perform(MockMvcRequestBuilders.get("/v3/api-docs").accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        // create
        .andExpect(jsonPath(DATABASES + ".post.operationId", is(equalTo("createDatabase"))))
        .andExpect(jsonPath(DATABASES + ".post.tags", contains("Database")))
        .andExpect(jsonPath(DATABASES + ".post.responses.201").exists())
        .andExpect(jsonPath(DATABASES + ".post.responses.400").exists())
        .andExpect(jsonPath(DATABASES + ".post.responses.409").exists())
        .andExpect(
            jsonPath(
                DATABASES + ".post.requestBody.content['application/json'].schema.$ref",
                is(equalTo("#/components/schemas/CreateUpdateEntityRequestBodyDatabase"))))
        .andExpect(
            jsonPath(
                DATABASES + ".post.responses.201.content['application/json'].schema.$ref",
                is(equalTo("#/components/schemas/EntityResponseBodyDatabase"))))
        // get
        .andExpect(jsonPath(DATABASES + ".get.operationId", is(equalTo("getDatabase"))))
        .andExpect(jsonPath(DATABASES + ".get.tags", contains("Database")))
        .andExpect(jsonPath(DATABASES + ".get.parameters[0].name", is(equalTo("databaseId"))))
        .andExpect(jsonPath(DATABASES + ".get.parameters[0].in", is(equalTo("query"))))
        .andExpect(jsonPath(DATABASES + ".get.responses.200").exists())
        .andExpect(jsonPath(DATABASES + ".get.responses.400").exists())
        .andExpect(jsonPath(DATABASES + ".get.responses.404").exists())
        // delete
        .andExpect(jsonPath(DATABASES + ".delete.operationId", is(equalTo("deleteDatabase"))))
        .andExpect(jsonPath(DATABASES + ".delete.tags", contains("Database")))
        .andExpect(jsonPath(DATABASES + ".delete.responses.204").exists())
        .andExpect(jsonPath(DATABASES + ".delete.responses.404").exists())
        // update properties
        .andExpect(
            jsonPath(
                DATABASE_PROPERTIES + ".patch.operationId",
                is(equalTo("updateDatabaseProperties"))))
        .andExpect(jsonPath(DATABASE_PROPERTIES + ".patch.tags", contains("Database")))
        .andExpect(jsonPath(DATABASE_PROPERTIES + ".patch.responses.200").exists())
        .andExpect(jsonPath(DATABASE_PROPERTIES + ".patch.responses.404").exists())
        .andExpect(jsonPath(DATABASE_PROPERTIES + ".patch.responses.409").exists())
        .andExpect(jsonPath(DATABASE_PROPERTIES + ".patch.responses.422").exists())
        .andExpect(
            jsonPath(
                DATABASE_PROPERTIES + ".patch.requestBody.content['application/json'].schema.$ref",
                is(equalTo("#/components/schemas/UpdateDatabasePropertiesRequestBody"))))
        .andExpect(
            jsonPath(
                DATABASE_PROPERTIES
                    + ".patch.responses.200.content['application/json'].schema.$ref",
                is(equalTo("#/components/schemas/UpdateDatabasePropertiesResponseBody"))))
        // schemas
        .andExpect(
            jsonPath("$.components.schemas.Database.properties.databaseUuid.readOnly", is(true)))
        .andExpect(jsonPath("$.components.schemas.Database.properties.databaseId").exists())
        .andExpect(jsonPath("$.components.schemas.Database.properties.properties").exists())
        .andExpect(
            jsonPath(
                "$.components.schemas.UpdateDatabasePropertiesResponseBody.required",
                containsInAnyOrder("updated", "removed")));
  }

  @Test
  public void testUserTablePathsAreUnchanged() throws Exception {
    // The user table surface the Tables Service depends on is untouched by the database entity.
    mvc.perform(MockMvcRequestBuilders.get("/v3/api-docs").accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paths['/hts/tables'].get.operationId", is(equalTo("getUserTable"))))
        .andExpect(jsonPath("$.paths['/hts/tables'].put.operationId", is(equalTo("putUserTable"))))
        .andExpect(jsonPath("$.paths['/hts/tables'].get.tags", contains("UserTable")))
        .andExpect(jsonPath("$.paths['/hts/tables'].delete.tags", contains("UserTable")))
        .andExpect(
            jsonPath(
                "$.paths['/hts/togglestatuses'].get.operationId",
                is(equalTo("getTableToggleStatus"))));
  }
}
