package com.linkedin.openhouse.tables.controller;

import com.linkedin.openhouse.tables.api.handler.IcebergRestApiHandler;
import com.linkedin.openhouse.tables.api.handler.IcebergRestNamespaceApiHandler;
import com.linkedin.openhouse.tables.generated.iceberg.api.CatalogApiApi;
import com.linkedin.openhouse.tables.generated.iceberg.api.ConfigurationApiApi;
import com.linkedin.openhouse.tables.generated.iceberg.model.CatalogConfig;
import com.linkedin.openhouse.tables.generated.iceberg.model.ListTablesResponse;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.UUID;
import org.apache.iceberg.rest.requests.CreateNamespaceRequest;
import org.apache.iceberg.rest.requests.CreateTableRequest;
import org.apache.iceberg.rest.requests.UpdateNamespacePropertiesRequest;
import org.apache.iceberg.rest.requests.UpdateTableRequest;
import org.apache.iceberg.rest.responses.CreateNamespaceResponse;
import org.apache.iceberg.rest.responses.GetNamespaceResponse;
import org.apache.iceberg.rest.responses.ListNamespacesResponse;
import org.apache.iceberg.rest.responses.LoadTableResponse;
import org.apache.iceberg.rest.responses.UpdateNamespacePropertiesResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin Spring MVC adapter for the generated read-only Iceberg REST contract.
 *
 * <p>Protocol translation and orchestration live in {@link IcebergRestApiHandler}; existing
 * OpenHouse handlers and services remain the source of business behavior.
 */
@Hidden
@RestController
@ConditionalOnProperty(value = "cluster.tables.iceberg-rest.enabled", havingValue = "true")
public class IcebergRestCatalogController implements CatalogApiApi, ConfigurationApiApi {

  private final IcebergRestApiHandler icebergRestApiHandler;

  private final IcebergRestNamespaceApiHandler icebergRestNamespaceApiHandler;

  public IcebergRestCatalogController(
      IcebergRestApiHandler icebergRestApiHandler,
      IcebergRestNamespaceApiHandler icebergRestNamespaceApiHandler) {
    this.icebergRestApiHandler = icebergRestApiHandler;
    this.icebergRestNamespaceApiHandler = icebergRestNamespaceApiHandler;
  }

  @Override
  public ResponseEntity<CatalogConfig> getConfig(String warehouse) {
    return ResponseEntity.ok(icebergRestApiHandler.getConfig(warehouse));
  }

  @Override
  public ResponseEntity<ListTablesResponse> listTables(
      String prefix, String namespace, String pageToken, Integer pageSize) {
    return ResponseEntity.ok(
        icebergRestApiHandler.listTables(prefix, namespace, pageToken, pageSize));
  }

  @Override
  public ResponseEntity<LoadTableResponse> loadTable(
      String prefix,
      String namespace,
      String table,
      String xIcebergAccessDelegation,
      String ifNoneMatch,
      String snapshots,
      String referencedBy) {
    return ResponseEntity.ok(
        icebergRestApiHandler.loadTable(
            prefix,
            namespace,
            table,
            xIcebergAccessDelegation,
            ifNoneMatch,
            snapshots,
            referencedBy));
  }

  @Override
  public ResponseEntity<org.apache.iceberg.rest.responses.LoadTableResponse> createTable(
      String prefix,
      String namespace,
      CreateTableRequest createTableRequest,
      String xIcebergAccessDelegation,
      UUID idempotencyKey) {
    return ResponseEntity.ok(
        icebergRestApiHandler.createTable(
            prefix, namespace, createTableRequest, xIcebergAccessDelegation));
  }

  @Override
  public ResponseEntity<org.apache.iceberg.rest.responses.LoadTableResponse> updateTable(
      String prefix,
      String namespace,
      String table,
      UpdateTableRequest commitTableRequest,
      UUID idempotencyKey) {
    return ResponseEntity.ok(
        icebergRestApiHandler.updateTable(prefix, namespace, table, commitTableRequest));
  }

  @Override
  public ResponseEntity<Void> dropTable(
      String prefix, String namespace, String table, UUID idempotencyKey, Boolean purgeRequested) {
    icebergRestApiHandler.dropTable(prefix, namespace, table, purgeRequested);
    return ResponseEntity.noContent().build();
  }

  @Override
  public ResponseEntity<Void> tableExists(String prefix, String namespace, String table) {
    icebergRestApiHandler.tableExists(prefix, namespace, table);
    return ResponseEntity.noContent().build();
  }

  @Override
  public ResponseEntity<ListNamespacesResponse> listNamespaces(
      String prefix, String pageToken, Integer pageSize, String parent) {
    return ResponseEntity.ok(
        icebergRestNamespaceApiHandler.listNamespaces(prefix, parent, pageToken, pageSize));
  }

  @Override
  public ResponseEntity<CreateNamespaceResponse> createNamespace(
      String prefix, CreateNamespaceRequest createNamespaceRequest, UUID idempotencyKey) {
    return ResponseEntity.ok(
        icebergRestNamespaceApiHandler.createNamespace(prefix, createNamespaceRequest));
  }

  @Override
  public ResponseEntity<GetNamespaceResponse> loadNamespaceMetadata(
      String prefix, String namespace) {
    return ResponseEntity.ok(
        icebergRestNamespaceApiHandler.loadNamespaceMetadata(prefix, namespace));
  }

  @Override
  public ResponseEntity<Void> namespaceExists(String prefix, String namespace) {
    icebergRestNamespaceApiHandler.namespaceExists(prefix, namespace);
    return ResponseEntity.noContent().build();
  }

  @Override
  public ResponseEntity<Void> dropNamespace(String prefix, String namespace, UUID idempotencyKey) {
    icebergRestNamespaceApiHandler.dropNamespace(prefix, namespace);
    return ResponseEntity.noContent().build();
  }

  @Override
  public ResponseEntity<UpdateNamespacePropertiesResponse> updateProperties(
      String prefix,
      String namespace,
      UpdateNamespacePropertiesRequest updateNamespacePropertiesRequest,
      UUID idempotencyKey) {
    return ResponseEntity.ok(
        icebergRestNamespaceApiHandler.updateProperties(
            prefix, namespace, updateNamespacePropertiesRequest));
  }
}
