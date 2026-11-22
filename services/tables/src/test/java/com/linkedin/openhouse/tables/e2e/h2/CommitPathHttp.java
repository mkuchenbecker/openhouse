package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.common.api.validator.ValidatorConstants.INITIAL_TABLE_VERSION;
import static com.linkedin.openhouse.tables.e2e.h2.ValidationUtilities.CURRENT_MAJOR_VERSION_PREFIX;
import static com.linkedin.openhouse.tables.model.TableModelConstants.buildCreateUpdateTableRequestBody;
import static com.linkedin.openhouse.tables.model.TableModelConstants.buildGetTableResponseBodyWithDbTbl;

import com.google.gson.Gson;
import com.jayway.jsonpath.JsonPath;
import com.linkedin.openhouse.common.schema.IcebergSchemaHelper;
import com.linkedin.openhouse.tables.api.spec.v0.request.CreateUpdateTableRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.request.IcebergSnapshotsRequestBody;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.iceberg.Schema;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.Assertions;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Thin HTTP-only helpers shared by the commit-path black-box tests. Everything goes through {@link
 * MockMvc}; nothing here touches a service or a repository.
 */
final class CommitPathHttp {
  private static final String V1 = CURRENT_MAJOR_VERSION_PREFIX;

  private CommitPathHttp() {}

  static String tableUrl(String db, String table) {
    return String.format("%s/databases/%s/tables/%s", V1, db, table);
  }

  /** A create-ready request body (healthy schema, time partitioning, clustering, policies). */
  static CreateUpdateTableRequestBody newTable(String db, String table) {
    return buildCreateUpdateTableRequestBody(buildGetTableResponseBodyWithDbTbl(db, table))
        .toBuilder()
        .baseTableVersion(INITIAL_TABLE_VERSION)
        .build();
  }

  static ResultActions post(MockMvc mvc, CreateUpdateTableRequestBody body) throws Exception {
    return mvc.perform(
        MockMvcRequestBuilders.post(
                String.format("%s/databases/%s/tables/", V1, body.getDatabaseId()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body.toJson())
            .accept(MediaType.APPLICATION_JSON));
  }

  static ResultActions put(MockMvc mvc, CreateUpdateTableRequestBody body) throws Exception {
    return mvc.perform(
        MockMvcRequestBuilders.put(tableUrl(body.getDatabaseId(), body.getTableId()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body.toJson())
            .accept(MediaType.APPLICATION_JSON));
  }

  /** PUT with explicit path ids, for path/body mismatch cases. */
  static ResultActions put(
      MockMvc mvc, String pathDb, String pathTable, CreateUpdateTableRequestBody body)
      throws Exception {
    return mvc.perform(
        MockMvcRequestBuilders.put(tableUrl(pathDb, pathTable))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body.toJson())
            .accept(MediaType.APPLICATION_JSON));
  }

  static ResultActions putSnapshots(
      MockMvc mvc, String db, String table, IcebergSnapshotsRequestBody body) throws Exception {
    return mvc.perform(
        MockMvcRequestBuilders.put(tableUrl(db, table) + "/iceberg/v2/snapshots")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body.toJson())
            .accept(MediaType.APPLICATION_JSON));
  }

  static ResultActions get(MockMvc mvc, String db, String table) throws Exception {
    return mvc.perform(
        MockMvcRequestBuilders.get(tableUrl(db, table)).accept(MediaType.APPLICATION_JSON));
  }

  static ResultActions delete(MockMvc mvc, String db, String table) throws Exception {
    return mvc.perform(MockMvcRequestBuilders.delete(tableUrl(db, table)));
  }

  static ResultActions rename(
      MockMvc mvc, String fromDb, String fromTable, String toDb, String toTable) throws Exception {
    return mvc.perform(
        MockMvcRequestBuilders.patch(tableUrl(fromDb, fromTable) + "/rename")
            .param("toDatabaseId", toDb)
            .param("toTableId", toTable));
  }

  static ResultActions restore(MockMvc mvc, String db, String table, long deletedAtMs)
      throws Exception {
    return mvc.perform(
        MockMvcRequestBuilders.put(tableUrl(db, table) + "/restore")
            .param("deletedAtMs", Long.toString(deletedAtMs)));
  }

  static ResultActions purge(MockMvc mvc, String db, String table, long purgeAfterMs)
      throws Exception {
    return mvc.perform(
        MockMvcRequestBuilders.delete(tableUrl(db, table) + "/purge")
            .param("purgeAfterMs", Long.toString(purgeAfterMs)));
  }

  static ResultActions softDeleted(MockMvc mvc, String db) throws Exception {
    return mvc.perform(
        MockMvcRequestBuilders.get(String.format("%s/databases/%s/softDeletedTables", V1, db))
            .accept(MediaType.APPLICATION_JSON));
  }

  static ResultActions lock(MockMvc mvc, String db, String table) throws Exception {
    return mvc.perform(
        MockMvcRequestBuilders.post(tableUrl(db, table) + "/lock")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"locked\":true,\"message\":\"commit path test\"}")
            .accept(MediaType.APPLICATION_JSON));
  }

  static ResultActions unlock(MockMvc mvc, String db, String table) throws Exception {
    return mvc.perform(MockMvcRequestBuilders.delete(tableUrl(db, table) + "/lock"));
  }

  static String json(ResultActions actions) throws Exception {
    return actions.andReturn().getResponse().getContentAsString();
  }

  static int status(ResultActions actions) {
    return actions.andReturn().getResponse().getStatus();
  }

  static <T> T read(MvcResult result, String path) throws Exception {
    return JsonPath.read(result.getResponse().getContentAsString(), path);
  }

  static String str(String json, String path) {
    return JsonPath.read(json, path);
  }

  static <T> T read(String json, String path) {
    return JsonPath.read(json, path);
  }

  /** Message of an error body, or the raw body if it has none. */
  static String message(ResultActions actions) throws Exception {
    String body = json(actions);
    try {
      return JsonPath.read(body, "$.message");
    } catch (Exception e) {
      return body;
    }
  }

  /**
   * A write body rebuilt from a GET response, so a follow-up PUT is a faithful get-modify-put whose
   * base version is the current {@code tableLocation}.
   */
  static CreateUpdateTableRequestBody bodyFromGet(String getJson) {
    CreateUpdateTableRequestBody parsed =
        new Gson().fromJson(getJson, CreateUpdateTableRequestBody.class);
    return parsed
        .toBuilder()
        .baseTableVersion(JsonPath.read(getJson, "$.tableLocation"))
        .stageCreate(false)
        .build();
  }

  /** Schema JSON with {@code column} removed. */
  static String dropColumn(String schemaJson, String column) {
    Schema schema = IcebergSchemaHelper.getSchemaFromSchemaJson(schemaJson);
    return IcebergSchemaHelper.getSchemaJsonFromSchema(
        new Schema(
            schema.columns().stream()
                .filter(f -> !f.name().equals(column))
                .collect(Collectors.toList())));
  }

  /** Schema JSON with {@code column} given a new type, keeping its id and name. */
  static String retypeColumn(String schemaJson, String column, Type type) {
    Schema schema = IcebergSchemaHelper.getSchemaFromSchemaJson(schemaJson);
    return IcebergSchemaHelper.getSchemaJsonFromSchema(
        new Schema(
            schema.columns().stream()
                .map(
                    f ->
                        f.name().equals(column)
                            ? Types.NestedField.of(f.fieldId(), f.isOptional(), f.name(), type)
                            : f)
                .collect(Collectors.toList())));
  }

  /** Schema JSON where {@code from} is renamed to {@code to}, keeping its id and type. */
  static String renameColumn(String schemaJson, String from, String to) {
    Schema schema = IcebergSchemaHelper.getSchemaFromSchemaJson(schemaJson);
    return IcebergSchemaHelper.getSchemaJsonFromSchema(
        new Schema(
            schema.columns().stream()
                .map(
                    f ->
                        f.name().equals(from)
                            ? Types.NestedField.of(f.fieldId(), f.isOptional(), to, f.type())
                            : f)
                .collect(Collectors.toList())));
  }

  /** Schema JSON with one more optional column appended under a fresh id. */
  static String addColumn(String schemaJson, String column, Type type) {
    Schema schema = IcebergSchemaHelper.getSchemaFromSchemaJson(schemaJson);
    List<Types.NestedField> columns = new ArrayList<>(schema.columns());
    int nextId = columns.stream().mapToInt(Types.NestedField::fieldId).max().getAsInt() + 1;
    columns.add(Types.NestedField.optional(nextId, column, type));
    return IcebergSchemaHelper.getSchemaJsonFromSchema(new Schema(columns));
  }

  /** Asserts the HTTP status and that the error message contains the stable leading part. */
  static void expectError(ResultActions actions, int status, String messagePart) throws Exception {
    Assertions.assertEquals(status, status(actions), json(actions));
    String message = message(actions);
    Assertions.assertTrue(
        message.contains(messagePart),
        "expected message to contain [" + messagePart + "] but was [" + message + "]");
  }

  /** Asserts that a fresh GET returns exactly what an earlier GET returned. */
  static void expectUnchanged(MockMvc mvc, String before, String db, String table)
      throws Exception {
    Assertions.assertEquals(before, json(get(mvc, db, table)));
  }
}
