package com.linkedin.openhouse.tables.e2e.h2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.iceberg.EnvironmentContext;
import org.apache.iceberg.MetadataUpdate;
import org.apache.iceberg.Schema;
import org.apache.iceberg.UpdateRequirement;
import org.apache.iceberg.UpdateRequirements;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.IrcClientParsers;
import org.apache.iceberg.rest.requests.CreateViewRequest;
import org.apache.iceberg.rest.requests.ImmutableCreateViewRequest;
import org.apache.iceberg.rest.requests.RenameTableRequest;
import org.apache.iceberg.rest.requests.UpdateTableRequest;
import org.apache.iceberg.rest.responses.ListTablesResponse;
import org.apache.iceberg.rest.responses.LoadViewResponse;
import org.apache.iceberg.view.ImmutableSQLViewRepresentation;
import org.apache.iceberg.view.ImmutableViewVersion;
import org.apache.iceberg.view.ViewMetadata;
import org.apache.iceberg.view.ViewVersion;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * IRC view requests built the way Iceberg 1.5's {@code RESTCatalog} builds them ({@code
 * RESTSessionCatalog.RESTViewBuilder} for create and replace, {@code PropertiesUpdate} for property
 * changes, {@code RESTViewOperations} for commits): bodies written with the client's own object
 * mapper, requirements derived with {@link UpdateRequirements#forReplaceView}, responses parsed
 * with the client's own parsers.
 */
final class IrcViewWrite {
  private IrcViewWrite() {}

  static String views(String db) {
    return "/v1/namespaces/" + db + "/views";
  }

  static String view(String db, String view) {
    return views(db) + "/" + view;
  }

  /** The view version {@code RESTViewBuilder.create} sends. */
  static ViewVersion version(
      int versionId, Schema schema, String sql, String defaultCatalog, Namespace namespace) {
    return ImmutableViewVersion.builder()
        .versionId(versionId)
        .schemaId(schema.schemaId())
        .addRepresentations(
            ImmutableSQLViewRepresentation.builder().dialect("spark").sql(sql).build())
        .defaultNamespace(namespace)
        .defaultCatalog(defaultCatalog)
        .timestampMillis(System.currentTimeMillis())
        .putAllSummary(EnvironmentContext.get())
        .build();
  }

  static CreateViewRequest createRequest(
      String name, Schema schema, String sql, Namespace namespace, Map<String, String> properties) {
    return ImmutableCreateViewRequest.builder()
        .name(name)
        .schema(schema)
        .viewVersion(version(1, schema, sql, "openhouse", namespace))
        .properties(properties)
        .build();
  }

  static MvcResult create(MockMvc mvc, String db, CreateViewRequest request) throws Exception {
    return IrcHttp.perform(mvc, IrcWrite.post(views(db), request));
  }

  static MvcResult commit(MockMvc mvc, String db, String view, UpdateTableRequest request)
      throws Exception {
    return IrcHttp.perform(mvc, IrcWrite.post(view(db, view), request));
  }

  /** The commit {@code RESTViewOperations} sends for {@code updated} on {@code base}. */
  static UpdateTableRequest commitRequest(ViewMetadata base, ViewMetadata updated) {
    return UpdateTableRequest.create(
        null, UpdateRequirements.forReplaceView(base, updated.changes()), updated.changes());
  }

  static UpdateTableRequest commitRequest(
      List<UpdateRequirement> requirements, List<MetadataUpdate> updates) {
    return UpdateTableRequest.create(null, requirements, updates);
  }

  /** What {@code RESTViewBuilder.replace} builds on {@code base}. */
  static ViewMetadata replacement(
      ViewMetadata base, Schema schema, String sql, Map<String, String> properties) {
    int maxVersionId =
        base.versions().stream()
            .map(ViewVersion::versionId)
            .max(Integer::compareTo)
            .orElseGet(base::currentVersionId);
    return ViewMetadata.buildFrom(base)
        .setProperties(properties)
        .setCurrentVersion(
            version(
                maxVersionId + 1,
                schema,
                sql,
                base.currentVersion().defaultCatalog(),
                base.currentVersion().defaultNamespace()),
            schema)
        .build();
  }

  /** What {@code RESTViewBuilder.replace} builds on {@code base} with {@code withLocation}. */
  static ViewMetadata replacement(
      ViewMetadata base,
      Schema schema,
      String sql,
      Map<String, String> properties,
      String location) {
    int maxVersionId =
        base.versions().stream()
            .map(ViewVersion::versionId)
            .max(Integer::compareTo)
            .orElseGet(base::currentVersionId);
    return ViewMetadata.buildFrom(base)
        .setProperties(properties)
        .setCurrentVersion(
            version(
                maxVersionId + 1,
                schema,
                sql,
                base.currentVersion().defaultCatalog(),
                base.currentVersion().defaultNamespace()),
            schema)
        .setLocation(location)
        .build();
  }

  /** What {@code PropertiesUpdate} commits on {@code base}. */
  static ViewMetadata properties(ViewMetadata base, Map<String, String> set, Set<String> remove) {
    return ViewMetadata.buildFrom(base).setProperties(set).removeProperties(remove).build();
  }

  static ViewMetadata properties(ViewMetadata base, Map<String, String> set) {
    return properties(base, set, Collections.emptySet());
  }

  static MvcResult drop(MockMvc mvc, String db, String view) throws Exception {
    return IrcHttp.perform(mvc, IrcHttp.request(HttpMethod.DELETE, view(db, view)));
  }

  static MvcResult rename(MockMvc mvc, TableIdentifier from, TableIdentifier to) throws Exception {
    return IrcHttp.perform(
        mvc,
        IrcWrite.post(
            "/v1/views/rename",
            RenameTableRequest.builder().withSource(from).withDestination(to).build()));
  }

  /** The view as the client sees it after a load. */
  static ViewMetadata load(MockMvc mvc, String db, String view) throws Exception {
    return parsed(IrcHttp.get(mvc, view(db, view))).metadata();
  }

  /** Parses a 200 load, create or commit response as the client does. */
  static LoadViewResponse parsed(MvcResult result) throws Exception {
    assertEquals(200, result.getResponse().getStatus(), IrcHttp.body(result));
    return IrcClientParsers.parse(IrcHttp.body(result), LoadViewResponse.class);
  }

  static List<TableIdentifier> list(MockMvc mvc, String db) throws Exception {
    MvcResult result = IrcHttp.get(mvc, views(db));
    assertEquals(200, result.getResponse().getStatus(), IrcHttp.body(result));
    return IrcClientParsers.parse(IrcHttp.body(result), ListTablesResponse.class).identifiers();
  }
}
