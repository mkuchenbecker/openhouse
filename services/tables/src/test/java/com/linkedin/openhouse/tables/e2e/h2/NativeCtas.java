package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.common.api.validator.ValidatorConstants.INITIAL_TABLE_VERSION;
import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.bodyFromGet;
import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.post;
import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.putSnapshots;
import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.str;
import static com.linkedin.openhouse.tables.model.IcebergSnapshotsModelTestUtilities.createDummyDataFile;
import static com.linkedin.openhouse.tables.model.IcebergSnapshotsModelTestUtilities.obtainSnapshotRefsFromSnapshot;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.linkedin.openhouse.tables.api.spec.v0.request.CreateUpdateTableRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.request.IcebergSnapshotsRequestBody;
import java.util.Collections;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.BaseTable;
import org.apache.iceberg.LocationProviders;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.SnapshotParser;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableMetadataParser;
import org.apache.iceberg.TableOperations;
import org.apache.iceberg.hadoop.HadoopFileIO;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.io.LocationProvider;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * A create-with-data (CTAS) through the native API, sent the way the OpenHouse Java client sends
 * one: a staged create ({@code POST} with {@code stageCreate=true}), one append written under the
 * staged table's location, then a snapshots {@code PUT} on the initial version that carries the
 * staged table's metadata back.
 */
final class NativeCtas {
  private NativeCtas() {}

  /** The staged create's response body (the native staged table). */
  static String stage(MockMvc mvc, CreateUpdateTableRequestBody body) throws Exception {
    return post(mvc, body.toBuilder().stageCreate(true).build())
        .andExpect(status().isCreated())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  /**
   * Stages {@code body}, writes one data file at {@code dataPath}, and commits it as the create.
   */
  static ResultActions createWithData(
      MockMvc mvc, CreateUpdateTableRequestBody body, String dataPath) throws Exception {
    String staged = stage(mvc, body);
    FileIO io = new HadoopFileIO(new Configuration());
    TableMetadata metadata = TableMetadataParser.read(io, str(staged, "$.tableLocation"));
    Snapshot snapshot =
        new BaseTable(writable(metadata, io), "staged")
            .newAppend()
            .appendFile(createDummyDataFile(dataPath, metadata.spec()))
            .apply();
    String json = SnapshotParser.toJson(snapshot);
    CreateUpdateTableRequestBody inner =
        bodyFromGet(staged).toBuilder().baseTableVersion(INITIAL_TABLE_VERSION).build();
    return putSnapshots(
        mvc,
        body.getDatabaseId(),
        body.getTableId(),
        IcebergSnapshotsRequestBody.builder()
            .baseTableVersion(INITIAL_TABLE_VERSION)
            .jsonSnapshots(Collections.singletonList(json))
            .snapshotRefs(obtainSnapshotRefsFromSnapshot(json))
            .createUpdateTableRequestBody(inner)
            .build());
  }

  /** Operations over {@code metadata} that can write manifests next to it but never commit. */
  static TableOperations writable(TableMetadata metadata, FileIO io) {
    return new TableOperations() {
      @Override
      public TableMetadata current() {
        return metadata;
      }

      @Override
      public TableMetadata refresh() {
        return metadata;
      }

      @Override
      public void commit(TableMetadata base, TableMetadata updated) {
        throw new UnsupportedOperationException("writes files only");
      }

      @Override
      public FileIO io() {
        return io;
      }

      @Override
      public String metadataFileLocation(String fileName) {
        return metadata.location() + "/metadata/" + fileName;
      }

      @Override
      public LocationProvider locationProvider() {
        return LocationProviders.locationsFor(metadata.location(), metadata.properties());
      }
    };
  }

  /** A table over {@code metadata} for building snapshots (see {@link #writable}). */
  static Table table(TableMetadata metadata, FileIO io) {
    return new BaseTable(writable(metadata, io), "writable");
  }
}
