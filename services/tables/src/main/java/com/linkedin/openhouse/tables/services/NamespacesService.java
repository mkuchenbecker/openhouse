package com.linkedin.openhouse.tables.services;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.iceberg.catalog.Namespace;

/**
 * Service interface for namespaces, the resource behind {@code /v1/{prefix}/namespaces}.
 *
 * <p>The service speaks Iceberg {@link Namespace} values; the persisted encoding (dot-joined
 * levels) is applied below this seam and never leaks above it.
 *
 * <p>A namespace exists here if, and only if, the namespace store holds a row for it. It once also
 * existed if the table store held a table naming it; that second source covered for a store that
 * did not yet know about every database, and it is gone now that every write registers and the
 * backfill has caught up the rest. See {@link #ensureNamespace(String)} for the half of the
 * contract that keeps it true.
 */
public interface NamespacesService {

  /**
   * Create a namespace with an optional set of properties.
   *
   * @throws org.apache.iceberg.exceptions.AlreadyExistsException if it already exists
   * @throws org.apache.iceberg.exceptions.NoSuchNamespaceException if its parent does not exist
   */
  NamespaceMetadata createNamespace(
      Namespace namespace, Map<String, String> properties, String actingPrincipal);

  /**
   * Register {@code databaseId} in the namespace store if it is not there already, so that a
   * database created by writing a table into it is a namespace this API can see. Idempotent, and
   * unauthorized on its own: the caller has already been authorized to create the table.
   *
   * <p>Its failure is the table write's failure. Nothing derives a database's existence from its
   * tables any more, so a table whose database was never registered is a table in a database that
   * does not exist.
   *
   * @throws org.apache.iceberg.exceptions.NoSuchNamespaceException if {@code databaseId} names a
   *     nested namespace whose parent does not exist. Registering it would leave a child no listing
   *     walk can reach; the caller must create the parent first. Cannot arise at the shipped
   *     max-depth of 1, where every namespace is a root.
   */
  void ensureNamespace(String databaseId);

  /**
   * @throws org.apache.iceberg.exceptions.NoSuchNamespaceException if the namespace does not exist
   */
  NamespaceMetadata loadNamespaceMetadata(Namespace namespace, String actingPrincipal);

  boolean namespaceExists(Namespace namespace, String actingPrincipal);

  /**
   * List the immediate children of {@code parent}, each as a full namespace. An empty {@code
   * parent} lists the top-level namespaces, not a flattened tree.
   *
   * @throws org.apache.iceberg.exceptions.NoSuchNamespaceException if {@code parent} does not exist
   */
  List<Namespace> listNamespaces(Namespace parent, String actingPrincipal);

  /**
   * @throws org.apache.iceberg.exceptions.NoSuchNamespaceException if the namespace does not exist
   * @throws org.apache.iceberg.exceptions.NamespaceNotEmptyException if it still holds tables or
   *     child namespaces
   */
  void dropNamespace(Namespace namespace, String actingPrincipal);

  /**
   * @throws org.apache.iceberg.exceptions.NoSuchNamespaceException if the namespace does not exist
   * @throws org.apache.iceberg.exceptions.CommitFailedException if another writer changed the
   *     namespace concurrently
   */
  NamespacePropertiesUpdateResult updateProperties(
      Namespace namespace,
      Set<String> removals,
      Map<String, String> updates,
      String actingPrincipal);
}
