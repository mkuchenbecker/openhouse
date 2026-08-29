package com.linkedin.openhouse.tables.e2e.h2;

import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.internal.catalog.model.HouseTable;
import com.linkedin.openhouse.internal.catalog.model.HouseTablePrimaryKey;
import com.linkedin.openhouse.internal.catalog.repository.HouseTableRepository;
import com.linkedin.openhouse.tables.exception.ViewCommitConflictException;
import com.linkedin.openhouse.tables.exception.ViewNameConflictException;
import com.linkedin.openhouse.tables.mock.properties.AuthorizationPropertiesInitializer;
import com.linkedin.openhouse.tables.model.IcebergRestViewFixtures;
import com.linkedin.openhouse.tables.model.ViewCreationRequest;
import com.linkedin.openhouse.tables.model.ViewIdentifiersPage;
import com.linkedin.openhouse.tables.model.ViewPageRequest;
import com.linkedin.openhouse.tables.services.ViewsService;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.apache.iceberg.MetadataUpdate;
import org.apache.iceberg.UpdateRequirement;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.view.ImmutableViewVersion;
import org.apache.iceberg.view.SQLViewRepresentation;
import org.apache.iceberg.view.ViewMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

/**
 * A view survives a round trip: created, read back, listed, and dropped.
 *
 * <p>This is the first test in the tree that stores a view. Everything before it asserted that the
 * wire surface answers correctly while the backend refused, so it could not distinguish a correct
 * implementation from an absent one.
 *
 * <p>{@code cluster.tables.views.enabled=true} is set here and nowhere else. The default is off, so
 * every other test in this module keeps meeting {@code ViewsDisabledService} and its assertions
 * about the disabled posture stay meaningful.
 */
@SpringBootTest
@ContextConfiguration(
    initializers = {
      PropertyOverrideContextInitializer.class,
      AuthorizationPropertiesInitializer.class
    })
@TestPropertySource(properties = "cluster.tables.views.enabled=true")
public class ViewPersistenceTest {

  private static final String PRINCIPAL = "test_user";
  private static final TableIdentifier IDENTIFIER =
      TableIdentifier.of(IcebergRestViewFixtures.DATABASE_ID, IcebergRestViewFixtures.VIEW_ID);

  @Autowired ViewsService viewsService;

  /** Used only to plant a table row at a contested key; the view path never touches it. */
  @Autowired HouseTableRepository houseTableRepository;

  @AfterEach
  void clearTheIdentifier() {
    // The context is cached across methods, so anything left at IDENTIFIER would make the next
    // test's "does not exist yet" assertion fail for the wrong reason.
    viewsService.dropView(IDENTIFIER, PRINCIPAL);
    HouseTablePrimaryKey key =
        HouseTablePrimaryKey.builder()
            .databaseId(IcebergRestViewFixtures.DATABASE_ID)
            .tableId(IcebergRestViewFixtures.VIEW_ID)
            .build();
    houseTableRepository.findById(key).ifPresent(houseTableRepository::delete);
  }

  private static ViewCreationRequest creationRequest() {
    return ViewCreationRequest.builder()
        .identifier(IDENTIFIER)
        .schema(IcebergRestViewFixtures.SCHEMA)
        .requestedVersion(IcebergRestViewFixtures.viewVersion())
        .properties(Collections.singletonMap("owner", PRINCIPAL))
        .build();
  }

  @Test
  public void theConfiguredServiceIsTheOneThatStoresViews() {
    Assertions.assertEquals(
        "OpenHouseViewsService",
        viewsService.getClass().getSimpleName(),
        "cluster.tables.views.enabled=true must select the storing service; if this fails every"
            + " other assertion here is testing the disabled stub instead");
  }

  @Test
  public void aCreatedViewIsReadBackWithItsQueryAndSchema() throws Exception {
    Assertions.assertFalse(
        viewsService.viewExists(IDENTIFIER, PRINCIPAL), "the view must not exist before creation");

    ViewMetadata created = viewsService.createView(creationRequest(), PRINCIPAL);
    Assertions.assertNotNull(created.uuid(), "the server assigns the view UUID");

    Optional<ViewMetadata> loaded = viewsService.loadView(IDENTIFIER, PRINCIPAL);
    Assertions.assertTrue(loaded.isPresent(), "the created view must load");

    ViewMetadata metadata = loaded.get();
    Assertions.assertEquals(
        created.uuid(), metadata.uuid(), "load must return the view that was created");
    Assertions.assertEquals(
        IcebergRestViewFixtures.SCHEMA.asStruct(),
        metadata.schema().asStruct(),
        "the schema must survive the round trip");

    SQLViewRepresentation representation =
        (SQLViewRepresentation) metadata.currentVersion().representations().get(0);
    Assertions.assertEquals(
        IcebergRestViewFixtures.VIEW_SQL,
        representation.sql(),
        "the query text must survive the round trip");
    Assertions.assertEquals(IcebergRestViewFixtures.SOURCE_DIALECT, representation.dialect());
  }

  @Test
  public void theStoredMetadataLivesInTheViewRootUncompressed() throws Exception {
    viewsService.createView(creationRequest(), PRINCIPAL);
    ViewMetadata metadata = viewsService.loadView(IDENTIFIER, PRINCIPAL).get();

    String location = metadata.metadataFileLocation();
    Assertions.assertTrue(
        location.endsWith(".metadata.json"),
        "OpenHouse writes view metadata uncompressed, not .json.gz: " + location);
    Assertions.assertFalse(
        location.contains("/metadata/"),
        "OpenHouse writes view metadata in the view root, not a metadata/ subdirectory: "
            + location);
    Assertions.assertTrue(
        location.substring(location.lastIndexOf('/') + 1).startsWith("00000-"),
        "the file name must start with the zero-padded version — 00000 for a view's first commit,"
            + " which is how Iceberg parses the version back out on refresh: "
            + location);
    Assertions.assertTrue(
        location.startsWith(metadata.location() + "/"),
        "the metadata file must sit directly under the location the server allocated: " + location);
  }

  @Test
  public void aViewIsListedInItsDatabaseAndNotElsewhere() throws Exception {
    viewsService.createView(creationRequest(), PRINCIPAL);

    ViewIdentifiersPage page =
        viewsService.listViews(
            IcebergRestViewFixtures.DATABASE_ID, ViewPageRequest.unpaged(), PRINCIPAL);
    Assertions.assertTrue(
        page.getIdentifiers().contains(IDENTIFIER), "the created view must appear in its database");
    Assertions.assertFalse(
        page.getNextPageToken().isPresent(),
        "an unpaged request is answered completely, with no continuation token");

    Assertions.assertTrue(
        viewsService
            .listViews("some_other_database", ViewPageRequest.unpaged(), PRINCIPAL)
            .getIdentifiers()
            .isEmpty(),
        "a view must not leak into another database's listing");
  }

  @Test
  public void droppingReportsWhetherThisCallRemovedTheView() throws Exception {
    viewsService.createView(creationRequest(), PRINCIPAL);

    Assertions.assertTrue(
        viewsService.dropView(IDENTIFIER, PRINCIPAL), "the first drop removes the view");
    Assertions.assertFalse(
        viewsService.dropView(IDENTIFIER, PRINCIPAL),
        "a second drop reports false rather than succeeding silently");
    Assertions.assertFalse(
        viewsService.loadView(IDENTIFIER, PRINCIPAL).isPresent(), "a dropped view must not load");
  }

  @Test
  public void creatingTwiceIsANameConflictRatherThanASecondVersion() throws Exception {
    viewsService.createView(creationRequest(), PRINCIPAL);
    Assertions.assertThrows(
        ViewNameConflictException.class,
        () -> viewsService.createView(creationRequest(), PRINCIPAL),
        "a second create on a live identifier is a conflict, not an overwrite");
  }

  @Test
  public void aCommitAddsAVersionAndAdvancesTheStoredMetadataFile() throws Exception {
    ViewMetadata created = viewsService.createView(creationRequest(), PRINCIPAL);
    String firstFile = created.metadataFileLocation();

    String replacementSql = "SELECT id FROM my_database.my_table";
    List<UpdateRequirement> requirements =
        Collections.singletonList(new UpdateRequirement.AssertViewUUID(created.uuid()));
    List<MetadataUpdate> updates =
        Arrays.asList(
            new MetadataUpdate.AddViewVersion(
                ImmutableViewVersion.builder()
                    .from(IcebergRestViewFixtures.viewVersion())
                    .representations(
                        Collections.singletonList(
                            IcebergRestViewFixtures.representation(
                                IcebergRestViewFixtures.SOURCE_DIALECT, replacementSql)))
                    .build()),
            new MetadataUpdate.SetCurrentViewVersion(-1));

    Optional<ViewMetadata> committed =
        viewsService.replaceView(IDENTIFIER, requirements, updates, PRINCIPAL);
    Assertions.assertTrue(committed.isPresent(), "a commit on a live view must return the result");

    ViewMetadata metadata = committed.get();
    Assertions.assertEquals(
        created.uuid(), metadata.uuid(), "a commit replaces the definition, not the view");
    Assertions.assertEquals(
        replacementSql,
        ((SQLViewRepresentation) metadata.currentVersion().representations().get(0)).sql(),
        "the committed query must be the current one");
    Assertions.assertEquals(
        2, metadata.versions().size(), "the superseded version stays in the version list");

    String secondFile = metadata.metadataFileLocation();
    Assertions.assertNotEquals(
        firstFile, secondFile, "a commit writes a new metadata file rather than overwriting one");
    Assertions.assertTrue(
        secondFile.substring(secondFile.lastIndexOf('/') + 1).startsWith("00001-"),
        "the second commit is version 1, which is what a later refresh parses back out: "
            + secondFile);

    Assertions.assertEquals(
        replacementSql,
        ((SQLViewRepresentation)
                viewsService
                    .loadView(IDENTIFIER, PRINCIPAL)
                    .get()
                    .currentVersion()
                    .representations()
                    .get(0))
            .sql(),
        "the commit must be what a fresh load sees, not just what the committer was handed");
  }

  @Test
  public void aCommitAgainstAStaleUuidIsRejectedBeforeAnythingIsWritten() throws Exception {
    ViewMetadata created = viewsService.createView(creationRequest(), PRINCIPAL);

    Assertions.assertThrows(
        ViewCommitConflictException.class,
        () ->
            viewsService.replaceView(
                IDENTIFIER,
                Collections.singletonList(
                    new UpdateRequirement.AssertViewUUID(IcebergRestViewFixtures.VIEW_UUID)),
                Collections.singletonList(
                    new MetadataUpdate.SetProperties(
                        Collections.singletonMap("comment", "should not be applied"))),
                PRINCIPAL),
        "a requirement naming a different view must fail the commit");

    ViewMetadata reloaded = viewsService.loadView(IDENTIFIER, PRINCIPAL).get();
    Assertions.assertEquals(
        created.metadataFileLocation(),
        reloaded.metadataFileLocation(),
        "a rejected commit must leave the stored view exactly as it was");
    Assertions.assertFalse(
        reloaded.properties().containsKey("comment"),
        "requirements are checked before updates are applied, so nothing lands");
  }

  @Test
  public void committingToAnAbsentViewReportsAbsenceRatherThanCreating() throws Exception {
    Assertions.assertFalse(
        viewsService
            .replaceView(IDENTIFIER, Collections.emptyList(), Collections.emptyList(), PRINCIPAL)
            .isPresent(),
        "a commit is not a create: an absent view is a 404, not a new view");
    Assertions.assertFalse(
        viewsService.viewExists(IDENTIFIER, PRINCIPAL),
        "and the failed commit must not have left a view behind");
  }

  /**
   * Views and tables share one House Tables key space, so a name can be taken by the other kind.
   *
   * <p>The view path reads and writes through House Tables' view-scoped methods, which treat a
   * table at the key as absent — so without this check a create would sail past its own
   * already-exists test and plant a second row on a key a table already holds.
   */
  @Test
  public void creatingAViewOverATableIsAConflictNamingTheTable() {
    houseTableRepository.save(
        HouseTable.builder()
            .databaseId(IcebergRestViewFixtures.DATABASE_ID)
            .tableId(IcebergRestViewFixtures.VIEW_ID)
            .tableUUID(IcebergRestViewFixtures.VIEW_UUID)
            .tableVersion("INITIAL_VERSION")
            .tableLocation("/tmp/my_database/my_view-planted/00000-planted.metadata.json")
            .storageType("local")
            .creationTime(1L)
            .lastModifiedTime(1L)
            .build());

    ViewNameConflictException conflict =
        Assertions.assertThrows(
            ViewNameConflictException.class,
            () -> viewsService.createView(creationRequest(), PRINCIPAL),
            "the name is taken, whichever kind of entity took it");
    Assertions.assertEquals(
        ViewNameConflictException.Kind.TABLE,
        conflict.getKind(),
        "a client debugging a failed create needs to know it collided with a table");
  }

  @Test
  public void aViewIsNotVisibleThroughTheTablePath() throws Exception {
    viewsService.createView(creationRequest(), PRINCIPAL);

    Assertions.assertFalse(
        houseTableRepository
            .findById(
                HouseTablePrimaryKey.builder()
                    .databaseId(IcebergRestViewFixtures.DATABASE_ID)
                    .tableId(IcebergRestViewFixtures.VIEW_ID)
                    .build())
            .isPresent(),
        "a table-scoped read must not see a view, or every table API would start serving views");
    Assertions.assertTrue(
        houseTableRepository
            .findViewById(
                HouseTablePrimaryKey.builder()
                    .databaseId(IcebergRestViewFixtures.DATABASE_ID)
                    .tableId(IcebergRestViewFixtures.VIEW_ID)
                    .build())
            .isPresent(),
        "and the view-scoped read must see it");
  }
}
