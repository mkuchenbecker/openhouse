package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.*;
import static com.linkedin.openhouse.tables.model.IcebergSnapshotsModelTestUtilities.createDummyDataFile;
import static com.linkedin.openhouse.tables.model.IcebergSnapshotsModelTestUtilities.getPartitionSpec;
import static com.linkedin.openhouse.tables.model.IcebergSnapshotsModelTestUtilities.obtainSnapshotRefsFromSnapshot;
import static com.linkedin.openhouse.tables.model.TableModelConstants.buildGetTableResponseBody;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.linkedin.openhouse.common.schema.IcebergSchemaHelper;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.internal.catalog.CatalogConstants;
import com.linkedin.openhouse.tables.api.spec.v0.request.CreateUpdateTableRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.request.IcebergSnapshotsRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.ClusteringColumn;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.History;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.Policies;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.PolicyTag;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.Retention;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.TimePartitionSpec;
import com.linkedin.openhouse.tables.api.spec.v0.response.GetTableResponseBody;
import com.linkedin.openhouse.tables.mock.properties.AuthorizationPropertiesInitializer;
import com.linkedin.openhouse.tablestest.HouseTablesH2Repository;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.SnapshotParser;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Rules of the write path that must hold identically for any implementation of it: what is
 * rejected, what is ignored, and what a replace carries over. Each case pins the HTTP status, the
 * stable leading part of the message and what a follow-up GET returns.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(
    initializers = {
      PropertyOverrideContextInitializer.class,
      AuthorizationPropertiesInitializer.class
    })
public class CommitPathInvariantsTest {

  @Autowired private HouseTablesH2Repository houseTablesH2Store;
  private static final String EVOLUTION_REJECTED =
      "Evolution of table partitioning and clustering columns are not supported";

  @Autowired MockMvc mvc;
  @Autowired Catalog catalog;

  private final List<String[]> created = new ArrayList<>();

  @AfterEach
  void cleanUp() throws Exception {
    for (String[] key : created) {
      delete(mvc, key[0], key[1]);
    }
    created.clear();
    houseTablesH2Store
        .softDeletedTables()
        .keySet()
        .removeIf(k -> k.getDatabaseId().startsWith("inv_"));
  }

  private CreateUpdateTableRequestBody base(String db, String t, boolean rtas) {
    CreateUpdateTableRequestBody b = newTable(db, t);
    Map<String, String> props = new HashMap<>(b.getTableProperties());
    if (rtas) {
      props.put(CatalogConstants.RTAS_ENABLED_TABLE_PROP, "true");
    }
    return b.toBuilder().tableProperties(props).build();
  }

  /** Creates the table and returns what a GET shows for it. */
  private String make(CreateUpdateTableRequestBody b) throws Exception {
    created.add(new String[] {b.getDatabaseId(), b.getTableId()});
    post(mvc, b).andExpect(status().isCreated());
    return json(get(mvc, b.getDatabaseId(), b.getTableId()));
  }

  /** A table with a second timestamp column and no policies, so partition rules are isolated. */
  private CreateUpdateTableRequestBody twoTimestamps(String db, String t) {
    CreateUpdateTableRequestBody b = base(db, t, false);
    return b.toBuilder()
        .schema(addColumn(b.getSchema(), "ts2", Types.TimestampType.withoutZone()))
        .policies(null)
        .build();
  }

  private static TimePartitionSpec partitionOn(
      String column, TimePartitionSpec.Granularity granularity) {
    return TimePartitionSpec.builder().columnName(column).granularity(granularity).build();
  }

  private static ClusteringColumn cluster(String column) {
    return ClusteringColumn.builder().columnName(column).build();
  }

  @Test
  void changingTimePartitioningOnPutIsRejectedAndTableUnchanged() throws Exception {
    String db = "inv_tp";
    String before = make(twoTimestamps(db, "t"));
    CreateUpdateTableRequestBody b = bodyFromGet(before);

    expectError(
        put(
            mvc,
            b.toBuilder()
                .timePartitioning(partitionOn("timestampCol", TimePartitionSpec.Granularity.DAY))
                .build()),
        400,
        EVOLUTION_REJECTED);
    expectUnchanged(mvc, before, db, "t");

    expectError(
        put(
            mvc,
            b.toBuilder()
                .timePartitioning(partitionOn("ts2", TimePartitionSpec.Granularity.HOUR))
                .build()),
        400,
        EVOLUTION_REJECTED);
    expectUnchanged(mvc, before, db, "t");

    expectError(put(mvc, b.toBuilder().timePartitioning(null).build()), 400, EVOLUTION_REJECTED);
    expectUnchanged(mvc, before, db, "t");
  }

  @Test
  void changingClusteringOnPutIsRejectedAndTableUnchanged() throws Exception {
    String db = "inv_cl";
    String before = make(twoTimestamps(db, "t"));
    CreateUpdateTableRequestBody b = bodyFromGet(before);

    for (List<ClusteringColumn> changed :
        Arrays.asList(
            Arrays.asList(cluster("name"), cluster("id")),
            Collections.singletonList(cluster("id")),
            Collections.<ClusteringColumn>emptyList(),
            (List<ClusteringColumn>) null)) {
      expectError(put(mvc, b.toBuilder().clustering(changed).build()), 400, EVOLUTION_REJECTED);
      expectUnchanged(mvc, before, db, "t");
    }
  }

  @Test
  void incompatibleSchemaChangesOnPutAreRejectedAndTableUnchanged() throws Exception {
    String db = "inv_schema";
    String before = make(base(db, "t", false));
    CreateUpdateTableRequestBody b = bodyFromGet(before);
    String invalidEvolution = "contains invalid schema evolution on top of current table schema";

    // double -> long is not a valid promotion
    expectError(
        put(
            mvc,
            b.toBuilder()
                .schema(retypeColumn(b.getSchema(), "stats", Types.LongType.get()))
                .build()),
        400,
        invalidEvolution);
    expectUnchanged(mvc, before, db, "t");

    // int -> string is not a valid promotion
    expectError(
        put(
            mvc,
            b.toBuilder()
                .schema(retypeColumn(b.getSchema(), "count", Types.StringType.get()))
                .build()),
        400,
        invalidEvolution);
    expectUnchanged(mvc, before, db, "t");

    // dropping a column is not an allowed evolution
    expectError(
        put(mvc, b.toBuilder().schema(dropColumn(b.getSchema(), "timeLong")).build()),
        400,
        invalidEvolution);
    expectUnchanged(mvc, before, db, "t");

    // dropping the time-partition source column
    expectError(
        put(mvc, b.toBuilder().schema(dropColumn(b.getSchema(), "timestampCol")).build()),
        400,
        "Adding partition spec failed:Cannot find source column: timestampCol");
    expectUnchanged(mvc, before, db, "t");
  }

  @Test
  void putThatOnlyRecasesAColumnNameIsAcceptedAndKeepsOriginalCasing() throws Exception {
    String db = "inv_recase";
    String before = make(base(db, "t", false));
    CreateUpdateTableRequestBody b = bodyFromGet(before);

    put(mvc, b.toBuilder().schema(renameColumn(b.getSchema(), "stats", "STATS")).build())
        .andExpect(status().isOk());

    String after = json(get(mvc, db, "t"));
    Schema schema = IcebergSchemaHelper.getSchemaFromSchemaJson(read(after, "$.schema"));
    assertEquals("stats", schema.findField(4).name());
    assertEquals(null, schema.findField("STATS"));
    // nothing was committed: same metadata file, same everything
    assertEquals(before, after);
  }

  @Test
  void stagedReplaceThatDropsAPartitionColumnIsRejectedAndTableUnchanged() throws Exception {
    String db = "inv_repl";
    String before = make(base(db, "t", true).toBuilder().policies(null).build());
    CreateUpdateTableRequestBody b = bodyFromGet(before);
    String dropsPartition = "RTAS does not support dropping a partition column";

    // time-partition source column
    expectError(
        post(
            mvc,
            b.toBuilder()
                .schema(dropColumn(b.getSchema(), "timestampCol"))
                .stageReplace(true)
                .build()),
        400,
        dropsPartition + " [timestampCol]");
    expectUnchanged(mvc, before, db, "t");

    // clustering column
    expectError(
        post(
            mvc,
            b.toBuilder().schema(dropColumn(b.getSchema(), "name")).stageReplace(true).build()),
        400,
        dropsPartition + " [name]");
    expectUnchanged(mvc, before, db, "t");

    // also rejected when the request drops the time partitioning itself
    expectError(
        post(
            mvc,
            b.toBuilder()
                .schema(dropColumn(b.getSchema(), "timestampCol"))
                .timePartitioning(null)
                .stageReplace(true)
                .build()),
        400,
        dropsPartition + " [timestampCol]");
    expectUnchanged(mvc, before, db, "t");

    // a column that is not a partition column can be dropped by a replace; the live table is not
    // touched by a staged replace.
    post(
            mvc,
            b.toBuilder().schema(dropColumn(b.getSchema(), "timeLong")).stageReplace(true).build())
        .andExpect(status().isCreated());
    expectUnchanged(mvc, before, db, "t");
  }

  /**
   * Sort order id 5 is the {@code count} column. Dropping it in a replace is a server error today,
   * not a client error; replacing with the sort order omitted or unsorted works.
   */
  @Test
  void stagedReplaceThatDropsASortOrderColumn() throws Exception {
    String db = "inv_sort";
    String sort =
        "{\"order-id\":1,\"fields\":[{\"transform\":\"identity\",\"source-id\":5,"
            + "\"direction\":\"asc\",\"null-order\":\"nulls-first\"}]}";
    String before = make(base(db, "t", true).toBuilder().policies(null).sortOrder(sort).build());
    assertEquals(sort, read(before, "$.sortOrder"));
    CreateUpdateTableRequestBody b = bodyFromGet(before);
    String noCount = dropColumn(b.getSchema(), "count");

    expectError(
        post(mvc, b.toBuilder().schema(noCount).stageReplace(true).build()),
        500,
        "Cannot find source column for sort field: identity(5) ASC NULLS FIRST");
    expectUnchanged(mvc, before, db, "t");

    post(mvc, b.toBuilder().schema(noCount).sortOrder(null).stageReplace(true).build())
        .andExpect(status().isCreated());
    expectUnchanged(mvc, before, db, "t");

    post(
            mvc,
            b.toBuilder()
                .schema(noCount)
                .sortOrder("{\"order-id\":0,\"fields\":[]}")
                .stageReplace(true)
                .build())
        .andExpect(status().isCreated());
    expectUnchanged(mvc, before, db, "t");
  }

  private CreateUpdateTableRequestBody withRichPolicies(String db, String t) {
    Map<String, PolicyTag> tags = new HashMap<>();
    tags.put("name", PolicyTag.builder().tags(Collections.singleton(PolicyTag.Tag.PII)).build());
    Policies policies =
        Policies.builder()
            .retention(
                Retention.builder().count(3).granularity(TimePartitionSpec.Granularity.DAY).build())
            .history(
                History.builder()
                    .maxAge(3)
                    .granularity(TimePartitionSpec.Granularity.DAY)
                    .versions(10)
                    .build())
            .columnTags(tags)
            .build();
    return base(db, t, true)
        .toBuilder()
        .timePartitioning(partitionOn("timestampCol", TimePartitionSpec.Granularity.DAY))
        .policies(policies)
        .build();
  }

  @Test
  void stagedReplaceWithPoliciesOmittedKeepsRetentionHistoryAndColumnTags() throws Exception {
    String db = "inv_pol_omit";
    String before = make(withRichPolicies(db, "t"));
    CreateUpdateTableRequestBody b = bodyFromGet(before);

    String replaced =
        json(
            post(mvc, b.toBuilder().policies(null).stageReplace(true).build())
                .andExpect(status().isCreated()));

    assertEquals(3, (int) read(replaced, "$.policies.retention.count"));
    assertEquals("DAY", read(replaced, "$.policies.retention.granularity"));
    assertEquals(3, (int) read(replaced, "$.policies.history.maxAge"));
    assertEquals(10, (int) read(replaced, "$.policies.history.versions"));
    List<String> tags = read(replaced, "$.policies.columnTags.name.tags");
    assertEquals(Collections.singletonList("PII"), tags);
    expectUnchanged(mvc, before, db, "t");

    // Committing the replace (snapshots PUT with replaceCommit, policies still omitted) makes it
    // visible: GET now shows the carried-over policies on the replaced table.
    MvcResult current = get(mvc, db, "t").andReturn();
    GetTableResponseBody currentBody = buildGetTableResponseBody(current);
    Table liveTable = catalog.loadTable(TableIdentifier.of(db, "t"));
    Snapshot snapshot =
        liveTable
            .newAppend()
            .appendFile(createDummyDataFile("/tmp/inv_pol_omit.orc", getPartitionSpec(currentBody)))
            .apply();
    String location = read(before, "$.tableLocation");
    IcebergSnapshotsRequestBody commit =
        IcebergSnapshotsRequestBody.builder()
            .baseTableVersion(location)
            .createUpdateTableRequestBody(
                b.toBuilder().policies(null).replaceCommit(true).baseTableVersion(location).build())
            .jsonSnapshots(Collections.singletonList(SnapshotParser.toJson(snapshot)))
            .snapshotRefs(obtainSnapshotRefsFromSnapshot(SnapshotParser.toJson(snapshot)))
            .build();
    putSnapshots(mvc, db, "t", commit).andExpect(status().isOk());

    String after = json(get(mvc, db, "t"));
    assertEquals(3, (int) read(after, "$.policies.retention.count"));
    assertEquals(3, (int) read(after, "$.policies.history.maxAge"));
    List<String> afterTags = read(after, "$.policies.columnTags.name.tags");
    assertEquals(Collections.singletonList("PII"), afterTags);
  }

  @Test
  void stagedReplaceWithPartialPoliciesChangesOnlyWhatWasSupplied() throws Exception {
    String db = "inv_pol_part";
    String before = make(withRichPolicies(db, "t"));
    CreateUpdateTableRequestBody b = bodyFromGet(before);

    // only retention supplied: history and column tags are carried over
    String retentionOnly =
        json(
            post(
                    mvc,
                    b.toBuilder()
                        .policies(
                            Policies.builder()
                                .retention(
                                    Retention.builder()
                                        .count(9)
                                        .granularity(TimePartitionSpec.Granularity.DAY)
                                        .build())
                                .build())
                        .stageReplace(true)
                        .build())
                .andExpect(status().isCreated()));
    assertEquals(9, (int) read(retentionOnly, "$.policies.retention.count"));
    assertEquals(3, (int) read(retentionOnly, "$.policies.history.maxAge"));
    assertEquals(10, (int) read(retentionOnly, "$.policies.history.versions"));
    List<String> tags = read(retentionOnly, "$.policies.columnTags.name.tags");
    assertEquals(Collections.singletonList("PII"), tags);

    // only history supplied: retention and column tags are carried over
    String historyOnly =
        json(
            post(
                    mvc,
                    b.toBuilder()
                        .policies(
                            Policies.builder()
                                .history(
                                    History.builder()
                                        .maxAge(2)
                                        .granularity(TimePartitionSpec.Granularity.DAY)
                                        .versions(20)
                                        .build())
                                .build())
                        .stageReplace(true)
                        .build())
                .andExpect(status().isCreated()));
    assertEquals(3, (int) read(historyOnly, "$.policies.retention.count"));
    assertEquals(2, (int) read(historyOnly, "$.policies.history.maxAge"));
    assertEquals(20, (int) read(historyOnly, "$.policies.history.versions"));
    tags = read(historyOnly, "$.policies.columnTags.name.tags");
    assertEquals(Collections.singletonList("PII"), tags);

    expectUnchanged(mvc, before, db, "t");
    assertTrue(json(get(mvc, db, "t")).contains("\"PII\""));
  }
}
