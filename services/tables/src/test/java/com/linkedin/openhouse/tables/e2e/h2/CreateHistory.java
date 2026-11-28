package com.linkedin.openhouse.tables.e2e.h2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.HasTableOperations;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.SortOrder;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.Transaction;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.expressions.Expressions;
import org.apache.iceberg.inmemory.InMemoryCatalog;
import org.apache.iceberg.types.Types;

/**
 * Create transactions that change the table's schema, partition spec and sort order before they
 * commit, run the same way against any catalog, and the parts of the resulting metadata that say
 * what history the table kept: every schema, spec and sort order with its id, the current ones, the
 * last column and partition field ids, and for each snapshot its schema id and the spec id of each
 * file it added. Iceberg's own in-memory catalog is the reference.
 */
final class CreateHistory {
  private CreateHistory() {}

  /** As Iceberg's {@code CatalogTests}: column ids that a create assigns afresh (to 1 and 2). */
  static final Schema SCHEMA =
      new Schema(
          Types.NestedField.required(3, "id", Types.IntegerType.get()),
          Types.NestedField.required(4, "data", Types.StringType.get()));

  static final PartitionSpec SPEC = PartitionSpec.builderFor(SCHEMA).bucket("id", 16).build();

  static final SortOrder ORDER =
      SortOrder.builderFor(SCHEMA).asc(Expressions.bucket("id", 16)).asc("id").build();

  /** A transaction started on a catalog, before any change. */
  interface Start {
    Transaction start(Catalog catalog, TableIdentifier identifier);
  }

  static final Start CREATE =
      (catalog, identifier) ->
          catalog
              .buildTable(identifier, SCHEMA)
              .withPartitionSpec(SPEC)
              .withSortOrder(ORDER)
              .createTransaction();

  static final Start CREATE_OR_REPLACE =
      (catalog, identifier) ->
          catalog
              .buildTable(identifier, SCHEMA)
              .withPartitionSpec(SPEC)
              .withSortOrder(ORDER)
              .createOrReplaceTransaction();

  /** A data file of {@code table}'s current spec, in the table's directory. */
  static DataFile file(Table table, String name, String partitionPath) {
    return DataFiles.builder(table.spec())
        .withPath(table.location() + "/data/" + name + ".parquet")
        .withFileSizeInBytes(10)
        .withPartitionPath(partitionPath)
        .withRecordCount(2)
        .build();
  }

  /**
   * Iceberg's {@code testCompleteCreateTransactionMultipleSchemas}: an append, then a new column,
   * the spec and the sort order on it, then an append under the new spec, all in the transaction.
   */
  static Transaction evolveAll(Transaction create) {
    create.newFastAppend().appendFile(file(create.table(), "a", "id_bucket=0")).commit();
    create.updateSchema().addColumn("new_col", Types.LongType.get()).commit();
    create.updateSpec().addField("new_col").commit();
    create.replaceSortOrder().asc("new_col").commit();
    create.newFastAppend().appendFile(file(create.table(), "b", "id_bucket=0/new_col=0")).commit();
    return create;
  }

  /** A column dropped and another added: the final schema's ids are not the ones a create gives. */
  static Transaction dropAndAdd(Transaction create) {
    create.updateSchema().addColumn("extra", Types.StringType.get()).commit();
    create.updateSchema().deleteColumn("extra").addColumn("added", Types.LongType.get()).commit();
    create.newFastAppend().appendFile(file(create.table(), "a", "id_bucket=0")).commit();
    return create;
  }

  /** What a CTAS does: one append, nothing else. */
  static Transaction appendOnly(Transaction create) {
    create.newFastAppend().appendFile(file(create.table(), "a", "id_bucket=0")).commit();
    return create;
  }

  /** The files a scan of {@code table} plans, each with its spec id, sorted; or why it cannot. */
  static String planned(Table table) {
    List<String> files = new ArrayList<>();
    try (org.apache.iceberg.io.CloseableIterable<org.apache.iceberg.FileScanTask> tasks =
        table.newScan().planFiles()) {
      for (org.apache.iceberg.FileScanTask task : tasks) {
        String path = task.file().path().toString();
        files.add(
            path.substring(path.lastIndexOf('/') + 1)
                + "@spec-"
                + task.spec().specId()
                + " "
                + task.spec().fields());
      }
    } catch (Exception e) {
      return "unplannable: " + e;
    }
    Collections.sort(files);
    return String.join("\n", files);
  }

  /** A fresh reference catalog with {@code identifier}'s namespace. */
  static Catalog reference(TableIdentifier identifier) {
    InMemoryCatalog reference = new InMemoryCatalog();
    reference.initialize("reference", Collections.emptyMap());
    reference.createNamespace(identifier.namespace());
    return reference;
  }

  static TableMetadata metadata(Table table) {
    return ((HasTableOperations) table).operations().current();
  }

  /** The history parts of {@code table}'s metadata, one per line, to compare as text. */
  static String shape(Table table) {
    TableMetadata m = metadata(table);
    List<String> lines = new ArrayList<>();
    lines.add("format-version " + m.formatVersion());
    m.schemas().forEach(s -> lines.add("schema " + s.schemaId() + " " + s.asStruct()));
    lines.add("current-schema-id " + m.currentSchemaId());
    m.specs().forEach(s -> lines.add("spec " + s.specId() + " " + s.fields()));
    lines.add("default-spec-id " + m.defaultSpecId());
    m.sortOrders().forEach(o -> lines.add("sort-order " + o.orderId() + " " + o.fields()));
    lines.add("default-sort-order-id " + m.defaultSortOrderId());
    lines.add("last-column-id " + m.lastColumnId());
    lines.add("last-partition-id " + m.lastAssignedPartitionId());
    for (Snapshot snapshot : m.snapshots()) {
      StringBuilder line = new StringBuilder("snapshot schema-id " + snapshot.schemaId());
      try {
        for (DataFile added : snapshot.addedDataFiles(table.io())) {
          String path = added.path().toString();
          line.append(" ")
              .append(path.substring(path.lastIndexOf('/') + 1))
              .append("@spec-")
              .append(added.specId())
              .append(" ")
              .append(added.partition());
        }
      } catch (RuntimeException e) {
        line.append(" unreadable: ").append(e);
      }
      lines.add(line.toString());
    }
    return String.join("\n", lines);
  }
}
