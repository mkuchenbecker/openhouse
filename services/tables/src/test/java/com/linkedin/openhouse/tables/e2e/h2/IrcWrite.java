package com.linkedin.openhouse.tables.e2e.h2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.MetadataUpdate;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.SortOrder;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.UpdateRequirement;
import org.apache.iceberg.UpdateRequirements;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.hadoop.HadoopFileIO;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableList;
import org.apache.iceberg.rest.IrcClientParsers;
import org.apache.iceberg.rest.requests.CreateTableRequest;
import org.apache.iceberg.rest.requests.RenameTableRequest;
import org.apache.iceberg.rest.requests.UpdateTableRequest;
import org.apache.iceberg.rest.responses.LoadTableResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * IRC write requests built the way Iceberg 1.5's {@code RESTCatalog} builds them: request bodies
 * written with the client's own object mapper, requirements derived with Iceberg's own {@link
 * UpdateRequirements} from the base the client loaded, and the create and replace changes spelled
 * as {@code RESTSessionCatalog} spells them.
 */
final class IrcWrite {
  static final FileIO IO = new HadoopFileIO(new Configuration());

  private IrcWrite() {}

  static String tables(String db) {
    return "/v1/namespaces/" + db + "/tables";
  }

  static String table(String db, String table) {
    return tables(db) + "/" + table;
  }

  static MockHttpServletRequestBuilder post(String path, Object body) throws Exception {
    return IrcHttp.request(HttpMethod.POST, path)
        .contentType(MediaType.APPLICATION_JSON)
        .content(IrcClientParsers.toJson(body));
  }

  static MvcResult create(MockMvc mvc, String db, CreateTableRequest request) throws Exception {
    return IrcHttp.perform(mvc, post(tables(db), request));
  }

  static MvcResult commit(MockMvc mvc, String db, String table, UpdateTableRequest request)
      throws Exception {
    return IrcHttp.perform(mvc, post(table(db, table), request));
  }

  /**
   * An ordinary (SIMPLE) commit of {@code updated} on {@code base}, as RESTTableOperations sends.
   */
  static MvcResult commit(
      MockMvc mvc, String db, String table, TableMetadata base, TableMetadata updated)
      throws Exception {
    return commit(mvc, db, table, simple(base, updated.changes()));
  }

  static UpdateTableRequest simple(TableMetadata base, List<MetadataUpdate> changes) {
    return new UpdateTableRequest(UpdateRequirements.forUpdateTable(base, changes), changes);
  }

  static MvcResult drop(MockMvc mvc, String db, String table) throws Exception {
    return IrcHttp.perform(mvc, IrcHttp.request(HttpMethod.DELETE, table(db, table)));
  }

  static MvcResult rename(MockMvc mvc, TableIdentifier from, TableIdentifier to) throws Exception {
    return IrcHttp.perform(
        mvc,
        post(
            "/v1/tables/rename",
            RenameTableRequest.builder().withSource(from).withDestination(to).build()));
  }

  /** The table as the client sees it after a load. */
  static TableMetadata load(MockMvc mvc, String db, String table) throws Exception {
    MvcResult result = IrcHttp.get(mvc, table(db, table));
    assertEquals(200, result.getResponse().getStatus(), IrcHttp.body(result));
    return IrcClientParsers.parse(IrcHttp.body(result), LoadTableResponse.class).tableMetadata();
  }

  /** Parses a 200 load or commit response as the client does. */
  static TableMetadata parsed(MvcResult result) throws Exception {
    assertEquals(200, result.getResponse().getStatus(), IrcHttp.body(result));
    return IrcClientParsers.parse(IrcHttp.body(result), LoadTableResponse.class).tableMetadata();
  }

  /** One appended data file on top of {@code metadata}, written under its location. */
  static Snapshot append(TableMetadata metadata, String dataPath) throws Exception {
    return NativeCtas.table(metadata, IO)
        .newAppend()
        .appendFile(
            com.linkedin.openhouse.tables.model.IcebergSnapshotsModelTestUtilities
                .createDummyDataFile(dataPath, metadata.spec()))
        .apply();
  }

  /** {@code metadata} with {@code snapshot} added and made the head of main. */
  static TableMetadata withMain(TableMetadata metadata, Snapshot snapshot) {
    return TableMetadata.buildFrom(metadata)
        .addSnapshot(snapshot)
        .setBranchSnapshot(snapshot.snapshotId(), "main")
        .build();
  }

  /**
   * The replacement {@code RESTSessionCatalog.replaceTransaction} builds on {@code base} (main
   * reset, fresh ids reused by name, base location).
   */
  static TableMetadata replacement(
      TableMetadata base, Schema schema, PartitionSpec spec, Map<String, String> properties) {
    return base.buildReplacement(schema, spec, SortOrder.unsorted(), base.location(), properties);
  }

  /**
   * The replace commit RESTTableOperations sends for {@code replacement} plus the transaction's
   * later changes {@code then}: the replacement's changes, the set-current changes {@code
   * RESTSessionCatalog} adds when the replacement lacks them, and requirements for a replace.
   */
  static UpdateTableRequest replace(
      TableMetadata base, TableMetadata replacement, List<MetadataUpdate> then) {
    ImmutableList.Builder<MetadataUpdate> changes = ImmutableList.builder();
    if (replacement.changes().stream()
        .noneMatch(MetadataUpdate.SetCurrentSchema.class::isInstance)) {
      changes.add(new MetadataUpdate.SetCurrentSchema(replacement.currentSchemaId()));
    }
    if (replacement.changes().stream()
        .noneMatch(MetadataUpdate.SetDefaultPartitionSpec.class::isInstance)) {
      changes.add(new MetadataUpdate.SetDefaultPartitionSpec(replacement.defaultSpecId()));
    }
    if (replacement.changes().stream()
        .noneMatch(MetadataUpdate.SetDefaultSortOrder.class::isInstance)) {
      changes.add(new MetadataUpdate.SetDefaultSortOrder(replacement.defaultSortOrderId()));
    }
    changes.addAll(replacement.changes()).addAll(then);
    List<MetadataUpdate> updates = changes.build();
    return new UpdateTableRequest(UpdateRequirements.forReplaceTable(base, updates), updates);
  }

  /**
   * The create commit RESTTableOperations sends after a staged create returned {@code staged}:
   * {@code RESTSessionCatalog.createChanges(staged)} followed by the transaction's changes.
   */
  static UpdateTableRequest createCommit(TableMetadata staged, List<MetadataUpdate> then) {
    List<MetadataUpdate> updates = new ArrayList<>(createChanges(staged));
    updates.addAll(then);
    List<UpdateRequirement> requirements = UpdateRequirements.forCreateTable(updates);
    return new UpdateTableRequest(requirements, updates);
  }

  /** {@code RESTSessionCatalog.createChanges} (private there), line for line. */
  static List<MetadataUpdate> createChanges(TableMetadata meta) {
    ImmutableList.Builder<MetadataUpdate> changes = ImmutableList.builder();
    changes.add(new MetadataUpdate.AssignUUID(meta.uuid()));
    changes.add(new MetadataUpdate.UpgradeFormatVersion(meta.formatVersion()));
    Schema schema = meta.schema();
    changes.add(new MetadataUpdate.AddSchema(schema, schema.highestFieldId()));
    changes.add(new MetadataUpdate.SetCurrentSchema(-1));
    PartitionSpec spec = meta.spec();
    if (spec != null && spec.isPartitioned()) {
      changes.add(new MetadataUpdate.AddPartitionSpec(spec));
    } else {
      changes.add(new MetadataUpdate.AddPartitionSpec(PartitionSpec.unpartitioned()));
    }
    changes.add(new MetadataUpdate.SetDefaultPartitionSpec(-1));
    SortOrder order = meta.sortOrder();
    if (order != null && order.isSorted()) {
      changes.add(new MetadataUpdate.AddSortOrder(order));
    } else {
      changes.add(new MetadataUpdate.AddSortOrder(SortOrder.unsorted()));
    }
    changes.add(new MetadataUpdate.SetDefaultSortOrder(-1));
    String location = meta.location();
    if (location != null) {
      changes.add(new MetadataUpdate.SetLocation(location));
    }
    Map<String, String> properties = meta.properties();
    if (properties != null && !properties.isEmpty()) {
      changes.add(new MetadataUpdate.SetProperties(properties));
    }
    return changes.build();
  }
}
