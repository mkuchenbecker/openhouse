package com.linkedin.openhouse.tables.e2e.h2;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
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
 * Pins the springdoc contract of the database entity routes. The generated Tables client ({@code
 * client/tableclient}) derives the {@code DatabaseApi} method names from the operationIds below
 * (controller method name plus the path version), and the Java client compiles against them.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(initializers = PropertyOverrideContextInitializer.class)
@WithMockUser(username = "testUser")
public class DatabasesOpenApiContractTest {

  private static final String DATABASES = "$.paths['/v1/databases']";
  private static final String DATABASE = "$.paths['/v1/databases/{databaseId}']";
  private static final String PROPERTIES = "$.paths['/v1/databases/{databaseId}/properties']";
  private static final String TABLE = "$.paths['/v1/databases/{databaseId}/tables/{tableId}']";

  @Autowired MockMvc mvc;

  @Test
  public void testDatabaseEntityOperations() throws Exception {
    mvc.perform(MockMvcRequestBuilders.get("/v3/api-docs").accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        // list is unchanged
        .andExpect(jsonPath(DATABASES + ".get.operationId", is(equalTo("getAllDatabasesV1"))))
        // create
        .andExpect(jsonPath(DATABASES + ".post.operationId", is(equalTo("createDatabaseV1"))))
        .andExpect(jsonPath(DATABASES + ".post.tags", contains("Database")))
        .andExpect(jsonPath(DATABASES + ".post.responses.201").exists())
        .andExpect(jsonPath(DATABASES + ".post.responses.400").exists())
        .andExpect(jsonPath(DATABASES + ".post.responses.403").exists())
        .andExpect(jsonPath(DATABASES + ".post.responses.409").exists())
        .andExpect(
            jsonPath(
                DATABASES + ".post.requestBody.content['application/json'].schema.$ref",
                is(equalTo("#/components/schemas/CreateDatabaseRequestBody"))))
        .andExpect(
            jsonPath(
                DATABASES + ".post.responses.201.content['application/json'].schema.$ref",
                is(equalTo("#/components/schemas/GetDatabaseResponseBody"))))
        // get
        .andExpect(jsonPath(DATABASE + ".get.operationId", is(equalTo("getDatabaseV1"))))
        .andExpect(jsonPath(DATABASE + ".get.tags", contains("Database")))
        .andExpect(jsonPath(DATABASE + ".get.responses.200").exists())
        .andExpect(jsonPath(DATABASE + ".get.responses.404").exists())
        .andExpect(
            jsonPath(
                DATABASE + ".get.responses.200.content['application/json'].schema.$ref",
                is(equalTo("#/components/schemas/GetDatabaseResponseBody"))))
        // delete
        .andExpect(jsonPath(DATABASE + ".delete.operationId", is(equalTo("deleteDatabaseV1"))))
        .andExpect(jsonPath(DATABASE + ".delete.responses.204").exists())
        .andExpect(jsonPath(DATABASE + ".delete.responses.404").exists())
        // update properties
        .andExpect(
            jsonPath(PROPERTIES + ".post.operationId", is(equalTo("updateDatabasePropertiesV1"))))
        .andExpect(jsonPath(PROPERTIES + ".post.responses.200").exists())
        .andExpect(jsonPath(PROPERTIES + ".post.responses.404").exists())
        .andExpect(jsonPath(PROPERTIES + ".post.responses.422").exists())
        .andExpect(
            jsonPath(
                PROPERTIES + ".post.requestBody.content['application/json'].schema.$ref",
                is(equalTo("#/components/schemas/UpdateDatabasePropertiesRequestBody"))))
        .andExpect(
            jsonPath(
                PROPERTIES + ".post.responses.200.content['application/json'].schema.$ref",
                is(equalTo("#/components/schemas/UpdateDatabasePropertiesResponseBody"))))
        // schemas
        .andExpect(
            jsonPath(
                "$.components.schemas.GetDatabaseResponseBody.properties.databaseUUID.readOnly",
                is(true)))
        .andExpect(
            jsonPath(
                "$.components.schemas.GetDatabaseResponseBody.properties.properties.readOnly",
                is(true)))
        .andExpect(
            jsonPath(
                "$.components.schemas.UpdateDatabasePropertiesResponseBody.required",
                containsInAnyOrder("updated", "removed")));
  }

  @Test
  public void testTablePathsAreUnchanged() throws Exception {
    mvc.perform(MockMvcRequestBuilders.get("/v3/api-docs").accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath(TABLE + ".get.operationId", is(equalTo("getTableV1"))))
        .andExpect(jsonPath(TABLE + ".put.operationId", is(equalTo("updateTableV1"))))
        .andExpect(jsonPath(TABLE + ".delete.operationId", is(equalTo("deleteTableV1"))))
        .andExpect(
            jsonPath(
                "$.paths['/v1/databases/{databaseId}/tables'].post.operationId",
                is(equalTo("createTableV1"))));
  }
}
