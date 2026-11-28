package com.linkedin.openhouse.tables.services;

import static com.linkedin.openhouse.internal.catalog.CatalogConstants.ENTITY_TYPE_VIEW;
import static com.linkedin.openhouse.internal.catalog.mapper.HouseTableSerdeUtils.getCanonicalFieldName;

import com.linkedin.openhouse.cluster.configs.ClusterProperties;
import com.linkedin.openhouse.cluster.storage.Storage;
import com.linkedin.openhouse.cluster.storage.StorageManager;
import com.linkedin.openhouse.cluster.storage.StorageType;
import com.linkedin.openhouse.cluster.storage.selector.StorageSelector;
import com.linkedin.openhouse.common.api.validator.ValidatorConstants;
import com.linkedin.openhouse.common.schema.IcebergSchemaHelper;
import com.linkedin.openhouse.internal.catalog.fileio.FileIOManager;
import com.linkedin.openhouse.internal.catalog.model.HouseTable;
import com.linkedin.openhouse.internal.catalog.model.HouseTablePrimaryKey;
import com.linkedin.openhouse.internal.catalog.repository.HouseTableRepository;
import com.linkedin.openhouse.internal.catalog.repository.exception.HouseTableRepositoryStateUnknownException;
import com.linkedin.openhouse.internal.catalog.view.ViewCommitEngine;
import com.linkedin.openhouse.internal.catalog.view.ViewMetadataCodec;
import com.linkedin.openhouse.internal.catalog.view.model.SqlViewRepresentationIntent;
import com.linkedin.openhouse.internal.catalog.view.model.ViewCommitIntent;
import com.linkedin.openhouse.internal.catalog.view.model.ViewCommitResult;
import com.linkedin.openhouse.internal.catalog.view.model.ViewPointer;
import com.linkedin.openhouse.tables.api.spec.v0.request.CreateUpdateViewRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.ViewRepresentation;
import com.linkedin.openhouse.tables.authorization.Privileges;
import com.linkedin.openhouse.tables.exception.ViewApiException;
import com.linkedin.openhouse.tables.exception.ViewErrorCode;
import com.linkedin.openhouse.tables.exception.ViewFailureTranslator;
import com.linkedin.openhouse.tables.model.ReservedViewProperties;
import com.linkedin.openhouse.tables.model.TableDto;
import com.linkedin.openhouse.tables.model.ViewDto;
import com.linkedin.openhouse.tables.model.ViewListResult;
import com.linkedin.openhouse.tables.utils.AllocatedDirectory;
import com.linkedin.openhouse.tables.utils.AuthorizationUtils;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;
import org.apache.iceberg.Schema;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.exceptions.CommitStateUnknownException;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.view.ViewMetadata;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.util.Pair;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * Default {@link ViewsService}, modeled after {@code TablesServiceImpl} without reusing it: no
 * snapshots, partition specs, sort orders, or retention semantics. Per-intent order:
 *
 * <pre>
 * GET:  gate -> HTS read -> metadata read (no authorization)
 * IRC load ({@link #loadReadableView}): gate -> HTS read -> read authorization -> metadata read
 * LIST: gate -> HTS read (no authorization)
 *
 * POST / PUT: gate -> route database existence -> prepared capture -> authorization
 *             (replace: the captured view; otherwise the database) -> resource type / base-version -> allocate (create only)
 *             -> commit
 *
 * DELETE:     gate -> prepared capture -> authorization
 *             -> delete
 * </pre>
 *
 * Captures, reads, lists and deletes use the typed {@link HouseTableRepository}; a create selects
 * and allocates storage here, above the commit-only {@link ViewCommitEngine}. GET and POST/PUT
 * responses carry the server-owned {@code clusterId}; failures are typed with their cause kept for
 * internal classification and never rendered to the caller.
 */
@Component
public class ViewsServiceImpl implements ViewsService {

  private static final String VIEWS_DISABLED_MESSAGE = "Views are disabled";

  private static final Pageable SINGLE_ROW = PageRequest.of(0, 1);

  @Autowired private ViewsFeatureGate featureGate;

  @Autowired private HouseTableRepository houseTableRepository;

  @Autowired private ViewCommitEngine viewCommitEngine;

  @Autowired private StorageSelector storageSelector;

  @Autowired private FileIOManager fileIOManager;

  @Autowired private ViewMetadataCodec viewMetadataCodec;

  @Autowired private StorageType storageType;

  @Autowired private StorageManager storageManager;

  @Autowired private AuthorizationUtils authorizationUtils;

  @Autowired private ViewWritePrivilegeMapper privilegeMapper;

  @Autowired private ClusterProperties clusterProperties;

  private final ViewPaginationAdapter paginationAdapter =
      new ViewPaginationAdapter(this::sourcePage, ViewPaginationAdapter.DEFAULT_SOURCE_PAGE_SIZE);

  /**
   * Resolves the cluster's default storage eagerly so an unresolvable default fails startup. The
   * result is deliberately not retained: create always selects storage per request.
   */
  @PostConstruct
  void validateDefaultStorage() {
    storageManager.getDefaultStorage();
  }

  @Override
  public ViewDto getView(String databaseId, String viewId, String actingPrincipal) {
    return loadView(databaseId, viewId, actingPrincipal).getView();
  }

  /**
   * {@link #getView} together with the metadata it reads: the same gate, the same House Tables read
   * and the same single read of the metadata file at the pointer, with the same failures. For
   * callers that serve the metadata itself (the IRC view routes); not on {@link ViewsService},
   * whose disabled implementation runs where the Iceberg view types do not exist.
   */
  public LoadedView loadView(String databaseId, String viewId, String actingPrincipal) {
    return load(databaseId, viewId, row -> {});
  }

  /**
   * {@link #loadView} for a caller who is handed the metadata the service reads (the IRC view
   * load): after the House Tables read, and before the metadata file is read, the caller must hold
   * the read privilege a table load asks for ({@code GET_TABLE_METADATA}), on the view's
   * authorization reference, as a replace or drop is decided on it. A missing view is the same 404
   * before any decision, as for a table load.
   */
  public LoadedView loadReadableView(String databaseId, String viewId, String actingPrincipal) {
    return load(
        databaseId,
        viewId,
        row -> authorizeRead(icebergRestAuthorizationReference(row), actingPrincipal));
  }

  /**
   * The view's House Tables row must exist and be a view (404 otherwise, after the views gate), and
   * the authorization reference a replace or drop of it is decided on: no metadata is read.
   */
  public TableDto viewReference(String databaseId, String viewId) {
    try {
      requireEnabled(databaseId);
      return icebergRestAuthorizationReference(
          houseTableRepository
              .findViewById(primaryKey(databaseId, viewId))
              .orElseThrow(() -> noSuchView(databaseId, viewId)));
    } catch (ViewApiException e) {
      throw e;
    } catch (RuntimeException e) {
      throw ViewFailureTranslator.translateReadFailureTo(e);
    }
  }

  /**
   * The decision {@link #putView} makes for a replace of an existing view, on its reference ({@link
   * #viewReference}), for a caller that decides it before it checks a request against the stored
   * view; {@code putView} still makes it. Failures are those {@code putView} gives for it.
   */
  public void authorizeReplace(TableDto reference, String actingPrincipal) {
    try {
      authorizationUtils.checkTablePrivilege(
          reference, actingPrincipal, privilegeMapper.forPut(true));
    } catch (RuntimeException e) {
      throw ViewFailureTranslator.translateWriteFailureTo(e);
    }
  }

  /**
   * The read decision of {@link #loadReadableView}: the read privilege a table load asks for
   * ({@code GET_TABLE_METADATA}), on a view's reference ({@link #viewReference}).
   */
  public void authorizeRead(TableDto reference, String actingPrincipal) {
    authorizationUtils.checkTablePrivilege(
        reference, actingPrincipal, Privileges.GET_TABLE_METADATA);
  }

  /**
   * The gate, the House Tables read and the single read of the metadata file at the pointer, with
   * {@code beforeRead} run on the row in between. An authorization failure of {@code beforeRead}
   * keeps its own type, as on the write paths.
   */
  private LoadedView load(String databaseId, String viewId, Consumer<HouseTable> beforeRead) {
    try {
      requireEnabled(databaseId);
      HouseTable viewRow =
          houseTableRepository
              .findViewById(primaryKey(databaseId, viewId))
              .orElseThrow(() -> noSuchView(databaseId, viewId));
      requireCompletePersistedPointer(viewRow);
      beforeRead.accept(viewRow);
      FileIO fileIO = fileIOManager.getFileIO(storageType.fromString(viewRow.getStorageType()));
      ViewMetadata metadata =
          viewMetadataCodec.read(fileIO.newInputFile(viewRow.getTableLocation()));
      return new LoadedView(withClusterId(toPointerDto(viewRow, metadata.properties())), metadata);
    } catch (ViewApiException | AccessDeniedException e) {
      throw e;
    } catch (RuntimeException e) {
      throw ViewFailureTranslator.translateReadFailureTo(e);
    }
  }

  /**
   * The metadata file at {@code metadataLocation}, a version of the view {@code databaseId.viewId}
   * that a write stored, read with the FileIO of the view's storage: for the answer of a write
   * whose view has moved on since. No authorization decision: the caller has made the read decision
   * on the view. A file that cannot be read fails as the read fails.
   */
  public ViewMetadata readViewVersion(String databaseId, String viewId, String metadataLocation) {
    HouseTable viewRow =
        houseTableRepository
            .findViewById(primaryKey(databaseId, viewId))
            .orElseThrow(() -> noSuchView(databaseId, viewId));
    FileIO fileIO = fileIOManager.getFileIO(storageType.fromString(viewRow.getStorageType()));
    return viewMetadataCodec.read(fileIO.newInputFile(metadataLocation));
  }

  /** A view's pointer and the metadata stored at it, read once. */
  @Getter
  @AllArgsConstructor
  public static final class LoadedView {
    private final ViewDto view;
    private final ViewMetadata metadata;
  }

  @Override
  public ViewListResult getAllViews(
      String databaseId, String pageToken, int size, String sortBy, String actingPrincipal) {
    try {
      requireEnabled(databaseId);
      return paginationAdapter.list(databaseId, pageToken, size, sortBy);
    } catch (ViewApiException e) {
      throw e;
    } catch (RuntimeException e) {
      throw ViewFailureTranslator.translateReadFailureTo(e);
    }
  }

  @Override
  public Pair<ViewDto, Boolean> putView(
      CreateUpdateViewRequestBody requestBody, String actingPrincipal, boolean failOnExist) {
    String databaseId = requestBody.getDatabaseId();
    String viewId = requestBody.getViewId();
    try {
      requireEnabled(databaseId);
      requireDatabaseExists(databaseId);

      Optional<HouseTable> occupant =
          houseTableRepository.findEntityById(primaryKey(databaseId, viewId));
      PreparedViewOperation prepared =
          occupant
              .map(
                  row ->
                      ENTITY_TYPE_VIEW.equals(row.getEntityType())
                          ? PreparedViewOperation.view(row)
                          : PreparedViewOperation.tableOccupant(row))
              .orElseGet(PreparedViewOperation::observedAbsence);
      boolean viewAlreadyExists = prepared.getViewBaseRow().isPresent();
      Privileges privilege =
          failOnExist ? privilegeMapper.forCreate() : privilegeMapper.forPut(viewAlreadyExists);
      if (!failOnExist && viewAlreadyExists) {
        authorizationUtils.checkTablePrivilege(
            authorizationReference(prepared.getViewBaseRow().get()), actingPrincipal, privilege);
      } else {
        authorizationUtils.checkDatabasePrivilege(databaseId, actingPrincipal, privilege);
      }

      if (failOnExist && viewAlreadyExists) {
        throw new ViewApiException(
            ViewErrorCode.VIEW_ALREADY_EXISTS, "View already exists: " + databaseId + "." + viewId);
      }
      if (occupant.filter(row -> !ENTITY_TYPE_VIEW.equals(row.getEntityType())).isPresent()) {
        throw new ViewApiException(
            ViewErrorCode.NAME_ALREADY_EXISTS_AS_TABLE,
            "Name already exists as a table: " + databaseId + "." + viewId);
      }

      if (!failOnExist) {
        checkBaseVersion(
            databaseId, viewId, requestBody.getBaseMetadataLocation(), prepared, viewAlreadyExists);
      }

      ViewCommitResult result =
          commitView(requestBody, prepared, actingPrincipal, !viewAlreadyExists);
      ViewDto dto = toCommittedDto(databaseId, viewId, result);
      return Pair.of(withClusterId(dto), result.isCreated());
    } catch (RuntimeException e) {
      throw ViewFailureTranslator.translateWriteFailureTo(e);
    }
  }

  @Override
  public void deleteView(String databaseId, String viewId, String actingPrincipal) {
    try {
      requireEnabled(databaseId);

      PreparedViewOperation prepared =
          houseTableRepository
              .findViewById(primaryKey(databaseId, viewId))
              .map(PreparedViewOperation::view)
              .orElseGet(PreparedViewOperation::observedAbsence);
      if (!prepared.getViewBaseRow().isPresent()) {
        throw noSuchView(databaseId, viewId);
      }
      authorizationUtils.checkTablePrivilege(
          authorizationReference(prepared.getViewBaseRow().get()),
          actingPrincipal,
          privilegeMapper.forDelete());
      deleteCapturedView(databaseId, viewId);
    } catch (RuntimeException e) {
      throw ViewFailureTranslator.translateWriteFailureTo(e);
    }
  }

  /**
   * The object resource of a captured view for authorization: its persisted identifiers and exact
   * metadata pointer, with no metadata read.
   */
  /**
   * {@link #authorizationReference}, with the view's UUID, for the Iceberg REST view routes that
   * decide on it themselves (the load, and the commit's own read and replace decisions and reads).
   * The row has no UUID (House Tables keeps none); the view's is in the name of the directory its
   * metadata lies in ({@link AllocatedDirectory}). With no UUID there, the reference names none.
   *
   * <p>The native view replace and drop keep {@link #authorizationReference}, which names no UUID,
   * as LinkedIn main does; the Iceberg REST view commit and drop reach them ({@code updateView},
   * {@code deleteView}), so those two decisions name no UUID on the Iceberg REST routes either.
   */
  private static TableDto icebergRestAuthorizationReference(HouseTable viewRow) {
    TableDto reference = authorizationReference(viewRow);
    return reference
        .toBuilder()
        .tableUUID(
            AllocatedDirectory.uuidOf(viewRow.getTableLocation())
                .map(java.util.UUID::toString)
                .orElse(reference.getTableUUID()))
        .build();
  }

  private static TableDto authorizationReference(HouseTable viewRow) {
    return TableDto.builder()
        .databaseId(viewRow.getDatabaseId())
        .tableId(viewRow.getTableId())
        .tableUUID(viewRow.getTableUUID())
        .tableLocation(viewRow.getTableLocation())
        .build();
  }

  private void deleteCapturedView(String databaseId, String viewId) {
    boolean dropped;
    try {
      dropped = houseTableRepository.deleteViewById(primaryKey(databaseId, viewId));
    } catch (HouseTableRepositoryStateUnknownException e) {
      throw new CommitStateUnknownException(e);
    }
    if (!dropped) {
      // The view was dropped or replaced by a non-view after capture: no retry, same as absent.
      throw noSuchView(databaseId, viewId);
    }
  }

  private ViewCommitResult commitView(
      CreateUpdateViewRequestBody requestBody,
      PreparedViewOperation prepared,
      String actingPrincipal,
      boolean isCreate) {
    Map<String, String> requestProperties = requestBody.getViewProperties();
    String sourceDialect = ReservedViewProperties.sourceDialect(requestProperties);
    Map<String, String> properties = new LinkedHashMap<>();
    if (requestProperties != null) {
      requestProperties.forEach(
          (key, value) -> {
            if (!isCreate || !ReservedViewProperties.SOURCE_DIALECT.equalsIgnoreCase(key)) {
              properties.put(key, value);
            }
          });
    }
    String viewUuid = null;
    String viewLocation = null;
    String storageType = null;
    if (isCreate) {
      viewUuid = UUID.randomUUID().toString();
      Storage storage =
          storageSelector.selectStorage(requestBody.getDatabaseId(), requestBody.getViewId());
      storageType = storage.getType().getValue();
      // The bare allocated root, passed verbatim: the engine stores it as ViewMetadata.location()
      // and lays metadata files out flat directly under it, per its frozen contract.
      viewLocation =
          storage.allocateTableLocation(
              requestBody.getDatabaseId(),
              requestBody.getViewId(),
              viewUuid,
              actingPrincipal,
              properties);
    }
    Schema schema = IcebergSchemaHelper.getSchemaFromSchemaJson(requestBody.getSchema());
    return viewCommitEngine.commit(
        ViewCommitIntent.builder()
            .databaseId(requestBody.getDatabaseId())
            .viewId(requestBody.getViewId())
            .schema(schema)
            .representations(toEngineRepresentations(requestBody.getRepresentations()))
            .sourceDialect(sourceDialect)
            .defaultCatalog(requestBody.getDefaultCatalog())
            .defaultNamespace(toNamespace(requestBody.getDefaultNamespace()))
            .viewProperties(properties)
            .creator(actingPrincipal)
            .isCreate(isCreate)
            .baseRow(prepared.getViewBaseRow().orElse(null))
            .viewUuid(viewUuid)
            .viewLocation(viewLocation)
            .storageType(storageType)
            .build());
  }

  private void checkBaseVersion(
      String databaseId,
      String viewId,
      String baseToken,
      PreparedViewOperation prepared,
      boolean viewAlreadyExists) {
    if (!viewAlreadyExists) {
      if (!ValidatorConstants.INITIAL_TABLE_VERSION.equals(baseToken)) {
        throw new ViewApiException(
            ViewErrorCode.CONCURRENT_VIEW_MODIFICATION,
            "Stale base version for absent view: " + databaseId + "." + viewId);
      }
      return;
    }
    String currentPointer = prepared.getViewBaseRow().get().getTableLocation();
    if (!currentPointer.equals(baseToken)) {
      throw new ViewApiException(
          ViewErrorCode.CONCURRENT_VIEW_MODIFICATION,
          "Concurrent view modification: " + databaseId + "." + viewId);
    }
  }

  private void requireEnabled(String databaseId) {
    if (!featureGate.isEnabled(databaseId)) {
      throw new ViewApiException(ViewErrorCode.VIEWS_DISABLED, VIEWS_DISABLED_MESSAGE);
    }
  }

  /**
   * A database has no row of its own; it exists once it holds a table or a view. Tables are probed
   * first because nearly every database holds one, so the view probe runs only on a miss. Only the
   * route database is probed: {@code defaultNamespace} is SQL-resolution metadata with no linkage
   * inside OpenHouse, so it is passed through unchecked.
   */
  private void requireDatabaseExists(String databaseId) {
    if (houseTableRepository.findAllByDatabaseId(databaseId, SINGLE_ROW).hasContent()
        || houseTableRepository.findAllViewsByDatabaseId(databaseId, SINGLE_ROW).hasContent()) {
      return;
    }
    throw new ViewApiException(
        ViewErrorCode.DATABASE_NOT_FOUND, "Database not found: " + databaseId);
  }

  private ViewDto withClusterId(ViewDto dto) {
    return dto.toBuilder().clusterId(clusterProperties.getClusterName()).build();
  }

  private Page<ViewDto> sourcePage(String databaseId, Pageable pageable) {
    Page<HouseTable> rows = houseTableRepository.findAllViewsByDatabaseId(databaseId, pageable);
    List<ViewDto> dtos =
        rows.getContent().stream()
            .map(
                row ->
                    ViewDto.builder()
                        .viewId(row.getTableId())
                        .databaseId(row.getDatabaseId())
                        .build())
            .collect(Collectors.toList());
    return new PageImpl<>(dtos, pageable, rows.getTotalElements());
  }

  private static List<SqlViewRepresentationIntent> toEngineRepresentations(
      List<ViewRepresentation> representations) {
    if (representations == null) {
      return Collections.emptyList();
    }
    return representations.stream()
        .map(
            representation ->
                SqlViewRepresentationIntent.builder()
                    .sql(representation.getSql())
                    .dialect(representation.getDialect())
                    .build())
        .collect(Collectors.toList());
  }

  private static Namespace toNamespace(List<String> defaultNamespace) {
    if (defaultNamespace == null || defaultNamespace.isEmpty()) {
      return Namespace.empty();
    }
    return Namespace.of(defaultNamespace.toArray(new String[0]));
  }

  private static ViewDto toCommittedDto(String databaseId, String viewId, ViewCommitResult result) {
    ViewPointer pointer = result.getPointer();
    return ViewDto.builder()
        .databaseId(databaseId)
        .viewId(viewId)
        .metadataLocation(pointer.getMetadataLocation())
        .viewVersion(pointer.getMetadataLocation())
        .viewCreator(result.getViewCreator())
        .creationTime(pointer.getCreationTime())
        .lastModifiedTime(result.getLastModifiedTime())
        .build();
  }

  /**
   * HTS stores only the pointer; creator and modification time live in the view metadata, whose
   * properties are passed here.
   */
  private static ViewDto toPointerDto(HouseTable row, Map<String, String> properties) {
    String creator = properties.get(getCanonicalFieldName("tableCreator"));
    if (StringUtils.isBlank(creator)) {
      throw new IllegalStateException(
          "Corrupt view metadata for " + row.getDatabaseId() + "." + row.getTableId());
    }
    String lastModifiedTime = properties.get(getCanonicalFieldName("lastModifiedTime"));
    return ViewDto.builder()
        .viewId(row.getTableId())
        .databaseId(row.getDatabaseId())
        .metadataLocation(row.getTableLocation())
        .viewVersion(row.getTableLocation())
        .viewCreator(creator)
        .creationTime(row.getCreationTime())
        .lastModifiedTime(lastModifiedTime == null ? 0L : Long.parseLong(lastModifiedTime))
        .build();
  }

  /**
   * A valid persisted VIEW row always carries both facts; requiring both is this completeness
   * invariant, not an optional new field. Corrupt persisted metadata is a sanitized server fault,
   * never reinterpreted as absence. Checked on the HTS row before any metadata is read.
   */
  private static void requireCompletePersistedPointer(HouseTable row) {
    if (StringUtils.isBlank(row.getTableLocation()) || StringUtils.isBlank(row.getStorageType())) {
      throw new IllegalStateException(
          "Corrupt view pointer for " + row.getDatabaseId() + "." + row.getTableId());
    }
  }

  private static HouseTablePrimaryKey primaryKey(String databaseId, String viewId) {
    return HouseTablePrimaryKey.builder().databaseId(databaseId).tableId(viewId).build();
  }

  private static ViewApiException noSuchView(String databaseId, String viewId) {
    return new ViewApiException(
        ViewErrorCode.NO_SUCH_VIEW, "No such view: " + databaseId + "." + viewId);
  }
}
