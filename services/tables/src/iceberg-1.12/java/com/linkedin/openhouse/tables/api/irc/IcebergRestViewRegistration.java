package com.linkedin.openhouse.tables.api.irc;

import static com.linkedin.openhouse.internal.catalog.CatalogConstants.ENTITY_TYPE_VIEW;
import static com.linkedin.openhouse.internal.catalog.mapper.HouseTableSerdeUtils.getCanonicalFieldName;

import com.linkedin.openhouse.cluster.storage.Storage;
import com.linkedin.openhouse.cluster.storage.selector.StorageSelector;
import com.linkedin.openhouse.internal.catalog.CatalogConstants;
import com.linkedin.openhouse.internal.catalog.HouseTableRowLimits;
import com.linkedin.openhouse.internal.catalog.fileio.FileIOManager;
import com.linkedin.openhouse.internal.catalog.model.HouseTable;
import com.linkedin.openhouse.internal.catalog.model.HouseTablePrimaryKey;
import com.linkedin.openhouse.internal.catalog.repository.HouseTableRepository;
import com.linkedin.openhouse.internal.catalog.repository.exception.HouseTableConcurrentUpdateException;
import com.linkedin.openhouse.internal.catalog.repository.exception.HouseTableRepositoryStateUnknownException;
import com.linkedin.openhouse.internal.catalog.view.ViewMetadataCodec;
import com.linkedin.openhouse.internal.catalog.view.ViewSystemProperties;
import com.linkedin.openhouse.tables.api.spec.v0.request.CreateUpdateViewRequestBody;
import com.linkedin.openhouse.tables.api.validator.ViewsApiValidator;
import com.linkedin.openhouse.tables.audit.ViewAuditAspect;
import com.linkedin.openhouse.tables.exception.ViewApiException;
import com.linkedin.openhouse.tables.exception.ViewErrorCode;
import com.linkedin.openhouse.tables.exception.ViewFailureTranslator;
import com.linkedin.openhouse.tables.model.ReservedViewProperties;
import com.linkedin.openhouse.tables.services.ViewWritePrivilegeMapper;
import com.linkedin.openhouse.tables.services.ViewsFeatureGate;
import com.linkedin.openhouse.tables.utils.AuthorizationUtils;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.apache.iceberg.SchemaParser;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.CommitStateUnknownException;
import org.apache.iceberg.exceptions.NotFoundException;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.rest.responses.ImmutableLoadViewResponse;
import org.apache.iceberg.rest.responses.LoadViewResponse;
import org.apache.iceberg.view.ViewMetadata;
import org.apache.iceberg.view.ViewVersion;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

/**
 * The IRC view registration ({@code POST .../register-view}): the view row is stored pointing at
 * the metadata file the request names, in place. Nothing is written to storage, and the view's
 * UUID, location, versions, history and properties are the file's.
 *
 * <p>It runs what the native view create runs before it writes, in the same order and with the same
 * answers: the native id validation, the views gate (404 "Views are disabled"), the database (404),
 * the name's occupant, the create privilege on the database (403), then a view (409) or a table
 * (409) of that name. Then the file must be one OpenHouse wrote for a view of that name in that
 * database:
 *
 * <ul>
 *   <li>a {@code .metadata.json} file directly in the directory the server allocates to the view
 *       for the UUID in the directory's name ({@code <view>-<uuid>}), as the table register
 *       requires of a table, spelled as the engine stores it (as the load route returns it);
 *   <li>no House Tables row (a table, live or soft-deleted, or a view) points into that directory
 *       or has that UUID, as for the table register: a table renamed away keeps its directory, and
 *       a soft-deleted one can be restored into it;
 *   <li>view metadata, whose location is that directory and whose UUID is that UUID, and whose
 *       OpenHouse properties name this view (database, name, UUID), this file as its own pointer,
 *       its creator, source dialect and times, without a case-duplicated OpenHouse key, as the view
 *       commit engine writes them. So the view can be loaded and replaced like one the engine
 *       created;
 *   <li>its current version passes the native create's validation, and its location fits the House
 *       Tables row, as the engine requires of a create.
 * </ul>
 *
 * The file's content is trusted only as far as these checks go: that nobody but the service writes
 * into a view's directory rests on storage permissions. Older files of a dropped view register too
 * (decision B). A refusal of the file is 400 {@code BadRequestException} and does not tell what the
 * file holds. The row is written once, as the engine writes a create's: a concurrent create of the
 * name is 409, naming the kind that won. The registration is audited as a CREATE by the caller
 * ({@link ViewAuditAspect#auditCreate}), and the view keeps the creator its file records.
 *
 * <p>Compiled on the Iceberg 1.12 line only, with its route ({@link
 * IcebergRestRegisterViewController}).
 */
@Component
public class IcebergRestViewRegistration {

  private static final String METADATA_FILE_SUFFIX = ".metadata.json";

  private static final int PAGE_SIZE = 100;

  private static final Pageable SINGLE_ROW = PageRequest.of(0, 1);

  @Autowired private ViewsApiValidator viewsApiValidator;

  @Autowired private ViewsFeatureGate viewsFeatureGate;

  @Autowired private HouseTableRepository houseTableRepository;

  @Autowired private AuthorizationUtils authorizationUtils;

  @Autowired private ViewWritePrivilegeMapper privilegeMapper;

  @Autowired private StorageSelector storageSelector;

  @Autowired private FileIOManager fileIOManager;

  @Autowired private ViewMetadataCodec viewMetadataCodec;

  @Autowired private ViewAuditAspect viewAudit;

  @Autowired private ObjectProvider<HouseTableRowLimits> houseTableRowLimits;

  public LoadViewResponse registerView(
      String namespace, String viewId, String metadataLocation, String actingPrincipal) {
    String databaseId = IcebergRestApiHandler.databaseId(namespace);
    // Audited as a native create is: a CREATE by the caller, success or failure (decision A).
    return IcebergRestViewHandler.onNamespace(
        () ->
            viewAudit.auditCreate(
                databaseId,
                viewId,
                () -> register(databaseId, viewId, metadataLocation, actingPrincipal),
                LoadViewResponse::metadataLocation));
  }

  /**
   * Checks, then writes the row; answers the metadata already read, which is what a load of the row
   * reads (the file at the row's pointer), so nothing can fail after the row is written.
   */
  private LoadViewResponse register(
      String databaseId, String viewId, String metadataLocation, String actingPrincipal) {
    viewsApiValidator.validateGetView(databaseId, viewId);
    TableIdentifier identifier = TableIdentifier.of(databaseId, viewId);
    try {
      // The native create's order (ViewsServiceImpl.putView): gate, database, capture, privilege,
      // then the name.
      if (!viewsFeatureGate.isEnabled(databaseId)) {
        throw new ViewApiException(ViewErrorCode.VIEWS_DISABLED, "Views are disabled");
      }
      if (!houseTableRepository.findAllByDatabaseId(databaseId, SINGLE_ROW).hasContent()
          && !houseTableRepository.findAllViewsByDatabaseId(databaseId, SINGLE_ROW).hasContent()) {
        throw new ViewApiException(
            ViewErrorCode.DATABASE_NOT_FOUND, "Database not found: " + databaseId);
      }
      Optional<HouseTable> occupant = houseTableRepository.findEntityById(key(databaseId, viewId));
      authorizationUtils.checkDatabasePrivilege(
          databaseId, actingPrincipal, privilegeMapper.forCreate());
      if (occupant.isPresent()) {
        throw ENTITY_TYPE_VIEW.equals(occupant.get().getEntityType())
            ? new ViewApiException(
                ViewErrorCode.VIEW_ALREADY_EXISTS, "View already exists: " + identifier)
            : new ViewApiException(
                ViewErrorCode.NAME_ALREADY_EXISTS_AS_TABLE,
                "Name already exists as a table: " + identifier);
      }

      Storage storage = storageSelector.selectStorage(databaseId, viewId);
      String directory = allocatedDirectory(identifier, storage, metadataLocation, actingPrincipal);
      UUID uuid = uuidOf(identifier, directory);
      refuseADirectoryInUse(identifier, storage, directory, uuid);
      ViewMetadata metadata = read(identifier, storage, metadataLocation);
      requireWrittenForThisView(identifier, storage, directory, uuid, metadataLocation, metadata);
      requireWhatACreateAccepts(identifier, metadata);
      houseTableRowLimits
          .getIfAvailable(() -> HouseTableRowLimits.DEFAULT)
          .requireMetadataLocationFits("View " + identifier, metadataLocation);
      save(identifier, storage, uuid, metadataLocation, metadata);
      return ImmutableLoadViewResponse.builder()
          .metadataLocation(metadataLocation)
          .metadata(metadata)
          .build();
    } catch (ViewApiException e) {
      throw e;
    } catch (RuntimeException e) {
      throw ViewFailureTranslator.translateWriteFailureTo(e);
    }
  }

  /**
   * The directory {@code metadataLocation} lies in, without the storage's endpoint, if that is the
   * directory the server allocates to this view for the UUID its name carries; refused otherwise,
   * before anything is read.
   */
  private static String allocatedDirectory(
      TableIdentifier identifier,
      Storage storage,
      String metadataLocation,
      String actingPrincipal) {
    String path = withoutEndpoint(storage, metadataLocation);
    int fileAt = path.lastIndexOf('/');
    if (fileAt > 0 && path.endsWith(METADATA_FILE_SUFFIX)) {
      String directory = path.substring(0, fileAt);
      String directoryName = directory.substring(directory.lastIndexOf('/') + 1);
      String prefix = identifier.name() + "-";
      if (directoryName.startsWith(prefix)) {
        try {
          UUID uuid = UUID.fromString(directoryName.substring(prefix.length()));
          String allocated =
              storage.allocateTableLocation(
                  identifier.namespace().toString(),
                  identifier.name(),
                  uuid.toString(),
                  actingPrincipal,
                  new HashMap<>());
          if (directory.equals(withoutEndpoint(storage, allocated))) {
            String stored = allocated + path.substring(fileAt);
            if (!stored.equals(metadataLocation)) {
              // The same file spelled otherwise (with or without the storage's endpoint): the row
              // keeps the spelling the engine stores, which is the load route's.
              throw refused(
                  identifier,
                  "%s is not spelled as OpenHouse stores it: name the file as the view's load "
                      + "returned it, %s",
                  metadataLocation,
                  stored);
            }
            return directory;
          }
        } catch (IllegalArgumentException e) {
          // not a UUID: refused below
        }
      }
    }
    throw refused(
        identifier,
        "%s is not a metadata file in a directory OpenHouse assigned to a view of that name in "
            + "that database: OpenHouse assigns the location of a view",
        metadataLocation);
  }

  private static UUID uuidOf(TableIdentifier identifier, String directory) {
    String directoryName = directory.substring(directory.lastIndexOf('/') + 1);
    return UUID.fromString(directoryName.substring(identifier.name().length() + 1));
  }

  /** {@code location} as the house-table store spells it: without the storage's endpoint. */
  private static String withoutEndpoint(Storage storage, String location) {
    String endpoint = storage.getClient().getEndpoint();
    return endpoint != null && !endpoint.isEmpty() && location.startsWith(endpoint)
        ? location.substring(endpoint.length())
        : location;
  }

  /**
   * Refuses a directory a house-table row of the database points into, or whose UUID is the one the
   * directory of a row's location is named for ({@code IcebergRestWriteHandler.namesUuid}): a
   * table, live or soft-deleted (as the table register refuses it), or a view. A row's drop does
   * not free the directory for another view, and two rows sharing a directory would diverge on
   * their next write. Existing lookups only: the database's table rows, its soft-deleted tables and
   * its views, page by page.
   */
  private void refuseADirectoryInUse(
      TableIdentifier identifier, Storage storage, String directory, UUID uuid) {
    String databaseId = identifier.namespace().toString();
    for (HouseTable row : houseTableRepository.findAllByDatabaseId(databaseId)) {
      refuseIfUses(identifier, row, storage, directory, uuid, "");
    }
    for (int page = 0; ; page++) {
      List<HouseTable> rows =
          houseTableRepository
              .searchSoftDeletedTables(databaseId, null, PageRequest.of(page, PAGE_SIZE))
              .getContent();
      for (HouseTable row : rows) {
        refuseIfUses(identifier, row, storage, directory, uuid, "the soft-deleted table ");
      }
      if (rows.size() < PAGE_SIZE) {
        break;
      }
    }
    for (int page = 0; ; page++) {
      List<HouseTable> rows =
          houseTableRepository
              .findAllViewsByDatabaseId(databaseId, PageRequest.of(page, PAGE_SIZE))
              .getContent();
      for (HouseTable row : rows) {
        refuseIfUses(identifier, row, storage, directory, uuid, "");
      }
      if (rows.size() < PAGE_SIZE) {
        break;
      }
    }
  }

  private static void refuseIfUses(
      TableIdentifier identifier,
      HouseTable row,
      Storage storage,
      String directory,
      UUID uuid,
      String kind) {
    String location = row.getTableLocation();
    String stored = location == null ? null : withoutEndpoint(storage, location);
    if (stored != null
        && (stored.startsWith(directory + "/")
            || IcebergRestWriteHandler.namesUuid(stored, uuid))) {
      throw refused(
          identifier,
          "that directory belongs to %s%s.%s",
          kind,
          row.getDatabaseId(),
          row.getTableId());
    }
  }

  /** Reads the file as view metadata; a failure is told without the file's content. */
  private ViewMetadata read(TableIdentifier identifier, Storage storage, String metadataLocation) {
    FileIO io = fileIOManager.getFileIO(storage.getType());
    try {
      return viewMetadataCodec.read(io.newInputFile(metadataLocation));
    } catch (NotFoundException e) {
      throw refused(identifier, "there is no file at %s", metadataLocation);
    } catch (RuntimeException e) {
      throw refused(
          identifier, "the file at %s is not view metadata this server reads", metadataLocation);
    }
  }

  /**
   * Refuses metadata that is not what the view commit engine wrote for this view in this directory,
   * at this file: the view would not load or replace as the engine's views do.
   */
  private static void requireWrittenForThisView(
      TableIdentifier identifier,
      Storage storage,
      String directory,
      UUID uuid,
      String metadataLocation,
      ViewMetadata metadata) {
    Map<String, String> properties = metadata.properties();
    String mismatch = null;
    if (!uuid.toString().equals(metadata.uuid())) {
      mismatch = "its UUID is not the one its directory is named for";
    } else if (metadata.location() == null
        || !directory.equals(withoutEndpoint(storage, metadata.location()))) {
      mismatch = "its location is not the directory it lies in";
    } else if (!uuid.toString().equals(properties.get(getCanonicalFieldName("tableUUID")))
        || !identifier
            .namespace()
            .toString()
            .equals(properties.get(getCanonicalFieldName("databaseId")))
        || !identifier.name().equals(properties.get(getCanonicalFieldName("tableId")))) {
      mismatch = "its OpenHouse properties name another view";
    } else if (!metadataLocation.equals(properties.get(getCanonicalFieldName("tableLocation")))) {
      mismatch = "its OpenHouse properties name another file as the view's metadata";
    } else if (isBlank(properties.get(getCanonicalFieldName("tableCreator")))) {
      mismatch = "it records no creator";
    } else if (isBlank(properties.get(ViewSystemProperties.SOURCE_DIALECT))) {
      mismatch = "it records no source dialect";
    } else if (!isLong(properties.get(getCanonicalFieldName("creationTime")))) {
      mismatch = "its creation time is not a number";
    } else if (properties.get(getCanonicalFieldName("lastModifiedTime")) != null
        && !isLong(properties.get(getCanonicalFieldName("lastModifiedTime")))) {
      // The view's load reads it as a number.
      mismatch = "its last modified time is not a number";
    } else {
      Set<String> systemKeys = new HashSet<>();
      for (String key : properties.keySet()) {
        if (ViewSystemProperties.isSystemProperty(key)
            && !systemKeys.add(key.toLowerCase(Locale.ROOT))) {
          // Every later replace of such a view fails the engine's system-property check.
          mismatch = "it holds the OpenHouse property " + key + " twice, in different cases";
          break;
        }
      }
    }
    if (mismatch != null) {
      throw refused(
          identifier,
          "the metadata at %s is not what OpenHouse wrote for this view: %s",
          metadataLocation,
          mismatch);
    }
  }

  /**
   * Runs the native create's validation ({@link ViewsApiValidator#validateCreateView}: schema,
   * dialects and their uniqueness, representations, default namespace, properties, source dialect)
   * on the create that would make the file's current version, so a registration admits no view a
   * create would refuse. The engine's own create checks (duplicate dialects, server-owned keys,
   * required inputs) are within it or above; the location's length is checked by the caller. Older
   * versions are history, not checked.
   */
  private void requireWhatACreateAccepts(TableIdentifier identifier, ViewMetadata metadata) {
    ViewVersion version = metadata.currentVersion();
    Map<String, String> properties = new LinkedHashMap<>();
    metadata
        .properties()
        .forEach(
            (key, value) -> {
              if (!ViewSystemProperties.isSystemProperty(key)) {
                properties.put(key, value);
              }
            });
    properties.put(
        ReservedViewProperties.SOURCE_DIALECT,
        metadata.properties().get(ViewSystemProperties.SOURCE_DIALECT));
    CreateUpdateViewRequestBody body =
        CreateUpdateViewRequestBody.builder()
            .viewId(identifier.name())
            .databaseId(identifier.namespace().toString())
            .schema(SchemaParser.toJson(metadata.schema(), false))
            .representations(IcebergRestViewSpelling.representations(version))
            .defaultCatalog(version.defaultCatalog())
            .defaultNamespace(IcebergRestViewSpelling.levels(version.defaultNamespace()))
            .viewProperties(properties)
            .build();
    try {
      viewsApiValidator.validateCreateView(body);
    } catch (ViewApiException e) {
      throw new ViewApiException(
          e.getErrorCode(),
          String.format(
              "Cannot register view %s: its current version is not one a create accepts: %s",
              identifier, e.getMessage()));
    }
  }

  /** The row the view commit engine writes for a create, pointing at the registered file. */
  private void save(
      TableIdentifier identifier,
      Storage storage,
      UUID uuid,
      String metadataLocation,
      ViewMetadata metadata) {
    Map<String, String> properties = metadata.properties();
    HouseTable row =
        HouseTable.builder()
            .databaseId(identifier.namespace().toString())
            .tableId(identifier.name())
            .tableUUID(uuid.toString())
            .tableVersion(CatalogConstants.INITIAL_VERSION)
            .tableLocation(metadataLocation)
            .tableCreator(properties.get(getCanonicalFieldName("tableCreator")))
            .creationTime(Long.parseLong(properties.get(getCanonicalFieldName("creationTime"))))
            .storageType(storage.getType().getValue())
            .build();
    try {
      houseTableRepository.saveView(row);
    } catch (HouseTableConcurrentUpdateException e) {
      // A create of the name landed since the capture: say which kind won, as the capture would.
      Optional<HouseTable> winner = Optional.empty();
      try {
        winner = houseTableRepository.findEntityById(key(row.getDatabaseId(), row.getTableId()));
      } catch (RuntimeException lookup) {
        // unknown kind: the neutral answer below
      }
      if (winner.filter(w -> !ENTITY_TYPE_VIEW.equals(w.getEntityType())).isPresent()) {
        throw new ViewApiException(
            ViewErrorCode.NAME_ALREADY_EXISTS_AS_TABLE,
            "Name already exists as a table: " + identifier);
      }
      throw new ViewApiException(
          ViewErrorCode.VIEW_ALREADY_EXISTS,
          (winner.isPresent() ? "View already exists: " : "Name already exists: ") + identifier);
    } catch (HouseTableRepositoryStateUnknownException e) {
      throw ViewFailureTranslator.translateWriteFailureTo(new CommitStateUnknownException(e));
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }

  private static boolean isLong(String value) {
    try {
      Long.parseLong(value);
      return true;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  private static HouseTablePrimaryKey key(String databaseId, String viewId) {
    return HouseTablePrimaryKey.builder().databaseId(databaseId).tableId(viewId).build();
  }

  /**
   * A refusal of the file: 400, as a create's invalid definition (so the view audit names it as
   * such), with the route's message.
   */
  private static ViewApiException refused(
      TableIdentifier identifier, String reason, Object... args) {
    return new ViewApiException(
        ViewErrorCode.INVALID_VIEW_DEFINITION,
        String.format("Cannot register view %s: %s", identifier, String.format(reason, args)));
  }
}
