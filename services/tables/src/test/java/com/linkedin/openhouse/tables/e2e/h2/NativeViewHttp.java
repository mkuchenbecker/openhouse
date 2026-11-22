package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.ValidationUtilities.CURRENT_MAJOR_VERSION_PREFIX;

import com.jayway.jsonpath.JsonPath;
import com.linkedin.openhouse.housetables.client.model.ToggleStatus;
import com.linkedin.openhouse.tables.api.spec.v0.request.CreateUpdateViewRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.ViewRepresentation;
import com.linkedin.openhouse.tables.services.ViewsFeatureGate;
import com.linkedin.openhouse.tables.toggle.model.TableToggleStatus;
import com.linkedin.openhouse.tables.toggle.repository.ToggleStatusesRepository;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * The native view API over {@link MockMvc}, for tests that compare what an IRC view write stores
 * with what the native write stores. Requests are spelled as the OpenHouse Java client's {@code
 * OpenHouseViewOperations} spells them: a create is a POST without a base version, a replace is a
 * PUT of the complete desired definition with the base metadata location.
 */
final class NativeViewHttp {
  static final String V1 = CURRENT_MAJOR_VERSION_PREFIX;

  static final String SOURCE_DIALECT = "openhouse.view.sourceDialect";

  static final String SCHEMA_ID_NAME =
      "{\"type\":\"struct\",\"schema-id\":0,\"fields\":["
          + "{\"id\":1,\"name\":\"id\",\"required\":false,\"type\":\"long\"},"
          + "{\"id\":2,\"name\":\"name\",\"required\":false,\"type\":\"string\"}]}";

  static final String SCHEMA_ID_NAME_EXTRA =
      "{\"type\":\"struct\",\"schema-id\":0,\"fields\":["
          + "{\"id\":1,\"name\":\"id\",\"required\":false,\"type\":\"long\"},"
          + "{\"id\":2,\"name\":\"name\",\"required\":false,\"type\":\"string\"},"
          + "{\"id\":3,\"name\":\"extra\",\"required\":false,\"type\":\"int\"}]}";

  private NativeViewHttp() {}

  /** Opens the views feature gate for {@code db}, as a {@code views} toggle rule does. */
  static void enableViews(ToggleStatusesRepository toggles, String db) {
    toggles.save(
        TableToggleStatus.builder()
            .databaseId(db)
            .tableId(ViewsFeatureGate.GATE_PROBE_TABLE_ID)
            .featureId("views")
            .toggleStatusEnum(ToggleStatus.StatusEnum.ACTIVE)
            .build());
  }

  static String views(String db) {
    return V1 + "/databases/" + db + "/views";
  }

  static String view(String db, String view) {
    return views(db) + "/" + view;
  }

  static ViewRepresentation spark(String sql) {
    return ViewRepresentation.builder().type("sql").sql(sql).dialect("spark").build();
  }

  /** A create body as the Java client spells one for a single Spark representation. */
  static CreateUpdateViewRequestBody createBody(
      String db, String view, String schema, String sql, Map<String, String> userProperties) {
    Map<String, String> properties = new LinkedHashMap<>(userProperties);
    properties.put(SOURCE_DIALECT, "spark");
    return CreateUpdateViewRequestBody.builder()
        .databaseId(db)
        .viewId(view)
        .schema(schema)
        .representations(Collections.singletonList(spark(sql)))
        .defaultCatalog("openhouse")
        .defaultNamespace(Collections.singletonList(db))
        .viewProperties(properties)
        .build();
  }

  /** A replace body: the complete desired definition and properties on {@code base}. */
  static CreateUpdateViewRequestBody replaceBody(
      String db,
      String view,
      String base,
      String schema,
      List<ViewRepresentation> representations,
      Map<String, String> properties) {
    return CreateUpdateViewRequestBody.builder()
        .databaseId(db)
        .viewId(view)
        .schema(schema)
        .representations(representations)
        .defaultCatalog("openhouse")
        .defaultNamespace(Collections.singletonList(db))
        .viewProperties(properties)
        .baseMetadataLocation(base)
        .build();
  }

  static MvcResult post(MockMvc mvc, CreateUpdateViewRequestBody body) throws Exception {
    return mvc.perform(
            MockMvcRequestBuilders.post(views(body.getDatabaseId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.toJson())
                .accept(MediaType.APPLICATION_JSON))
        .andReturn();
  }

  static MvcResult put(MockMvc mvc, CreateUpdateViewRequestBody body) throws Exception {
    return mvc.perform(
            MockMvcRequestBuilders.put(view(body.getDatabaseId(), body.getViewId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.toJson())
                .accept(MediaType.APPLICATION_JSON))
        .andReturn();
  }

  static MvcResult get(MockMvc mvc, String db, String view) throws Exception {
    return mvc.perform(MockMvcRequestBuilders.get(view(db, view))).andReturn();
  }

  static MvcResult list(MockMvc mvc, String db) throws Exception {
    return mvc.perform(MockMvcRequestBuilders.get(views(db))).andReturn();
  }

  static MvcResult delete(MockMvc mvc, String db, String view) throws Exception {
    return mvc.perform(MockMvcRequestBuilders.delete(view(db, view))).andReturn();
  }

  /** The current metadata location the native GET reports. */
  static String pointer(MockMvc mvc, String db, String view) throws Exception {
    MvcResult result = get(mvc, db, view);
    if (result.getResponse().getStatus() != 200) {
      throw new AssertionError(
          "GET view " + db + "." + view + ": " + result.getResponse().getContentAsString());
    }
    return JsonPath.read(result.getResponse().getContentAsString(), "$.metadataLocation");
  }

  static int code(MvcResult result) {
    return result.getResponse().getStatus();
  }

  static String body(MvcResult result) throws Exception {
    return result.getResponse().getContentAsString();
  }
}
