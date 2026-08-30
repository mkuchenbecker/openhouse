package com.linkedin.openhouse.tables.api.handler;

import org.apache.iceberg.rest.requests.CreateNamespaceRequest;
import org.apache.iceberg.rest.requests.UpdateNamespacePropertiesRequest;
import org.apache.iceberg.rest.responses.CreateNamespaceResponse;
import org.apache.iceberg.rest.responses.GetNamespaceResponse;
import org.apache.iceberg.rest.responses.ListNamespacesResponse;
import org.apache.iceberg.rest.responses.UpdateNamespacePropertiesResponse;

/**
 * Protocol adapter between the generated Iceberg REST namespace routes and OpenHouse's stored
 * namespaces.
 *
 * <p>This is the only place a {@link org.apache.iceberg.catalog.Namespace} is built from a wire
 * string; everything below it speaks the domain type, and the persisted encoding is applied lower
 * still, in the service.
 */
public interface IcebergRestNamespaceApiHandler {

  ListNamespacesResponse listNamespaces(
      String prefix, String parent, String pageToken, Integer pageSize);

  CreateNamespaceResponse createNamespace(String prefix, CreateNamespaceRequest request);

  GetNamespaceResponse loadNamespaceMetadata(String prefix, String namespace);

  /** Throws {@link org.apache.iceberg.exceptions.NoSuchNamespaceException} when it does not. */
  void namespaceExists(String prefix, String namespace);

  void dropNamespace(String prefix, String namespace);

  UpdateNamespacePropertiesResponse updateProperties(
      String prefix, String namespace, UpdateNamespacePropertiesRequest request);
}
