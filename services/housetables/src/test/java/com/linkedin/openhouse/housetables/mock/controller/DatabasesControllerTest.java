package com.linkedin.openhouse.housetables.mock.controller;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.linkedin.openhouse.common.api.spec.ApiResponse;
import com.linkedin.openhouse.common.exception.AlreadyExistsException;
import com.linkedin.openhouse.common.exception.EntityConcurrentModificationException;
import com.linkedin.openhouse.common.exception.NoSuchEntityException;
import com.linkedin.openhouse.common.exception.UnprocessableEntityException;
import com.linkedin.openhouse.housetables.api.handler.DatabasesApiHandler;
import com.linkedin.openhouse.housetables.api.spec.model.Database;
import com.linkedin.openhouse.housetables.api.spec.model.DatabaseKey;
import com.linkedin.openhouse.housetables.api.spec.request.CreateUpdateEntityRequestBody;
import com.linkedin.openhouse.housetables.api.spec.request.UpdateDatabasePropertiesRequestBody;
import com.linkedin.openhouse.housetables.api.spec.response.EntityResponseBody;
import com.linkedin.openhouse.housetables.api.spec.response.UpdateDatabasePropertiesResponseBody;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Verifies request binding of {@code DatabasesController} against a mocked handler, and that the
 * typed exceptions the handler can throw are rendered by the shared exception handler with the
 * status codes the Iceberg namespace contract expects.
 */
@SpringBootTest
@AutoConfigureMockMvc
public class DatabasesControllerTest {

  @Autowired private MockMvc mvc;

  @MockBean private DatabasesApiHandler databasesApiHandler;

  private static final Database DB =
      Database.builder()
          .databaseUuid("uuid-1")
          .databaseId("mock_db")
          .creationTime(42L)
          .properties(Collections.singletonMap("a", "1"))
          .build();

  @Test
  public void testCreateBindsEntityAndReturnsHandlerStatus() throws Exception {
    when(databasesApiHandler.createEntity(any()))
        .thenReturn(
            ApiResponse.<EntityResponseBody<Database>>builder()
                .httpStatus(HttpStatus.CREATED)
                .responseBody(EntityResponseBody.<Database>builder().entity(DB).build())
                .build());
    Map<String, String> properties = new HashMap<>();
    properties.put("a", "1");
    mvc.perform(
            MockMvcRequestBuilders.post("/hts/databases")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    CreateUpdateEntityRequestBody.<Database>builder()
                        .entity(
                            Database.builder().databaseId("mock_db").properties(properties).build())
                        .build()
                        .toJson())
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isCreated())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.entity.databaseUuid", is(equalTo("uuid-1"))))
        .andExpect(jsonPath("$.entity.databaseId", is(equalTo("mock_db"))))
        .andExpect(jsonPath("$.entity.creationTime", is(equalTo(42))))
        .andExpect(jsonPath("$.entity.properties.a", is(equalTo("1"))));

    ArgumentCaptor<Database> captor = ArgumentCaptor.forClass(Database.class);
    Mockito.verify(databasesApiHandler).createEntity(captor.capture());
    org.junit.jupiter.api.Assertions.assertEquals("mock_db", captor.getValue().getDatabaseId());
    org.junit.jupiter.api.Assertions.assertEquals(properties, captor.getValue().getProperties());
  }

  @Test
  public void testGetBindsQueryParam() throws Exception {
    when(databasesApiHandler.getEntity(DatabaseKey.builder().databaseId("mock_db").build()))
        .thenReturn(
            ApiResponse.<EntityResponseBody<Database>>builder()
                .httpStatus(HttpStatus.OK)
                .responseBody(EntityResponseBody.<Database>builder().entity(DB).build())
                .build());
    mvc.perform(
            MockMvcRequestBuilders.get("/hts/databases")
                .param("databaseId", "mock_db")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.entity.databaseId", is(equalTo("mock_db"))));

    // Missing the required query parameter is a client error before the handler is reached.
    mvc.perform(MockMvcRequestBuilders.get("/hts/databases").accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest());
  }

  @Test
  public void testDeleteReturnsNoContent() throws Exception {
    when(databasesApiHandler.deleteEntity(DatabaseKey.builder().databaseId("mock_db").build()))
        .thenReturn(ApiResponse.<Void>builder().httpStatus(HttpStatus.NO_CONTENT).build());
    mvc.perform(MockMvcRequestBuilders.delete("/hts/databases").param("databaseId", "mock_db"))
        .andExpect(status().isNoContent())
        .andExpect(content().string(""));
  }

  @Test
  public void testUpdatePropertiesBindsKeyAndBody() throws Exception {
    when(databasesApiHandler.updateProperties(
            eq(DatabaseKey.builder().databaseId("mock_db").build()), any()))
        .thenReturn(
            ApiResponse.<UpdateDatabasePropertiesResponseBody>builder()
                .httpStatus(HttpStatus.OK)
                .responseBody(
                    UpdateDatabasePropertiesResponseBody.builder()
                        .updated(Collections.singletonList("a"))
                        .removed(Collections.singletonList("b"))
                        .missing(Collections.singletonList("c"))
                        .build())
                .build());
    Map<String, String> updates = new HashMap<>();
    updates.put("a", "9");
    mvc.perform(
            MockMvcRequestBuilders.patch("/hts/databases/properties")
                .param("databaseId", "mock_db")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    UpdateDatabasePropertiesRequestBody.builder()
                        .updates(updates)
                        .removals(Collections.singletonList("b"))
                        .build()
                        .toJson())
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.updated", contains("a")))
        .andExpect(jsonPath("$.removed", contains("b")))
        .andExpect(jsonPath("$.missing", contains("c")));

    ArgumentCaptor<UpdateDatabasePropertiesRequestBody> captor =
        ArgumentCaptor.forClass(UpdateDatabasePropertiesRequestBody.class);
    Mockito.verify(databasesApiHandler).updateProperties(any(), captor.capture());
    org.junit.jupiter.api.Assertions.assertEquals(updates, captor.getValue().getUpdates());
    org.junit.jupiter.api.Assertions.assertEquals(
        Collections.singletonList("b"), captor.getValue().getRemovals());
  }

  @Test
  public void testTypedExceptionsMapToIcebergStatusCodes() throws Exception {
    doThrow(new NoSuchEntityException("Database", "mock_db"))
        .when(databasesApiHandler)
        .getEntity(any());
    mvc.perform(MockMvcRequestBuilders.get("/hts/databases").param("databaseId", "mock_db"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status", is(equalTo("NOT_FOUND"))))
        .andExpect(jsonPath("$.message", is(equalTo("Database mock_db cannot be found"))));

    doThrow(new AlreadyExistsException("Database", "mock_db"))
        .when(databasesApiHandler)
        .createEntity(any());
    mvc.perform(
            MockMvcRequestBuilders.post("/hts/databases")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"entity\":{\"databaseId\":\"mock_db\"}}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message", is(equalTo("Database mock_db already exists"))));

    doThrow(new UnprocessableEntityException("key in both sets"))
        .when(databasesApiHandler)
        .updateProperties(any(), any());
    mvc.perform(
            MockMvcRequestBuilders.patch("/hts/databases/properties")
                .param("databaseId", "mock_db")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"updates\":{\"a\":\"1\"},\"removals\":[\"a\"]}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.status", is(equalTo("UNPROCESSABLE_ENTITY"))));

    doThrow(
            new EntityConcurrentModificationException(
                "mock_db", "modified", new RuntimeException("stale version")))
        .when(databasesApiHandler)
        .updateProperties(any(), any());
    mvc.perform(
            MockMvcRequestBuilders.patch("/hts/databases/properties")
                .param("databaseId", "mock_db")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"updates\":{\"a\":\"1\"}}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status", is(equalTo("CONFLICT"))));
  }
}
