package com.linkedin.openhouse.tables.services;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.iceberg.catalog.Namespace;

/**
 * Service interface for stored namespaces, the resource behind {@code /v1/{prefix}/namespaces}.
 *
 * <p>The service speaks Iceberg {@link Namespace} values; the persisted encoding (dot-joined
 * levels) is applied below this seam and never leaks above it.
 */
public interface NamespacesService {

  /**
   * Create a namespace with an optional set of properties.
   *
   * @throws com.linkedin.openhouse.common.exception.AlreadyExistsException if it already exists
   * @throws org.apache.iceberg.exceptions.NoSuchNamespaceException if its parent does not exist
   */
  Map<String, String> createNamespace(
      Namespace namespace, Map<String, String> properties, String actingPrincipal);

  /**
   * @throws org.apache.iceberg.exceptions.NoSuchNamespaceException if the namespace does not exist
   */
  Map<String, String> loadNamespaceMetadata(Namespace namespace, String actingPrincipal);

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
   */
  NamespacePropertiesUpdateResult updateProperties(
      Namespace namespace,
      Set<String> removals,
      Map<String, String> updates,
      String actingPrincipal);
}
