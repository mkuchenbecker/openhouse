package com.linkedin.openhouse.tables.controller;

import static com.linkedin.openhouse.common.security.AuthenticationUtils.extractAuthenticatedUserPrincipal;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.linkedin.openhouse.cluster.configs.ClusterProperties;
import com.linkedin.openhouse.common.api.validator.ValidatorConstants;
import com.linkedin.openhouse.common.exception.EntityConcurrentModificationException;
import com.linkedin.openhouse.common.exception.InvalidSchemaEvolutionException;
import com.linkedin.openhouse.common.exception.RequestValidationFailureException;
import com.linkedin.openhouse.common.exception.UnsupportedClientOperationException;
import com.linkedin.openhouse.tables.api.handler.IcebergSnapshotsApiHandler;
import com.linkedin.openhouse.tables.api.handler.TablesApiHandler;
import com.linkedin.openhouse.tables.api.spec.v0.request.CreateUpdateTableRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.request.IcebergSnapshotsRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.ClusteringColumn;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.Policies;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.TimePartitionSpec;
import com.linkedin.openhouse.tables.dto.mapper.iceberg.PartitionSpecMapper;
import com.linkedin.openhouse.tables.dto.mapper.iceberg.PoliciesSpecMapper;
import com.linkedin.openhouse.tables.model.DatabaseDto;
import com.linkedin.openhouse.tables.repository.SchemaValidator;
import com.linkedin.openhouse.tables.services.DatabasesService;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.iceberg.BaseTable;
import org.apache.iceberg.MetadataUpdate;
import org.apache.iceberg.PartitionField;
import org.apache.iceberg.SchemaParser;
import org.apache.iceberg.SnapshotParser;
import org.apache.iceberg.SnapshotRefParser;
import org.apache.iceberg.SortOrderParser;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.UpdateRequirement;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.exceptions.BadRequestException;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.exceptions.CommitStateUnknownException;
import org.apache.iceberg.exceptions.ForbiddenException;
import org.apache.iceberg.exceptions.NoSuchNamespaceException;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.exceptions.NotAuthorizedException;
import org.apache.iceberg.exceptions.RESTException;
import org.apache.iceberg.exceptions.ValidationException;
import org.apache.iceberg.rest.CatalogHandlers;
import org.apache.iceberg.rest.RESTSerializers;
import org.apache.iceberg.rest.RESTUtil;
import org.apache.iceberg.rest.requests.CreateTableRequest;
import org.apache.iceberg.rest.requests.RenameTableRequest;
import org.apache.iceberg.rest.requests.UpdateTableRequest;
import org.apache.iceberg.rest.responses.ConfigResponse;
import org.apache.iceberg.rest.responses.CreateNamespaceResponse;
import org.apache.iceberg.rest.responses.ErrorResponse;
import org.apache.iceberg.rest.responses.GetNamespaceResponse;
import org.apache.iceberg.rest.responses.ListNamespacesResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Server-side implementation of the <a
 * href="https://github.com/apache/iceberg/blob/main/open-api/rest-catalog-open-api.yaml">Iceberg
 * REST Catalog spec</a>, mounted under {@code /iceberg} so that a stock {@code
 * org.apache.iceberg.rest.RESTCatalog} (Spark 4.0) can drive OpenHouse with no custom client jar.
 *
 * <p>TABLE operations delegate to Iceberg's server driver {@link CatalogHandlers}, which routes
 * through the injected {@link Catalog} (the {@code OpenHouseInternalCatalog} bean) and its {@code
 * TableOperations} -- so the OpenHouse reserved-key interception (snapshot smuggling, {@code
 * COMMIT_KEY}) in {@code OpenHouseInternalTableOperations.commit(...)} still applies.
 *
 * <p>NAMESPACE operations are implemented directly here because {@code OpenHouseInternalCatalog}
 * does NOT implement {@link org.apache.iceberg.catalog.SupportsNamespaces} -- OpenHouse databases
 * are implicit (a database "exists" iff it has at least one table). See {@code
 * docs/spark4-iceberg-upgrade/rest-endpoint} for the full design, decisions, and pitfalls.
 *
 * <p>Serialization is done manually with an {@link ObjectMapper} configured exactly like Iceberg's
 * internal {@code RESTObjectMapper} (kebab-case, field visibility, {@link RESTSerializers}). We
 * read the raw request body as a String and write the raw response as a String to avoid depending
 * on the Spring MVC {@code ObjectMapper} having Iceberg's REST (de)serializers registered.
 *
 * <p>Errors are mapped to the Iceberg {@link ErrorResponse} envelope by local {@link
 * ExceptionHandler} methods, which take precedence over the global {@code
 * OpenHouseExceptionHandler @ControllerAdvice} for exceptions thrown by this controller.
 */
@Slf4j
@RestController
@RequestMapping(path = "/iceberg/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class IcebergRestCatalogController {

  @Autowired private Catalog catalog;

  @Autowired private DatabasesService databasesService;

  /**
   * The same service bean the native {@code TablesController} PUT create path uses. Reusing it
   * means REST-driven creates go through OpenHouse's own create pipeline (location allocation via
   * {@code StorageSelector}, reserved {@code openhouse.*} property population, policy management,
   * and eligibility checks) instead of a parallel path.
   */
  @Autowired private TablesApiHandler tablesApiHandler;

  /**
   * The snapshot service bean the native {@code IcebergSnapshotsController} PUT-snapshots path
   * uses. Reusing it means REST-driven RTAS (REPLACE TABLE AS SELECT) commits go through
   * OpenHouse's own replace pipeline ({@code OpenHouseInternalRepositoryImpl.save} replace branch:
   * RTAS-enabled gating via {@code validateReplaceTable}, policy re-management, reserved-prop
   * recomputation, {@code isReplaceCommit} semantics) instead of a parallel path.
   */
  @Autowired private IcebergSnapshotsApiHandler icebergSnapshotsApiHandler;

  /**
   * Used to build the OpenHouse {@code TimePartitionSpec}/{@code ClusteringColumn}s from a spec.
   */
  @Autowired private PartitionSpecMapper partitionSpecMapper;

  /** Used to parse the serialized {@code policies} table property on the RTAS replace path. */
  @Autowired private PoliciesSpecMapper policiesSpecMapper;

  /**
   * The same {@code SchemaValidator} bean {@code OpenHouseInternalRepositoryImpl} uses on the
   * native update path, so a REST plain-update commit that evolves the schema is held to the
   * identical rules (no column drops, no incompatible narrowing, no nested-field drops). Reused
   * rather than reimplemented so the two paths can never diverge.
   */
  @Autowired private SchemaValidator schemaValidator;

  /** Supplies the server cluster name that the create request body must carry and match. */
  @Autowired private ClusterProperties clusterProperties;

  /** Configured identically to Iceberg's package-private {@code RESTObjectMapper}. */
  private static final ObjectMapper MAPPER = newRestObjectMapper();

  private static ObjectMapper newRestObjectMapper() {
    ObjectMapper mapper = new ObjectMapper();
    mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    mapper.setPropertyNamingStrategy(new PropertyNamingStrategies.KebabCaseStrategy());
    RESTSerializers.registerAll(mapper);
    return mapper;
  }

  // ---------------------------------------------------------------------------
  // Config
  // ---------------------------------------------------------------------------

  /**
   * Called by RESTCatalog on init. We return empty defaults/overrides and no prefix, so the client
   * uses the spec paths directly under this controller's base path.
   */
  @GetMapping("/config")
  public ResponseEntity<String> getConfig(
      @RequestParam(value = "warehouse", required = false) String warehouse) {
    return json(HttpStatus.OK, ConfigResponse.builder().build());
  }

  // ---------------------------------------------------------------------------
  // Namespaces (implemented directly -- no SupportsNamespaces on the catalog)
  // ---------------------------------------------------------------------------

  /**
   * Lists namespaces. OpenHouse databases are implicit, so this returns the distinct set of
   * databases that currently have at least one table (derived from the databases service). The
   * spec's {@code parent} query param (hierarchical listing) is not an OpenHouse concept and is
   * ignored -- a non-empty parent yields an empty list.
   */
  @GetMapping("/namespaces")
  public ResponseEntity<String> listNamespaces(
      @RequestParam(value = "parent", required = false) String parent) {
    if (parent != null && !parent.isEmpty()) {
      // OpenHouse namespaces are single-level; there are no child namespaces.
      return json(HttpStatus.OK, ListNamespacesResponse.builder().build());
    }
    Set<Namespace> namespaces =
        databasesService.getAllDatabases().stream()
            .map(DatabaseDto::getDatabaseId)
            .map(Namespace::of)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    return json(HttpStatus.OK, ListNamespacesResponse.builder().addAll(namespaces).build());
  }

  /**
   * Create namespace. OpenHouse databases are created implicitly on first table creation, so this
   * is a no-op that echoes the requested namespace with empty properties (properties are NOT
   * persisted; OpenHouse has no namespace property store).
   */
  @PostMapping("/namespaces")
  public ResponseEntity<String> createNamespace(@RequestBody(required = false) String body) {
    // We only need the namespace back; parse minimally and validate it is single-level.
    org.apache.iceberg.rest.requests.CreateNamespaceRequest request =
        readRequest(body, org.apache.iceberg.rest.requests.CreateNamespaceRequest.class);
    Namespace namespace = request.namespace();
    validateSingleLevel(namespace);
    return json(
        HttpStatus.OK,
        CreateNamespaceResponse.builder()
            .withNamespace(namespace)
            .setProperties(java.util.Collections.emptyMap())
            .build());
  }

  /**
   * Load namespace metadata. OpenHouse has no namespace property store, so this returns empty
   * properties for any valid single-level namespace (optimistic existence -- see class doc and the
   * pitfalls note).
   */
  @GetMapping("/namespaces/{namespace}")
  public ResponseEntity<String> loadNamespaceMetadata(@PathVariable("namespace") String namespace) {
    Namespace ns = RESTUtil.decodeNamespace(namespace);
    validateSingleLevel(ns);
    return json(
        HttpStatus.OK,
        GetNamespaceResponse.builder()
            .withNamespace(ns)
            .setProperties(java.util.Collections.emptyMap())
            .build());
  }

  /**
   * Namespace existence check. Returns 204 for any valid single-level namespace (optimistic -- see
   * class doc); 404 with no body only for malformed (multi-level) namespaces.
   */
  @RequestMapping(path = "/namespaces/{namespace}", method = RequestMethod.HEAD)
  public ResponseEntity<Void> namespaceExists(@PathVariable("namespace") String namespace) {
    Namespace ns = RESTUtil.decodeNamespace(namespace);
    if (ns.levels().length != 1) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.noContent().build();
  }

  /**
   * Drop namespace. Not supported: OpenHouse databases have no independent lifecycle (they
   * disappear when the last table is dropped), so there is nothing to drop. Returns 501.
   */
  @DeleteMapping("/namespaces/{namespace}")
  public ResponseEntity<String> dropNamespace(@PathVariable("namespace") String namespace) {
    Namespace ns = RESTUtil.decodeNamespace(namespace);
    return json(
        HttpStatus.NOT_IMPLEMENTED,
        errorBody(
            HttpStatus.NOT_IMPLEMENTED,
            "UnsupportedOperationException",
            "Dropping a namespace is not supported by OpenHouse; databases are implicit and are "
                + "removed when their last table is dropped. Namespace: "
                + ns));
  }

  // ---------------------------------------------------------------------------
  // Tables (delegate to CatalogHandlers -> Catalog -> TableOperations)
  // ---------------------------------------------------------------------------

  @GetMapping("/namespaces/{namespace}/tables")
  public ResponseEntity<String> listTables(@PathVariable("namespace") String namespace) {
    Namespace ns = RESTUtil.decodeNamespace(namespace);
    return json(HttpStatus.OK, CatalogHandlers.listTables(catalog, ns));
  }

  /**
   * Creates a brand-new OpenHouse table from a stock {@code RESTCatalog} {@code
   * CreateTableRequest}.
   *
   * <p>Rather than delegating to {@code CatalogHandlers.createTable} (which calls {@code
   * catalog.buildTable(...).create()} and fails because {@code
   * OpenHouseInternalCatalog.defaultWarehouseLocation} throws and no {@code openhouse.*} reserved
   * state is populated), this translates the Iceberg request into an OpenHouse {@link
   * CreateUpdateTableRequestBody} and calls the SAME {@link TablesApiHandler#createTable} bean the
   * native controller uses. The service/repository layer then allocates the table location,
   * computes the reserved properties, manages policies, and runs the creation eligibility checks.
   *
   * <p>The response is a valid Iceberg {@code LoadTableResponse}, produced by loading the freshly
   * created table through the same {@code CatalogHandlers.loadTable} path the load handler uses, so
   * the client immediately sees the new table's metadata and per-table config.
   *
   * <p><b>Staged create (CTAS).</b> Spark's CREATE TABLE AS SELECT drives a stock {@code
   * RESTCatalog} to POST this request with {@code stageCreate == true}, then writes the data and
   * publishes it with a follow-up {@code POST .../tables/{table}} (see {@link #updateTable}).
   * Iceberg staged create returns metadata for an as-yet-uncommitted table and relies on that
   * follow-up commit-transaction to publish it atomically. OpenHouse has no true staged
   * transaction: its create path commits the (empty) table to the HTS immediately. We therefore
   * treat {@code stageCreate} the same as a plain create -- create-then-commit, NOT an atomic
   * staged transaction. The window between the create here and the data commit in {@code
   * updateTable} is the accepted atomicity compromise (documented in {@code
   * docs/spark4-iceberg-upgrade/rest-ctas}). The follow-up commit carries a stock {@code
   * assert-create} requirement that cannot hold against the already-created table; {@link
   * #updateTable} handles that gracefully.
   */
  @PostMapping("/namespaces/{namespace}/tables")
  public ResponseEntity<String> createTable(
      @PathVariable("namespace") String namespace, @RequestBody(required = false) String body) {
    Namespace ns = RESTUtil.decodeNamespace(namespace);
    validateSingleLevel(ns);
    CreateTableRequest request = readRequest(body, CreateTableRequest.class);
    request.validate(); // Iceberg-side: name + schema required.

    // stageCreate is intentionally treated identically to a plain create (create-then-commit); see
    // the method javadoc and updateTable() for how the follow-up CTAS data commit is landed.
    String databaseId = ns.level(0);
    String tableId = request.name();
    CreateUpdateTableRequestBody requestBody = toCreateUpdateTableRequestBody(databaseId, request);
    tablesApiHandler.createTable(databaseId, requestBody, extractAuthenticatedUserPrincipal());

    // Load the freshly created table and return it as a LoadTableResponse (same as the load path).
    TableIdentifier ident = TableIdentifier.of(ns, tableId);
    return json(HttpStatus.OK, CatalogHandlers.loadTable(catalog, ident));
  }

  /**
   * Translates an Iceberg {@link CreateTableRequest} into an OpenHouse {@link
   * CreateUpdateTableRequestBody}, mirroring the client-side {@code OpenHouseTableOperations}
   * request-builder so a REST-created table maps to the same OpenHouse model as a natively created
   * one.
   *
   * <ul>
   *   <li><b>Schema</b>: the Iceberg schema serialized with {@code SchemaParser.toJson} (the
   *       inverse of {@code IcebergSchemaHelper.getSchemaFromSchemaJson}).
   *   <li><b>Partitioning</b>: the Iceberg {@code PartitionSpec} is reduced to OpenHouse's single
   *       {@code TimePartitionSpec} plus {@code List<ClusteringColumn>} via {@link
   *       PartitionSpecMapper}; specs OpenHouse cannot model are rejected (HTTP 400).
   *   <li><b>Sort order</b>: passed through as JSON when the request carries a sort order.
   *   <li><b>Properties</b>: user table properties are passed through unchanged, EXCEPT the
   *       reserved {@value #UPDATED_OPENHOUSE_POLICY_KEY} policy-carrier property, which is
   *       intercepted, stripped, and translated into the structured {@link Policies} model (see
   *       {@link #translatePolicyPatch}) so the create pipeline validates and persists it exactly
   *       like the native {@code /tables} create path.
   * </ul>
   */
  private CreateUpdateTableRequestBody toCreateUpdateTableRequestBody(
      String databaseId, CreateTableRequest request) {
    TimePartitionSpec timePartitioning =
        partitionSpecMapper.toTimePartitionSpec(request.schema(), request.spec());
    List<ClusteringColumn> clustering =
        partitionSpecMapper.toClusteringColumns(request.schema(), request.spec());
    Map<String, String> tableProperties =
        request.properties() == null ? new HashMap<>() : new HashMap<>(request.properties());
    // Intercept the policy-carrier property: on create there is no prior policy, so the carried
    // value IS the full policy. Strip it so it is not persisted as an opaque table property, and
    // hand the translated Policies object to the reused create pipeline (which validates + stores
    // it into the reserved `policies` property).
    String policyPatch = tableProperties.remove(UPDATED_OPENHOUSE_POLICY_KEY);
    Policies policies = policyPatch == null ? null : translatePolicyPatch(null, policyPatch);
    String sortOrder =
        (request.writeOrder() != null && request.writeOrder().isSorted())
            ? SortOrderParser.toJson(request.writeOrder())
            : null;

    return CreateUpdateTableRequestBody.builder()
        .tableId(request.name())
        .databaseId(databaseId)
        .clusterId(clusterProperties.getClusterName())
        .schema(SchemaParser.toJson(request.schema()))
        .timePartitioning(timePartitioning)
        .clustering(clustering)
        .tableProperties(tableProperties)
        .policies(policies)
        .sortOrder(sortOrder)
        .baseTableVersion(ValidatorConstants.INITIAL_TABLE_VERSION)
        .build();
  }

  @GetMapping("/namespaces/{namespace}/tables/{table}")
  public ResponseEntity<String> loadTable(
      @PathVariable("namespace") String namespace, @PathVariable("table") String table) {
    TableIdentifier ident = TableIdentifier.of(RESTUtil.decodeNamespace(namespace), table);
    return json(HttpStatus.OK, CatalogHandlers.loadTable(catalog, ident));
  }

  @RequestMapping(path = "/namespaces/{namespace}/tables/{table}", method = RequestMethod.HEAD)
  public ResponseEntity<Void> tableExists(
      @PathVariable("namespace") String namespace, @PathVariable("table") String table) {
    TableIdentifier ident = TableIdentifier.of(RESTUtil.decodeNamespace(namespace), table);
    // Throws NoSuchTableException (-> 404) when absent; returns 204 when present.
    CatalogHandlers.tableExists(catalog, ident);
    return ResponseEntity.noContent().build();
  }

  /**
   * Applies a stock {@code RESTCatalog} {@code UpdateTableRequest} (the {@code POST .../tables/{t}}
   * commit endpoint). A single client endpoint carries three different OpenHouse operations,
   * distinguished by the Iceberg {@code UpdateRequirement} fingerprint the client stamps:
   *
   * <ul>
   *   <li><b>CTAS data commit</b> ({@code AssertTableDoesNotExist} present -- {@code
   *       UpdateRequirements.forCreateTable}). Spark's CREATE TABLE AS SELECT stage-creates the
   *       table (see {@link #createTable}) and then publishes the data with this create-transaction
   *       commit. Because OpenHouse already committed the (empty) table at stage-create time, the
   *       stock {@code assert-create} can never hold and the create-shaped metadata updates (re-add
   *       of the identical schema/spec, {@code SetCurrentSchema(-1)}) would fail against the
   *       existing table. We instead land only the snapshot updates onto the just-created table --
   *       exactly like an INSERT -- via {@link CatalogHandlers}. This is the create-then-commit
   *       compromise noted in {@link #createTable}.
   *   <li><b>RTAS commit</b> ({@code AssertLastAssignedFieldId} present, {@code
   *       AssertCurrentSchemaID} absent -- the {@code UpdateRequirements.forReplaceTable}
   *       fingerprint, which skips the schema/ref/spec/order "not changed" assertions). Spark's
   *       REPLACE TABLE AS SELECT loads the table and commits a wholesale replacement. This is
   *       routed through OpenHouse's own replace pipeline ({@code isReplaceCommit}) so RTAS-enable
   *       gating and replace semantics apply.
   *   <li><b>Everything else</b> (INSERT, ALTER, ref/branch ops) -- a plain {@code
   *       UpdateRequirements.forUpdateTable} commit delegated straight to {@link CatalogHandlers},
   *       which routes through {@code OpenHouseInternalTableOperations.commit} (reserved-key
   *       interception, {@code COMMIT_KEY} CAS) unchanged.
   * </ul>
   *
   * <p><b>Server-side update-validation parity.</b> The plain-update path delegates to {@code
   * CatalogHandlers.updateTable -> OpenHouseInternalCatalog TableOperations.commit}, which BYPASSES
   * the service-layer update validation that {@code TablesService.putTable -> {@code
   * OpenHouseInternalRepositoryImpl.save}} runs on the native path. {@link #enforceUpdateGuards}
   * recovers those guards (table LOCK, reserved {@code openhouse.*}/{@code policies} property
   * guard, partition-spec evolution rejection, schema-evolution validation) by pre-inspecting the
   * projected commit before delegating. The CTAS and RTAS branches are handled above, so they do
   * not reach the guards: CTAS has no existing state to validate, and RTAS runs its own gating in
   * the service replace branch ({@code validateReplaceTable}, #640) reached via {@link
   * #replaceTable} -- so the replace eligibility check is enforced exactly once, in the service
   * layer, not duplicated here.
   */
  @PostMapping("/namespaces/{namespace}/tables/{table}")
  public ResponseEntity<String> updateTable(
      @PathVariable("namespace") String namespace,
      @PathVariable("table") String table,
      @RequestBody(required = false) String body) {
    Namespace ns = RESTUtil.decodeNamespace(namespace);
    TableIdentifier ident = TableIdentifier.of(ns, table);
    UpdateTableRequest request = readRequest(body, UpdateTableRequest.class);

    if (hasRequirement(request, UpdateRequirement.AssertTableDoesNotExist.class)) {
      return commitStagedCreate(ident, request);
    }
    if (isReplacePayload(request)) {
      return replaceTable(ns, ident, request);
    }
    if (isPolicyUpdate(request)) {
      // A SET POLICY carried as the reserved policy-carrier table property. Route it through
      // OpenHouse's own update pipeline (translate -> validate -> persist into `policies`) instead
      // of letting CatalogHandlers persist the carrier verbatim and the reserved-prop guard 400 it.
      return updatePolicy(ns, ident, request);
    }
    // Plain UPDATE (INSERT/ALTER/ref ops): recover the service-layer update guards that the direct
    // CatalogHandlers commit would bypass, then delegate.
    enforceUpdateGuards(ident, request);
    return json(HttpStatus.OK, CatalogHandlers.updateTable(catalog, ident, request));
  }

  private static boolean hasRequirement(
      UpdateTableRequest request, Class<? extends UpdateRequirement> type) {
    return request.requirements().stream().anyMatch(type::isInstance);
  }

  /**
   * Detects a Spark RTAS (REPLACE TABLE AS SELECT) commit -- a wholesale table redefinition -- as
   * opposed to an INSERT/ALTER/ref-op. A stock replace transaction (Iceberg {@code
   * RESTSessionCatalog.replaceTransaction}) always re-establishes the table identity by emitting
   * {@code SetCurrentSchema} + {@code SetDefaultPartitionSpec} + {@code SetDefaultSortOrder}
   * together: {@code TableMetadata.buildReplacement} emits them when the schema/spec/order changes,
   * and {@code replaceTransaction} explicitly adds any of the three that {@code buildReplacement}
   * left out (so a same-schema {@code CREATE OR REPLACE ... AS SELECT *} still carries all three).
   * No INSERT or single-facet ALTER ever emits all three at once, so the trio is a precise replace
   * marker -- and, unlike a requirement fingerprint, it fires for schema-preserving replaces too
   * (whose requirements collapse to just {@code AssertTableUUID}, indistinguishable from a
   * property-only ALTER). {@code AssertTableDoesNotExist} (CTAS create-commit) is ruled out before
   * this check.
   */
  private static boolean isReplacePayload(UpdateTableRequest request) {
    boolean setsCurrentSchema = false;
    boolean setsDefaultSpec = false;
    boolean setsDefaultSortOrder = false;
    for (MetadataUpdate update : request.updates()) {
      if (update instanceof MetadataUpdate.SetCurrentSchema) {
        setsCurrentSchema = true;
      } else if (update instanceof MetadataUpdate.SetDefaultPartitionSpec) {
        setsDefaultSpec = true;
      } else if (update instanceof MetadataUpdate.SetDefaultSortOrder) {
        setsDefaultSortOrder = true;
      }
    }
    return setsCurrentSchema && setsDefaultSpec && setsDefaultSortOrder;
  }

  /**
   * Lands a Spark CTAS create-transaction commit onto the table OpenHouse already created at
   * stage-create time. The client sends a full "create" payload (all metadata updates that rebuild
   * the table, plus the data snapshot) guarded by a single {@code AssertTableDoesNotExist}. That
   * assertion cannot hold (the table exists) and the create-shaped metadata updates -- which re-add
   * the identical schema/spec and reference them with {@code SetCurrentSchema(-1)} / {@code
   * SetDefaultPartitionSpec(-1)} -- would throw when applied on top of the existing metadata. The
   * only genuinely new content is the snapshot(s), which we apply on their own, exactly as a plain
   * INSERT does. The schema/spec/properties were already materialized from the same
   * CreateTableRequest at stage-create, so nothing is lost.
   */
  private ResponseEntity<String> commitStagedCreate(
      TableIdentifier ident, UpdateTableRequest request) {
    if (!catalog.tableExists(ident)) {
      // Should not happen: Spark stage-creates the table before this commit. Delegate unchanged so
      // the standard error surfaces rather than masking an unexpected state.
      return json(HttpStatus.OK, CatalogHandlers.updateTable(catalog, ident, request));
    }
    List<MetadataUpdate> snapshotUpdates =
        request.updates().stream()
            .filter(
                u ->
                    u instanceof MetadataUpdate.AddSnapshot
                        || u instanceof MetadataUpdate.SetSnapshotRef
                        || u instanceof MetadataUpdate.RemoveSnapshotRef
                        || u instanceof MetadataUpdate.RemoveSnapshots)
            .collect(Collectors.toList());
    // Empty requirements + snapshot-only updates => CatalogHandlers routes to the plain commit
    // branch (not the create branch) and applies the snapshot onto the existing table.
    UpdateTableRequest snapshotOnly =
        new UpdateTableRequest(Collections.emptyList(), snapshotUpdates);
    return json(HttpStatus.OK, CatalogHandlers.updateTable(catalog, ident, snapshotOnly));
  }

  /**
   * Routes a Spark RTAS (REPLACE TABLE AS SELECT) commit through OpenHouse's replace pipeline. The
   * stock replace transaction sends the wholesale replacement metadata updates plus the data
   * snapshot. We reconstruct the final {@link TableMetadata} by applying those updates onto the
   * current base (the replace payload is delta-shaped, so this is safe), then translate it into the
   * same {@link IcebergSnapshotsRequestBody} the native client's replace-commit builds and hand it
   * to the {@code IcebergSnapshotsApiHandler} with {@code replaceCommit == true}. The
   * service/repository layer then enforces RTAS-enable gating ({@code validateReplaceTable}),
   * re-manages policies, and runs the replace via {@code OpenHouseInternalRepositoryImpl.save}'s
   * {@code isReplaceCommit} branch. The response is a fresh {@code LoadTableResponse} for the
   * replaced table.
   */
  private ResponseEntity<String> replaceTable(
      Namespace ns, TableIdentifier ident, UpdateTableRequest request) {
    BaseTable table = (BaseTable) catalog.loadTable(ident);
    TableMetadata base = table.operations().current();

    TableMetadata.Builder builder = TableMetadata.buildFrom(base);
    request.updates().forEach(update -> update.applyTo(builder));
    TableMetadata finalMetadata = builder.build();

    String databaseId = ns.level(0);
    String tableId = ident.name();
    String policiesJson = finalMetadata.properties().get("policies");

    CreateUpdateTableRequestBody requestBody =
        CreateUpdateTableRequestBody.builder()
            .tableId(tableId)
            .databaseId(databaseId)
            .clusterId(clusterProperties.getClusterName())
            .schema(SchemaParser.toJson(finalMetadata.schema()))
            .timePartitioning(
                partitionSpecMapper.toTimePartitionSpec(
                    finalMetadata.schema(), finalMetadata.spec()))
            .clustering(
                partitionSpecMapper.toClusteringColumns(
                    finalMetadata.schema(), finalMetadata.spec()))
            .tableProperties(new HashMap<>(finalMetadata.properties()))
            .policies(
                policiesJson == null ? null : policiesSpecMapper.toPoliciesObject(policiesJson))
            .sortOrder(
                finalMetadata.sortOrder().isSorted()
                    ? SortOrderParser.toJson(finalMetadata.sortOrder())
                    : null)
            .baseTableVersion(base.metadataFileLocation())
            .replaceCommit(true)
            .build();

    IcebergSnapshotsRequestBody snapshotsRequestBody =
        IcebergSnapshotsRequestBody.builder()
            .baseTableVersion(base.metadataFileLocation())
            .jsonSnapshots(
                finalMetadata.snapshots().stream()
                    .map(SnapshotParser::toJson)
                    .collect(Collectors.toList()))
            .snapshotRefs(
                finalMetadata.refs().entrySet().stream()
                    .collect(
                        Collectors.toMap(
                            Map.Entry::getKey, e -> SnapshotRefParser.toJson(e.getValue()))))
            .createUpdateTableRequestBody(requestBody)
            .build();

    icebergSnapshotsApiHandler.putIcebergSnapshots(
        databaseId, tableId, snapshotsRequestBody, extractAuthenticatedUserPrincipal());

    return json(HttpStatus.OK, CatalogHandlers.loadTable(catalog, ident));
  }

  // ---------------------------------------------------------------------------
  // Policy set/update (parity with the native /tables policy path).
  //
  // A stock RESTCatalog cannot speak OpenHouse's structured Policies model; the client contract is
  // therefore to carry a policy as the reserved `updated.openhouse.policy` table property (see
  // UPDATED_OPENHOUSE_POLICY_KEY), exactly the legacy Spark SET POLICY encoding. On create this is
  // handled in toCreateUpdateTableRequestBody(); on update it is handled here.
  // ---------------------------------------------------------------------------

  /**
   * Detects a policy-set commit: a plain property update that carries the reserved {@value
   * #UPDATED_OPENHOUSE_POLICY_KEY} property. CTAS ({@code AssertTableDoesNotExist}) and RTAS
   * ({@link #isReplacePayload}) are ruled out by {@link #updateTable} before this check, so a match
   * here is always an {@code ALTER TABLE ... SET TBLPROPERTIES} / {@code updateProperties()}
   * carrying a policy. Snapshot commits (INSERT) and other ALTERs never set this key, so they fall
   * through to the plain-update path unchanged.
   */
  private static boolean isPolicyUpdate(UpdateTableRequest request) {
    return request.updates().stream()
        .anyMatch(
            u ->
                u instanceof MetadataUpdate.SetProperties
                    && ((MetadataUpdate.SetProperties) u)
                        .updated()
                        .containsKey(UPDATED_OPENHOUSE_POLICY_KEY));
  }

  /**
   * Applies a policy-set commit by routing it through OpenHouse's own update pipeline instead of
   * the direct {@link CatalogHandlers} commit. This mirrors what the legacy client's {@code
   * OpenHouseTableOperations.constructMetadataRequestBody} does: it strips the {@value
   * #UPDATED_OPENHOUSE_POLICY_KEY} carrier from the table properties and translates it into the
   * structured {@link Policies} model (merging onto any existing policy -- see {@link
   * #translatePolicyPatch}), then hands the full request to {@link TablesApiHandler#updateTable}.
   * The reused service layer runs the SAME policy validation the native {@code /tables} path runs
   * ({@code OpenHouseTablesApiValidator.validatePolicies}) and persists the merged policy into the
   * reserved {@code policies} property ({@code TablePolicyManager.managePoliciesOnUpdateIfNeeded}).
   * The response is a fresh {@code LoadTableResponse} carrying the updated {@code policies}
   * property.
   *
   * <p>Only the {@code policies} property changes; the carrier is not persisted and no other table
   * property, schema, spec or sort order is altered (the projected metadata differs from the base
   * only by the carrier, which is stripped). Locked tables are rejected up front, exactly as {@code
   * TablesService.putTable} does.
   */
  private ResponseEntity<String> updatePolicy(
      Namespace ns, TableIdentifier ident, UpdateTableRequest request) {
    BaseTable table = (BaseTable) catalog.loadTable(ident);
    TableMetadata base = table.operations().current();
    enforceNotLocked(ident, base);

    TableMetadata.Builder builder = TableMetadata.buildFrom(base);
    request.updates().forEach(update -> update.applyTo(builder));
    TableMetadata finalMetadata = builder.build();

    String policyPatch = finalMetadata.properties().get(UPDATED_OPENHOUSE_POLICY_KEY);
    Policies mergedPolicies =
        translatePolicyPatch(base.properties().get(POLICIES_KEY), policyPatch);

    // Carry through all other properties unchanged (mirroring the legacy client), minus the policy
    // carrier which must never be persisted verbatim.
    Map<String, String> tableProperties = new HashMap<>(finalMetadata.properties());
    tableProperties.remove(UPDATED_OPENHOUSE_POLICY_KEY);

    String databaseId = ns.level(0);
    String tableId = ident.name();
    CreateUpdateTableRequestBody requestBody =
        CreateUpdateTableRequestBody.builder()
            .tableId(tableId)
            .databaseId(databaseId)
            .clusterId(clusterProperties.getClusterName())
            .schema(SchemaParser.toJson(finalMetadata.schema()))
            .timePartitioning(
                partitionSpecMapper.toTimePartitionSpec(
                    finalMetadata.schema(), finalMetadata.spec()))
            .clustering(
                partitionSpecMapper.toClusteringColumns(
                    finalMetadata.schema(), finalMetadata.spec()))
            .tableProperties(tableProperties)
            .policies(mergedPolicies)
            .sortOrder(
                finalMetadata.sortOrder().isSorted()
                    ? SortOrderParser.toJson(finalMetadata.sortOrder())
                    : null)
            .baseTableVersion(base.metadataFileLocation())
            .build();

    tablesApiHandler.updateTable(
        databaseId, tableId, requestBody, extractAuthenticatedUserPrincipal());

    return json(HttpStatus.OK, CatalogHandlers.loadTable(catalog, ident));
  }

  /**
   * Translates the {@value #UPDATED_OPENHOUSE_POLICY_KEY} carrier JSON into a structured {@link
   * Policies} object, merging it onto the table's existing policy. This reproduces the merge
   * semantics of the legacy client's {@code OpenHouseTableOperations.buildUpdatedPolicies} on the
   * server side (the stock RESTCatalog client cannot merge): each sub-policy present in the patch
   * overrides the corresponding existing sub-policy, while sub-policies absent from the patch are
   * preserved. {@code sharingEnabled} is a primitive on the server {@code Policies} model (it
   * cannot carry a tri-state null), so its presence is detected by inspecting the raw patch JSON --
   * exactly how the legacy client keyed off its nullable {@code Boolean}. Parsing reuses the shared
   * {@link PoliciesSpecMapper} bean so the two lanes cannot drift.
   *
   * @param existingPoliciesJson the current serialized {@code policies} property, or {@code null}
   *     on create / when the table has no policy yet
   * @param patchJson the carrier value (never {@code null} at the call sites)
   * @return the merged {@link Policies}, or the patch itself when there is no existing policy
   */
  private Policies translatePolicyPatch(String existingPoliciesJson, String patchJson) {
    Policies patch = policiesSpecMapper.toPoliciesObject(patchJson == null ? "" : patchJson);
    Policies existing =
        (existingPoliciesJson == null || existingPoliciesJson.isEmpty())
            ? null
            : policiesSpecMapper.toPoliciesObject(existingPoliciesJson);
    if (patch == null) {
      return existing;
    }
    if (existing == null) {
      return patch;
    }
    com.google.gson.JsonObject patchObj =
        com.google.gson.JsonParser.parseString(patchJson).getAsJsonObject();
    Policies.PoliciesBuilder merged = existing.toBuilder();
    if (patch.getRetention() != null) {
      merged.retention(patch.getRetention());
    }
    if (patchObj.has("sharingEnabled")) {
      merged.sharingEnabled(patch.isSharingEnabled());
    }
    if (patch.getColumnTags() != null) {
      merged.columnTags(patch.getColumnTags());
    }
    if (patch.getReplication() != null) {
      merged.replication(patch.getReplication());
    }
    if (patch.getHistory() != null) {
      merged.history(patch.getHistory());
    }
    if (patch.getLockState() != null) {
      merged.lockState(patch.getLockState());
    }
    return merged.build();
  }

  // ---------------------------------------------------------------------------
  // Server-side update-validation guards (parity with the native
  // TablesService.putTable -> OpenHouseInternalRepositoryImpl.save UPDATE path).
  //
  // Only the plain-UPDATE branch reaches these: a staged create (AssertTableDoesNotExist) and an
  // RTAS replace (isReplacePayload) are routed away in updateTable() before this runs. RTAS
  // eligibility (replace.enabled + WAP/replication, #640) is therefore enforced solely by the
  // service replace branch reached through replaceTable() -- it is NOT re-implemented here, so the
  // replace gate is never double-applied.
  // ---------------------------------------------------------------------------

  /** The reserved property key that carries a table's serialized OpenHouse {@code Policies}. */
  private static final String POLICIES_KEY = "policies";

  /** Namespace prefix that marks a table property as OpenHouse-reserved. */
  private static final String OPENHOUSE_PROP_PREFIX = "openhouse.";

  /**
   * The client contract for setting a table policy over the stock {@code RESTCatalog} lane: a table
   * property whose value is the JSON serialization of a (partial) OpenHouse {@code Policies}
   * object, e.g. {@code {"retention":{"count":3,"granularity":"DAY"}}} or {@code
   * {"sharingEnabled":true}}. This is exactly the key + encoding the legacy Spark {@code
   * Set*PolicyExec} uses (consumed by the legacy client's {@code
   * OpenHouseTableOperations.buildUpdatedPolicies}), so a future Spark-4.0 port of the {@code SET
   * POLICY} SQL extension maps onto this server translation unchanged. Unlike a normal table
   * property it is NEVER persisted verbatim: on both create and update it is stripped and folded
   * into the reserved {@link #POLICIES_KEY} property via the OpenHouse policy pipeline.
   */
  private static final String UPDATED_OPENHOUSE_POLICY_KEY = "updated.openhouse.policy";

  /**
   * Re-applies OpenHouse's service-layer UPDATE validation to a plain REST commit. Runs before the
   * commit is delegated to {@link CatalogHandlers#updateTable}; a violation throws a mapped
   * exception (-> 4xx in the Iceberg {@code ErrorResponse} envelope) so the commit never reaches
   * the catalog. Mirrors the update-eligibility checks in {@code
   * OpenHouseInternalRepositoryImpl.save(TableDto)}'s non-replace branch: table LOCK, reserved
   * {@code openhouse.*}/{@code policies} property immutability, partition-spec evolution rejection,
   * and schema-evolution validation. A pure snapshot commit (happy-path DML) changes none of those,
   * so every guard is a no-op for it.
   */
  private void enforceUpdateGuards(TableIdentifier ident, UpdateTableRequest request) {
    TableMetadata base;
    TableMetadata updated;
    try {
      Table loaded = catalog.loadTable(ident);
      if (!(loaded instanceof BaseTable)) {
        return;
      }
      base = ((BaseTable) loaded).operations().current();
      if (base == null) {
        return;
      }
      // Mirror CatalogHandlers.commit: apply the requested MetadataUpdates to a builder to obtain
      // the metadata this commit WOULD produce, then validate that projection. (This is inspection
      // only -- the real commit is still performed by the delegate.)
      TableMetadata.Builder builder = TableMetadata.buildFrom(base);
      request.updates().forEach(update -> update.applyTo(builder));
      updated = builder.build();
    } catch (NoSuchTableException e) {
      throw e; // -> 404, same as the delegate would produce.
    } catch (RuntimeException e) {
      // If we cannot project the commit for any other reason, do not invent a new failure mode:
      // fall through and let CatalogHandlers.updateTable apply and report the authoritative error.
      log.debug("Skipping REST update guards for {}: could not project commit", ident, e);
      return;
    }

    enforceNotLocked(ident, base);
    enforceReservedPropsUnchanged(base, updated);
    enforcePartitionSpecUnchanged(base, updated);
    enforceSchemaEvolutionValid(ident, base, updated);
  }

  private static boolean isReservedKey(String key) {
    return key.startsWith(OPENHOUSE_PROP_PREFIX) || POLICIES_KEY.equals(key);
  }

  private static Map<String, String> reservedProps(Map<String, String> props) {
    return props.entrySet().stream()
        .filter(e -> isReservedKey(e.getKey()))
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  /**
   * Table LOCK enforcement. A locked table rejects every plain mutation -- parity with {@code
   * TablesService.putTable}, which throws {@code LOCKED_TABLE_OPERATION} before dispatching an
   * update. The lock lives in the reserved {@code policies} property as {@code lockState.locked}.
   */
  private void enforceNotLocked(TableIdentifier ident, TableMetadata base) {
    if (isLocked(base)) {
      throw new UnsupportedClientOperationException(
          UnsupportedClientOperationException.Operation.LOCKED_TABLE_OPERATION,
          String.format(
              "Table %s.%s is in locked state and cannot be updated.",
              ident.namespace(), ident.name()));
    }
  }

  private static boolean isLocked(TableMetadata metadata) {
    String policiesJson = metadata.properties().get(POLICIES_KEY);
    if (policiesJson == null || policiesJson.isEmpty()) {
      return false;
    }
    // Minimal, dependency-free probe of the serialized Policies for lockState.locked == true. The
    // policies blob is server-authored JSON; a lenient JSON check avoids coupling to the full
    // Policies model here while still recognizing the locked state.
    try {
      com.google.gson.JsonObject policies =
          com.google.gson.JsonParser.parseString(policiesJson).getAsJsonObject();
      if (!policies.has("lockState") || policies.get("lockState").isJsonNull()) {
        return false;
      }
      com.google.gson.JsonObject lockState = policies.getAsJsonObject("lockState");
      return lockState.has("locked") && lockState.get("locked").getAsBoolean();
    } catch (RuntimeException e) {
      return false;
    }
  }

  /**
   * Reserved-property guard. A user may not add, alter or drop any {@code openhouse.*} property or
   * the {@code policies} property -- parity with {@code checkIfPreservedTblPropsModified} (which
   * also covers the {@code openhouse.tableType}-immutability case). The message contains the word
   * "restriction" that the native path uses.
   */
  private void enforceReservedPropsUnchanged(TableMetadata base, TableMetadata updated) {
    Map<String, String> before = reservedProps(base.properties());
    Map<String, String> after = reservedProps(updated.properties());
    if (!before.equals(after)) {
      throw new UnsupportedClientOperationException(
          UnsupportedClientOperationException.Operation.ALTER_RESERVED_TBLPROPS,
          "Bad tblproperties provided: Can't add, alter or drop table properties due to the "
              + "restriction: [table properties starting with `openhouse.` and the `policies` key "
              + "cannot be modified].");
    }
  }

  /**
   * Partition-spec evolution rejection. OpenHouse does not permit adding/dropping partition or
   * clustering columns on an existing table -- parity with {@code checkPartitionSpecEvolution}.
   */
  private void enforcePartitionSpecUnchanged(TableMetadata base, TableMetadata updated) {
    List<String> before =
        base.spec().fields().stream().map(PartitionField::name).collect(Collectors.toList());
    List<String> after =
        updated.spec().fields().stream().map(PartitionField::name).collect(Collectors.toList());
    if (!before.equals(after)) {
      throw new UnsupportedClientOperationException(
          UnsupportedClientOperationException.Operation.PARTITION_EVOLUTION,
          "Evolution of table partitioning and clustering columns are not supported, recreate the "
              + "table with new partition spec.");
    }
  }

  /**
   * Schema-evolution validation. When the commit changes the schema, hold it to the same rules as
   * the native path -- no column drops (top-level or nested), no incompatible type narrowing, no
   * required-tightening -- by reusing the {@link SchemaValidator} bean. Throws {@link
   * InvalidSchemaEvolutionException} (-> 400) on violation.
   */
  private void enforceSchemaEvolutionValid(
      TableIdentifier ident, TableMetadata base, TableMetadata updated) {
    if (!updated.schema().sameSchema(base.schema())) {
      schemaValidator.validateWriteSchema(base.schema(), updated.schema(), ident.toString());
    }
  }

  @DeleteMapping("/namespaces/{namespace}/tables/{table}")
  public ResponseEntity<String> dropTable(
      @PathVariable("namespace") String namespace,
      @PathVariable("table") String table,
      @RequestParam(value = "purgeRequested", required = false, defaultValue = "false")
          boolean purgeRequested) {
    TableIdentifier ident = TableIdentifier.of(RESTUtil.decodeNamespace(namespace), table);
    if (purgeRequested) {
      CatalogHandlers.purgeTable(catalog, ident);
    } else {
      CatalogHandlers.dropTable(catalog, ident);
    }
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/tables/rename")
  public ResponseEntity<Void> renameTable(@RequestBody(required = false) String body) {
    RenameTableRequest request = readRequest(body, RenameTableRequest.class);
    CatalogHandlers.renameTable(catalog, request);
    return ResponseEntity.noContent().build();
  }

  // ---------------------------------------------------------------------------
  // (De)serialization helpers
  // ---------------------------------------------------------------------------

  private <T> T readRequest(String body, Class<T> type) {
    if (body == null || body.isEmpty()) {
      throw new BadRequestException("Missing request body for %s", type.getSimpleName());
    }
    try {
      return MAPPER.readValue(body, type);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new BadRequestException(
          "Malformed request body for %s: %s", type.getSimpleName(), e.getMessage());
    }
  }

  private static ResponseEntity<String> json(HttpStatus status, Object payload) {
    try {
      return ResponseEntity.status(status)
          .contentType(MediaType.APPLICATION_JSON)
          .body(MAPPER.writeValueAsString(payload));
    } catch (Exception e) {
      throw new RESTException(e, "Failed to serialize REST response: %s", e.getMessage());
    }
  }

  private static void validateSingleLevel(Namespace namespace) {
    if (namespace == null || namespace.levels().length != 1) {
      throw new ValidationException(
          "OpenHouse supports single-level namespaces (databaseId); got: %s", namespace);
    }
  }

  private static ErrorResponse errorBody(HttpStatus status, String type, String message) {
    return ErrorResponse.builder()
        .responseCode(status.value())
        .withType(type)
        .withMessage(message)
        .build();
  }

  // ---------------------------------------------------------------------------
  // Error handling -> Iceberg ErrorResponse envelope
  //
  // These local handlers win over the global OpenHouseExceptionHandler @ControllerAdvice for
  // exceptions thrown by this controller, so the RESTCatalog client always receives the Iceberg
  // error envelope with the spec-mandated HTTP status.
  // ---------------------------------------------------------------------------

  @ExceptionHandler({NoSuchTableException.class, NoSuchNamespaceException.class})
  public ResponseEntity<String> handleNotFound(Exception e) {
    return error(HttpStatus.NOT_FOUND, e);
  }

  @ExceptionHandler({
    AlreadyExistsException.class,
    CommitFailedException.class,
    CommitStateUnknownException.class,
    // OpenHouse service-layer equivalents thrown by the reused create path:
    com.linkedin.openhouse.common.exception.AlreadyExistsException.class,
    EntityConcurrentModificationException.class
  })
  public ResponseEntity<String> handleConflict(Exception e) {
    return error(HttpStatus.CONFLICT, e);
  }

  @ExceptionHandler(NotAuthorizedException.class)
  public ResponseEntity<String> handleUnauthorized(Exception e) {
    return error(HttpStatus.UNAUTHORIZED, e);
  }

  @ExceptionHandler(ForbiddenException.class)
  public ResponseEntity<String> handleForbidden(Exception e) {
    return error(HttpStatus.FORBIDDEN, e);
  }

  @ExceptionHandler({
    ValidationException.class,
    BadRequestException.class,
    IllegalArgumentException.class,
    // OpenHouse service-layer equivalents thrown by the reused create path and the update guards:
    RequestValidationFailureException.class,
    UnsupportedClientOperationException.class,
    InvalidSchemaEvolutionException.class,
    // Corrupt/unparseable stored metadata is a permanent bad-state, not a transient server failure.
    // Map it to a NON-retryable 400 (was falling through to handleGeneric -> 500, which the stock
    // client's read/commit path retries with exponential backoff up to ~30 min before surfacing the
    // error). The "has invalid metadata" message is preserved for the client either way.
    com.linkedin.openhouse.common.exception.InvalidTableMetadataException.class
  })
  public ResponseEntity<String> handleBadRequest(Exception e) {
    return error(HttpStatus.BAD_REQUEST, e);
  }

  @ExceptionHandler(UnsupportedOperationException.class)
  public ResponseEntity<String> handleUnsupported(Exception e) {
    return error(HttpStatus.NOT_IMPLEMENTED, e);
  }

  @ExceptionHandler({RESTException.class, Exception.class})
  public ResponseEntity<String> handleGeneric(Exception e) {
    log.warn("Unmapped exception in Iceberg REST endpoint", e);
    return error(HttpStatus.INTERNAL_SERVER_ERROR, e);
  }

  private static ResponseEntity<String> error(HttpStatus status, Exception e) {
    return json(status, errorBody(status, e.getClass().getSimpleName(), e.getMessage()));
  }
}
