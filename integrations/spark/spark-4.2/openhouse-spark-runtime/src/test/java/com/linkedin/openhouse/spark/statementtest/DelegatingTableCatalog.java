package com.linkedin.openhouse.spark.statementtest;

import java.util.Map;
import org.apache.iceberg.spark.SparkCatalog;
import org.apache.spark.sql.catalyst.analysis.NoSuchNamespaceException;
import org.apache.spark.sql.catalyst.analysis.NoSuchTableException;
import org.apache.spark.sql.catalyst.analysis.TableAlreadyExistsException;
import org.apache.spark.sql.connector.catalog.Column;
import org.apache.spark.sql.connector.catalog.Identifier;
import org.apache.spark.sql.connector.catalog.Table;
import org.apache.spark.sql.connector.catalog.TableCatalog;
import org.apache.spark.sql.connector.catalog.TableChange;
import org.apache.spark.sql.connector.expressions.Transform;
import org.apache.spark.sql.util.CaseInsensitiveStringMap;

/**
 * A Spark {@link TableCatalog} that is not Iceberg's {@link SparkCatalog} but delegates every call
 * to one, built from the same options, so its {@code loadTable} returns Iceberg {@code
 * SparkTable}s. It stands for a deployment's wrapper catalog.
 */
public class DelegatingTableCatalog implements TableCatalog {
  private final SparkCatalog delegate = new SparkCatalog();
  private String name;

  @Override
  public void initialize(String name, CaseInsensitiveStringMap options) {
    this.name = name;
    delegate.initialize(name, options);
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public Identifier[] listTables(String[] namespace) throws NoSuchNamespaceException {
    return delegate.listTables(namespace);
  }

  @Override
  public Table loadTable(Identifier ident) throws NoSuchTableException {
    return delegate.loadTable(ident);
  }

  @Override
  public Table createTable(
      Identifier ident, Column[] columns, Transform[] partitions, Map<String, String> properties)
      throws TableAlreadyExistsException, NoSuchNamespaceException {
    return delegate.createTable(ident, columns, partitions, properties);
  }

  @Override
  public Table alterTable(Identifier ident, TableChange... changes) throws NoSuchTableException {
    return delegate.alterTable(ident, changes);
  }

  @Override
  public boolean dropTable(Identifier ident) {
    return delegate.dropTable(ident);
  }

  @Override
  public void renameTable(Identifier oldIdent, Identifier newIdent)
      throws NoSuchTableException, TableAlreadyExistsException {
    delegate.renameTable(oldIdent, newIdent);
  }
}
