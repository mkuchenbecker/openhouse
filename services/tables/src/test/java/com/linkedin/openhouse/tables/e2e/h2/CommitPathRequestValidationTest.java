package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.tables.api.spec.v0.request.CreateUpdateTableRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.ClusteringColumn;
import com.linkedin.openhouse.tables.common.TableType;
import com.linkedin.openhouse.tables.mock.properties.AuthorizationPropertiesInitializer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

/**
 * What the create and update endpoints reject before anything is committed, and how: status, the
 * stable leading part of the message, and that nothing was created or changed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(
    initializers = {
      PropertyOverrideContextInitializer.class,
      AuthorizationPropertiesInitializer.class
    })
public class CommitPathRequestValidationTest {
  private static final String ID_CHARS = "Only alphanumerics and underscore supported";

  @Autowired MockMvc mvc;

  private final List<String[]> created = new ArrayList<>();

  @AfterEach
  void cleanUp() throws Exception {
    for (String[] key : created) {
      delete(mvc, key[0], key[1]);
    }
    created.clear();
  }

  /** Creates the table and returns what a GET shows for it. */
  private String make(CreateUpdateTableRequestBody b) throws Exception {
    created.add(new String[] {b.getDatabaseId(), b.getTableId()});
    post(mvc, b).andExpect(status().isCreated());
    return json(get(mvc, b.getDatabaseId(), b.getTableId()));
  }

  private static ClusteringColumn cluster(String column) {
    return ClusteringColumn.builder().columnName(column).build();
  }

  @Test
  void invalidDatabaseIdCharactersAreRejected() throws Exception {
    for (String bad : new String[] {"bad-db", "bad.db", "bad$db"}) {
      expectError(
          post(mvc, newTable("rv_ids", "t").toBuilder().databaseId(bad).build()),
          400,
          "CreateUpdateTableRequestBody.databaseId : " + ID_CHARS);
      expectError(get(mvc, bad, "t"), 400, "databaseId : provided " + bad + ", " + ID_CHARS);
    }
  }

  @Test
  void invalidTableIdCharactersAreRejectedAndNothingIsCreated() throws Exception {
    for (String bad : new String[] {"bad-t", "bad.t", "bad$t"}) {
      expectError(
          post(mvc, newTable("rv_ids", bad)),
          400,
          "CreateUpdateTableRequestBody.tableId : " + ID_CHARS);
      expectError(get(mvc, "rv_ids", bad), 400, "tableId : provided " + bad + ", " + ID_CHARS);
    }
  }

  @Test
  void idsLongerThan128CharactersAreRejected() throws Exception {
    String longId = String.join("", Collections.nCopies(129, "a"));
    expectError(
        post(mvc, newTable("rv_ids", longId)),
        400,
        "CreateUpdateTableRequestBody.tableId : size must be between 0 and 128");
    expectError(
        post(mvc, newTable(longId, "t")),
        400,
        "CreateUpdateTableRequestBody.databaseId : size must be between 0 and 128");
  }

  @Test
  void pathAndBodyIdsMustAgree() throws Exception {
    String before = make(newTable("rv_mm", "t1"));
    CreateUpdateTableRequestBody body = bodyFromGet(before);

    expectError(
        put(mvc, "rv_mm", "t1", body.toBuilder().tableId("t2").build()),
        400,
        "tableId : provided t1, doesn't match with the RequestBody t2");
    expectError(
        put(mvc, "rv_mm", "t3", body),
        400,
        "tableId : provided t3, doesn't match with the RequestBody t1");
    expectError(
        put(mvc, "rv_other", "t1", body),
        400,
        "databaseId : provided rv_other, doesn't match with the RequestBody rv_mm");
    expectError(
        post(mvc, "rv_other", newTable("rv_mm", "t9")),
        400,
        "databaseId : provided rv_other, doesn't match with the RequestBody rv_mm");
    expectError(
        post(mvc, newTable("rv_mm", "t8").toBuilder().clusterId("elsewhere").build()),
        400,
        "clusterId : provided elsewhere, doesn't match with the server cluster local-cluster");

    expectUnchanged(mvc, before, "rv_mm", "t1");
    for (String[] absent :
        new String[][] {
          {"rv_mm", "t2"}, {"rv_mm", "t3"}, {"rv_mm", "t8"}, {"rv_mm", "t9"}, {"rv_other", "t9"}
        }) {
      get(mvc, absent[0], absent[1]).andExpect(status().isNotFound());
    }
  }

  @Test
  void schemaThatIsDuplicatedEmptyOrUnparseableIsRejectedAndNothingIsCreated() throws Exception {
    CreateUpdateTableRequestBody base =
        newTable("rv_sch", "t").toBuilder().timePartitioning(null).clustering(null).build();
    String twoColumns =
        "{\"type\":\"struct\",\"fields\":[{\"id\":1,\"required\":true,\"name\":\"id\",\"type\":\"string\"},"
            + "{\"id\":2,\"required\":true,\"name\":\"ID\",\"type\":\"string\"}]}";

    // names that differ only by case
    expectError(
        post(mvc, base.toBuilder().schema(twoColumns).build()),
        400,
        "schema : duplicate column names found [ID] in top-level. Column names must be unique (case-insensitive).");
    // exact duplicate names are not caught by that validation
    expectError(
        post(mvc, base.toBuilder().schema(twoColumns.replace("\"ID\"", "\"id\"")).build()),
        500,
        "Invalid schema: multiple fields for name id: 1 and 2");
    // empty struct
    expectError(
        post(mvc, base.toBuilder().schema("{\"type\":\"struct\",\"fields\":[]}").build()),
        400,
        "schema : provided {\"type\":\"struct\",\"fields\":[]}, should contain at least one column");
    // not a schema at all
    expectError(
        post(mvc, base.toBuilder().schema("garbage").build()), 500, "java.io.UncheckedIOException");
    expectError(
        post(mvc, base.toBuilder().schema("").build()), 500, "java.io.UncheckedIOException");

    get(mvc, "rv_sch", "t").andExpect(status().isNotFound());
  }

  @Test
  void invalidSortOrdersAreRejectedAndNothingIsCreated() throws Exception {
    String badSource =
        "{\"order-id\":1,\"fields\":[{\"transform\":\"identity\",\"source-id\":99,\"direction\":\"asc\",\"null-order\":\"nulls-first\"}]}";
    String badDirection =
        "{\"order-id\":1,\"fields\":[{\"transform\":\"identity\",\"source-id\":1,\"direction\":\"sideways\",\"null-order\":\"nulls-first\"}]}";

    expectError(
        post(mvc, newTable("rv_sort", "s1").toBuilder().sortOrder("not json").build()),
        500,
        "java.io.UncheckedIOException");
    expectError(
        post(mvc, newTable("rv_sort", "s2").toBuilder().sortOrder("{}").build()),
        400,
        "sortOrder : provided {} is not a valid sort order");
    expectError(
        post(mvc, newTable("rv_sort", "s3").toBuilder().sortOrder(badSource).build()),
        500,
        "Cannot find source column for sort field: identity(99) ASC NULLS FIRST");
    expectError(
        post(mvc, newTable("rv_sort", "s4").toBuilder().sortOrder(badDirection).build()),
        400,
        "sortOrder : provided " + badDirection + " is not a valid sort order");

    for (String t : new String[] {"s1", "s2", "s3", "s4"}) {
      get(mvc, "rv_sort", t).andExpect(status().isNotFound());
    }
  }

  @Test
  void clusteringIsLimitedToFourColumnsAndMustNameRealColumns() throws Exception {
    CreateUpdateTableRequestBody base = newTable("rv_cl", "c4");
    String fiveColumns = addColumn(base.getSchema(), "c5", Types.StringType.get());
    List<ClusteringColumn> five =
        Arrays.asList(
            cluster("id"), cluster("name"), cluster("count"), cluster("timeLong"), cluster("c5"));

    expectError(
        post(mvc, base.toBuilder().tableId("c5").schema(fiveColumns).clustering(five).build()),
        400,
        "table rv_cl.c5 has 5 clustering columns specified, max clustering columns supported is 4");
    get(mvc, "rv_cl", "c5").andExpect(status().isNotFound());

    String before =
        make(base.toBuilder().schema(fiveColumns).clustering(five.subList(0, 4)).build());
    expectError(
        put(mvc, bodyFromGet(before).toBuilder().clustering(five).build()),
        400,
        "table rv_cl.c4 has 5 clustering columns specified, max clustering columns supported is 4");
    expectUnchanged(mvc, before, "rv_cl", "c4");

    expectError(
        post(
            mvc,
            newTable("rv_cl", "c6")
                .toBuilder()
                .clustering(Collections.singletonList(cluster("nope")))
                .build()),
        400,
        "Adding partition spec failed:Clustering column nope not found in the schema");
    expectError(
        post(
            mvc,
            newTable("rv_cl", "c7")
                .toBuilder()
                .clustering(Arrays.asList(cluster("id"), cluster("id")))
                .build()),
        400,
        "Adding partition spec failed:Cannot use partition name more than once: id");
    get(mvc, "rv_cl", "c6").andExpect(status().isNotFound());
    get(mvc, "rv_cl", "c7").andExpect(status().isNotFound());
  }

  @Test
  void replicaTableNeedsAValidUuid() throws Exception {
    String[][] cases = {
      {"not-a-uuid", "UUID: provided not-a-uuid is an invalid UUID string"},
      {"", "UUID: provided  is an invalid UUID string"},
      {
        null,
        "tableProperties should contain a valid openhouse.tableUUID property for tableType: REPLICA_TABLE"
      }
    };
    for (String[] c : cases) {
      Map<String, String> props = new HashMap<>(newTable("rv_rep", "t").getTableProperties());
      if (c[0] != null) {
        props.put("openhouse.tableUUID", c[0]);
      }
      expectError(
          post(
              mvc,
              newTable("rv_rep", "t")
                  .toBuilder()
                  .tableType(TableType.REPLICA_TABLE)
                  .tableProperties(props)
                  .build()),
          400,
          c[1]);
      get(mvc, "rv_rep", "t").andExpect(status().isNotFound());
    }
  }

  @Test
  void putWithStageCreateIsRejectedWhetherOrNotTheTableExists() throws Exception {
    String before = make(newTable("rv_sc", "t"));

    expectError(
        put(mvc, bodyFromGet(before).toBuilder().stageCreate(true).build()),
        400,
        "Staged table rv_sc.t cannot be created with PUT request, please use the POST apis");
    expectUnchanged(mvc, before, "rv_sc", "t");

    expectError(
        put(mvc, newTable("rv_sc", "n").toBuilder().stageCreate(true).build()),
        400,
        "Staged table rv_sc.n cannot be created with PUT request, please use the POST apis");
    get(mvc, "rv_sc", "n").andExpect(status().isNotFound());

    // the same request through POST is a staged create: accepted, and not visible afterwards
    post(mvc, newTable("rv_sc", "p").toBuilder().stageCreate(true).build())
        .andExpect(status().isCreated());
    get(mvc, "rv_sc", "p").andExpect(status().isNotFound());
  }
}
