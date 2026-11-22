package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.*;
import static com.linkedin.openhouse.tables.model.IcebergSnapshotsModelTestUtilities.createDummyDataFile;
import static com.linkedin.openhouse.tables.model.IcebergSnapshotsModelTestUtilities.getPartitionSpec;
import static com.linkedin.openhouse.tables.model.TableModelConstants.buildGetTableResponseBody;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.linkedin.openhouse.common.exception.CommitRejectedException;
import com.linkedin.openhouse.common.exception.EntityConcurrentModificationException;
import com.linkedin.openhouse.common.exception.InvalidSchemaEvolutionException;
import com.linkedin.openhouse.common.exception.RequestValidationFailureException;
import com.linkedin.openhouse.common.exception.UnsupportedClientOperationException;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.tables.api.spec.v0.request.CreateUpdateTableRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.LockReason;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.LockState;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.Policies;
import com.linkedin.openhouse.tables.api.spec.v0.response.GetTableResponseBody;
import com.linkedin.openhouse.tables.authorization.AuthorizationHandler;
import com.linkedin.openhouse.tables.authorization.Privileges;
import com.linkedin.openhouse.tables.commit.CommitContext;
import com.linkedin.openhouse.tables.commit.CommitKind;
import com.linkedin.openhouse.tables.config.TablesMvcConstants;
import com.linkedin.openhouse.tables.mock.properties.AuthorizationPropertiesInitializer;
import com.linkedin.openhouse.tables.model.DatabaseDto;
import com.linkedin.openhouse.tables.model.TableDto;
import com.linkedin.openhouse.tables.model.TableDtoWithMetadata;
import com.linkedin.openhouse.tables.readbridge.ClientCapabilityMissingException;
import com.linkedin.openhouse.tables.services.TableCommitService;
import com.linkedin.openhouse.tables.services.TablesService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.SortOrder;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The commit core's metadata entry point ({@link TableCommitService#commit(TableIdentifier, String,
 * TableDtoWithMetadata, TableMetadata, CommitContext)}), given candidates built the way an Iceberg
 * REST Catalog commit builds one: by applying changes to the stored metadata. One accepted case per
 * kind of change, and one rejected case per rule, each compared with what the native API answers
 * for the same violation on the same table: same exception (whose handler gives the same status)
 * and same message.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(
    initializers = {
      PropertyOverrideContextInitializer.class,
      AuthorizationPropertiesInitializer.class
    })
public class TableCommitServiceTest {
  private static final String PRINCIPAL = "commit-core-test";

  @Autowired MockMvc mvc;
  @Autowired Catalog catalog;
  @Autowired TablesService tablesService;
  @Autowired TableCommitService commits;
  @MockBean AuthorizationHandler authorizationHandler;

  private final List<String> created = new ArrayList<>();

  @BeforeEach
  void allowEverything() {
    reset(authorizationHandler);
    decide(true);
  }

  @AfterEach
  void cleanUp() throws Exception {
    RequestContextHolder.resetRequestAttributes();
    decide(true);
    for (String db : created) {
      unlock(mvc, db, "t");
      mvc.perform(
          MockMvcRequestBuilders.delete(tableUrl(db, "t") + "/lock/SYSTEM_ONLY")
              .header(TablesMvcConstants.HTTP_HEADER_ACTION_TYPE, "SYSTEM"));
      delete(mvc, db, "t");
    }
    created.clear();
  }

  private void decide(boolean allowed) {
    when(authorizationHandler.checkAccessDecision(
            any(), any(TableDto.class), any(Privileges.class)))
        .thenReturn(allowed);
    when(authorizationHandler.checkAccessDecision(
            any(), any(DatabaseDto.class), any(Privileges.class)))
        .thenReturn(allowed);
    when(authorizationHandler.checkSystemOnlyLockAccess(any(), any(), any())).thenCallRealMethod();
  }

  private String make(String db) throws Exception {
    created.add(db);
    post(mvc, newTable(db, "t")).andExpect(status().isCreated());
    return json(get(mvc, db, "t"));
  }

  private TableDtoWithMetadata load(String db) {
    return tablesService.loadTable(db, "t", PRINCIPAL);
  }

  private static CommitContext context(CommitKind kind) {
    return CommitContext.builder()
        .kind(kind)
        .principal(PRINCIPAL)
        .clusterId("local-cluster")
        .build();
  }

  private void commit(TableDtoWithMetadata base, TableMetadata candidate, CommitKind kind) {
    commit(base, candidate, context(kind));
  }

  private void commit(TableDtoWithMetadata base, TableMetadata candidate, CommitContext context) {
    commits.commit(
        TableIdentifier.of(base.getTableDto().getDatabaseId(), "t"),
        base.getTableDto().getTableLocation(),
        base,
        candidate,
        context);
  }

  private static TableMetadata withProperty(TableMetadata base, String key, String value) {
    Map<String, String> props = new HashMap<>();
    props.put(key, value);
    return TableMetadata.buildFrom(base).setProperties(props).build();
  }

  private static CreateUpdateTableRequestBody withProp(String getJson, String key, String value) {
    CreateUpdateTableRequestBody b = bodyFromGet(getJson);
    Map<String, String> props = new HashMap<>(b.getTableProperties());
    props.put(key, value);
    return b.toBuilder().tableProperties(props).build();
  }

  /** Asserts the core throws {@code type} with exactly the native error's message. */
  private static <T extends Throwable> void expectSameRejection(
      ResultActions nativeAnswer, int nativeStatus, Class<T> type, Executable core)
      throws Exception {
    assertEquals(nativeStatus, CommitPathHttp.status(nativeAnswer), json(nativeAnswer));
    T thrown = assertThrows(type, core);
    assertEquals(message(nativeAnswer), thrown.getMessage());
  }

  private static String version(String getJson) {
    String location = str(getJson, "$.tableLocation");
    return location.substring(location.lastIndexOf('/') + 1, location.lastIndexOf('/') + 6);
  }

  // ---- accepted, one per kind of change ----

  @Test
  void propertyChangeIsStoredAsOneNewVersion() throws Exception {
    String v0 = make("cc_prop");
    TableDtoWithMetadata base = load("cc_prop");
    commit(base, withProperty(base.getMetadata(), "user.k", "v"), CommitKind.TABLE_UPDATE);
    String v1 = json(get(mvc, "cc_prop", "t"));
    assertEquals("v", read(v1, "$.tableProperties['user.k']"));
    assertEquals("00000", version(v0));
    assertEquals("00001", version(v1));
    assertEquals(str(v0, "$.tableLocation").replaceFirst("^file:", ""), str(v1, "$.tableVersion"));
  }

  @Test
  void propertyRemovalIsStored() throws Exception {
    make("cc_unset");
    TableMetadata base = load("cc_unset").getMetadata();
    assertTrue(base.properties().containsKey("user.a"));
    TableMetadata candidate =
        TableMetadata.buildFrom(base)
            .removeProperties(java.util.Collections.singleton("user.a"))
            .build();
    commit(load("cc_unset"), candidate, CommitKind.TABLE_UPDATE);
    Map<String, String> props = read(json(get(mvc, "cc_unset", "t")), "$.tableProperties");
    assertFalse(props.containsKey("user.a"));
  }

  @Test
  void schemaChangeIsStored() throws Exception {
    make("cc_schema");
    TableDtoWithMetadata base = load("cc_schema");
    TableMetadata metadata = base.getMetadata();
    List<Types.NestedField> columns = new ArrayList<>(metadata.schema().columns());
    columns.add(
        Types.NestedField.optional(metadata.lastColumnId() + 1, "added", Types.StringType.get()));
    Schema evolved = new Schema(columns);
    commit(
        base,
        TableMetadata.buildFrom(metadata)
            .setCurrentSchema(evolved, evolved.highestFieldId())
            .build(),
        CommitKind.TABLE_UPDATE);
    assertTrue(str(json(get(mvc, "cc_schema", "t")), "$.schema").contains("\"added\""));
  }

  @Test
  void sortOrderChangeIsStored() throws Exception {
    make("cc_sort");
    TableDtoWithMetadata base = load("cc_sort");
    TableMetadata metadata = base.getMetadata();
    commit(
        base,
        TableMetadata.buildFrom(metadata)
            .setDefaultSortOrder(SortOrder.builderFor(metadata.schema()).asc("id").build())
            .build(),
        CommitKind.TABLE_UPDATE);
    assertTrue(str(json(get(mvc, "cc_sort", "t")), "$.sortOrder").contains("\"identity\""));
    assertEquals(
        1, catalog.loadTable(TableIdentifier.of("cc_sort", "t")).sortOrder().fields().size());
  }

  @Test
  void policyChangeThroughTheContextIsStored() throws Exception {
    make("cc_policy");
    TableDtoWithMetadata base = load("cc_policy");
    Policies policies = base.getTableDto().getPolicies().toBuilder().sharingEnabled(true).build();
    commit(
        base,
        base.getMetadata(),
        CommitContext.builder()
            .kind(CommitKind.TABLE_UPDATE)
            .principal(PRINCIPAL)
            .requestedPolicies(policies)
            .build());
    assertEquals(true, read(json(get(mvc, "cc_policy", "t")), "$.policies.sharingEnabled"));
  }

  @Test
  void snapshotAppendIsStored() throws Exception {
    make("cc_append");
    TableDtoWithMetadata base = load("cc_append");
    GetTableResponseBody body = buildGetTableResponseBody(get(mvc, "cc_append", "t").andReturn());
    Table table = catalog.loadTable(TableIdentifier.of("cc_append", "t"));
    Snapshot appended =
        table
            .newAppend()
            .appendFile(createDummyDataFile("/tmp/cc_append.orc", getPartitionSpec(body)))
            .apply();
    commit(
        base,
        TableMetadata.buildFrom(base.getMetadata()).setBranchSnapshot(appended, "main").build(),
        CommitKind.SNAPSHOTS_UPDATE);
    Table after = catalog.loadTable(TableIdentifier.of("cc_append", "t"));
    assertEquals(appended.snapshotId(), after.currentSnapshot().snapshotId());
    assertEquals("00001", version(json(get(mvc, "cc_append", "t"))));
  }

  @Test
  void unchangedCandidateWritesNoVersionOnlyWhenNothingDiffers() throws Exception {
    String v0 = make("cc_same");
    TableDtoWithMetadata base = load("cc_same");
    commit(base, base.getMetadata(), CommitKind.TABLE_UPDATE);
    expectUnchanged(mvc, v0, "cc_same", "t");
  }

  // ---- rejected, one per rule, each against the native answer ----

  @Test
  void lockedTableIsRejectedWithTheWordingOfEachRoute() throws Exception {
    make("cc_locked");
    lock(mvc, "cc_locked", "t").andExpect(status().isCreated());
    String locked = json(get(mvc, "cc_locked", "t"));
    TableDtoWithMetadata base = load("cc_locked");
    expectSameRejection(
        put(mvc, withProp(locked, "user.k", "v")),
        400,
        UnsupportedClientOperationException.class,
        () ->
            commit(base, withProperty(base.getMetadata(), "user.k", "v"), CommitKind.TABLE_UPDATE));
    UnsupportedClientOperationException snapshots =
        assertThrows(
            UnsupportedClientOperationException.class,
            () ->
                commit(
                    base,
                    withProperty(base.getMetadata(), "user.k", "v"),
                    CommitKind.SNAPSHOTS_UPDATE));
    assertEquals(
        "Table cc_locked.t is in locked state and cannot be written to", snapshots.getMessage());
    expectUnchanged(mvc, locked, "cc_locked", "t");
  }

  @Test
  void deniedWriteIsRejected() throws Exception {
    String before = make("cc_denied");
    TableDtoWithMetadata base = load("cc_denied");
    decide(false);
    assertEquals(403, CommitPathHttp.status(put(mvc, withProp(before, "user.k", "v"))));
    AccessDeniedException denied =
        assertThrows(
            AccessDeniedException.class,
            () ->
                commit(
                    base,
                    withProperty(base.getMetadata(), "user.k", "v"),
                    CommitKind.TABLE_UPDATE));
    assertTrue(denied.getMessage().contains("cc_denied"), denied.getMessage());
    decide(true);
    expectUnchanged(mvc, before, "cc_denied", "t");
  }

  @Test
  void reservedPropertyChangeIsRejected() throws Exception {
    String before = make("cc_reserved");
    TableDtoWithMetadata base = load("cc_reserved");
    expectSameRejection(
        put(mvc, withProp(before, "openhouse.tableCreator", "someone-else")),
        400,
        UnsupportedClientOperationException.class,
        () ->
            commit(
                base,
                withProperty(base.getMetadata(), "openhouse.tableCreator", "someone-else"),
                CommitKind.TABLE_UPDATE));
    expectSameRejection(
        put(mvc, withProp(before, "policies", "{}")),
        400,
        UnsupportedClientOperationException.class,
        () ->
            commit(
                base, withProperty(base.getMetadata(), "policies", "{}"), CommitKind.TABLE_UPDATE));
    expectUnchanged(mvc, before, "cc_reserved", "t");
  }

  @Test
  void partitionEvolutionIsRejected() throws Exception {
    String before = make("cc_partition");
    TableDtoWithMetadata base = load("cc_partition");
    CreateUpdateTableRequestBody b = bodyFromGet(before);
    TableMetadata metadata = base.getMetadata();
    // Drop the second clustering column; the policies stay valid, so only the partition rule
    // can reject it.
    PartitionSpec fewer =
        PartitionSpec.builderFor(metadata.schema()).hour("timestampCol").identity("id").build();
    expectSameRejection(
        put(mvc, b.toBuilder().clustering(b.getClustering().subList(0, 1)).build()),
        400,
        UnsupportedClientOperationException.class,
        () ->
            commit(
                base,
                TableMetadata.buildFrom(metadata).setDefaultPartitionSpec(fewer).build(),
                CommitKind.TABLE_UPDATE));
    expectUnchanged(mvc, before, "cc_partition", "t");
  }

  @Test
  void incompatibleSchemaChangeIsRejected() throws Exception {
    String before = make("cc_badschema");
    TableDtoWithMetadata base = load("cc_badschema");
    CreateUpdateTableRequestBody b = bodyFromGet(before);
    String retyped = retypeColumn(b.getSchema(), "stats", Types.LongType.get());
    ResultActions nativeAnswer = put(mvc, b.toBuilder().schema(retyped).build());
    assertEquals(400, CommitPathHttp.status(nativeAnswer));
    Schema schema =
        new Schema(
            base.getMetadata().schema().columns().stream()
                .map(
                    f ->
                        f.name().equals("stats")
                            ? Types.NestedField.of(
                                f.fieldId(), f.isOptional(), f.name(), Types.LongType.get())
                            : f)
                .collect(java.util.stream.Collectors.toList()));
    InvalidSchemaEvolutionException thrown =
        assertThrows(
            InvalidSchemaEvolutionException.class,
            () ->
                commit(
                    base,
                    TableMetadata.buildFrom(base.getMetadata())
                        .setCurrentSchema(schema, schema.highestFieldId())
                        .build(),
                    CommitKind.TABLE_UPDATE));
    // The message embeds the proposed schema as JSON; the native request's carries the schema id
    // the client sent, the candidate's the id Iceberg's builder assigned, so compare up to it.
    String stable = "contains invalid schema evolution on top of current table schema";
    assertTrue(message(nativeAnswer).contains(stable), message(nativeAnswer));
    assertTrue(thrown.getMessage().contains(stable), thrown.getMessage());
    expectUnchanged(mvc, before, "cc_badschema", "t");
  }

  @Test
  void staleBaseVersionIsAConflict() throws Exception {
    String v0 = make("cc_stale");
    TableDtoWithMetadata stale = load("cc_stale");
    put(mvc, withProp(v0, "user.first", "1")).andExpect(status().isOk());
    String v1 = json(get(mvc, "cc_stale", "t"));
    ResultActions nativeAnswer = put(mvc, withProp(v0, "user.second", "2"));
    assertEquals(409, CommitPathHttp.status(nativeAnswer));
    EntityConcurrentModificationException thrown =
        assertThrows(
            EntityConcurrentModificationException.class,
            () ->
                commit(
                    stale,
                    withProperty(stale.getMetadata(), "user.second", "2"),
                    CommitKind.TABLE_UPDATE));
    // The error handler words a conflict from the entity key and the cause's message.
    assertEquals(
        String.format(
            "Entity with key[%s] is modified by another process already, nested exception message: %s",
            "local-cluster.cc_stale.t", thrown.getCause().getMessage()),
        message(nativeAnswer));
    expectUnchanged(mvc, v1, "cc_stale", "t");
  }

  @Test
  void outOfBoundMaxRefAgeIsRejected() throws Exception {
    String before = make("cc_refage");
    TableDtoWithMetadata base = load("cc_refage");
    expectSameRejection(
        put(mvc, withProp(before, TableProperties.MAX_REF_AGE_MS, "0")),
        400,
        CommitRejectedException.class,
        () ->
            commit(
                base,
                withProperty(base.getMetadata(), TableProperties.MAX_REF_AGE_MS, "0"),
                CommitKind.TABLE_UPDATE));
    expectUnchanged(mvc, before, "cc_refage", "t");
  }

  @Test
  void missingClientCapabilityIsRejected() throws Exception {
    String before = make("cc_capability");
    TableDtoWithMetadata base = load("cc_capability");
    String key = "required-capability.column-defaults";
    expectSameRejection(
        put(mvc, withProp(before, key, "true")),
        422,
        ClientCapabilityMissingException.class,
        () -> commit(base, withProperty(base.getMetadata(), key, "true"), CommitKind.TABLE_UPDATE));
    expectUnchanged(mvc, before, "cc_capability", "t");
  }

  @Test
  void lockFlipThroughPoliciesIsRejected() throws Exception {
    make("cc_flip");
    lock(mvc, "cc_flip", "t").andExpect(status().isCreated());
    String locked = json(get(mvc, "cc_flip", "t"));
    CreateUpdateTableRequestBody b = bodyFromGet(locked);
    Policies unlocked =
        b.getPolicies().toBuilder().lockState(LockState.builder().locked(false).build()).build();
    TableDtoWithMetadata base = load("cc_flip");
    expectSameRejection(
        put(mvc, b.toBuilder().policies(unlocked).build()),
        400,
        IllegalArgumentException.class,
        () ->
            commit(
                base,
                base.getMetadata(),
                CommitContext.builder()
                    .kind(CommitKind.TABLE_UPDATE)
                    .principal(PRINCIPAL)
                    .requestedPolicies(unlocked)
                    .build()));
    expectUnchanged(mvc, locked, "cc_flip", "t");
  }

  @Test
  void systemOnlyLockChangeIsRejected() throws Exception {
    make("cc_sysonly");
    mvc.perform(
            MockMvcRequestBuilders.post(tableUrl("cc_sysonly", "t") + "/lock")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"locked\":true,\"reason\":\"SYSTEM_ONLY\"}"))
        .andExpect(status().isCreated());
    MvcResult getResult =
        mvc.perform(
                MockMvcRequestBuilders.get(tableUrl("cc_sysonly", "t"))
                    .header(TablesMvcConstants.HTTP_HEADER_ACTION_TYPE, "SYSTEM")
                    .accept(MediaType.APPLICATION_JSON))
            .andReturn();
    CreateUpdateTableRequestBody b = bodyFromGet(getResult.getResponse().getContentAsString());
    Policies legacy =
        b.getPolicies()
            .toBuilder()
            .lockState(LockState.builder().locked(true).reason(LockReason.LEGACY).build())
            .build();
    ResultActions nativeAnswer =
        mvc.perform(
            MockMvcRequestBuilders.put(tableUrl("cc_sysonly", "t"))
                .header(TablesMvcConstants.HTTP_HEADER_ACTION_TYPE, "SYSTEM")
                .contentType(MediaType.APPLICATION_JSON)
                .content(b.toBuilder().policies(legacy).build().toJson())
                .accept(MediaType.APPLICATION_JSON));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(TablesMvcConstants.HTTP_HEADER_ACTION_TYPE, "SYSTEM");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    TableDtoWithMetadata base = load("cc_sysonly");
    expectSameRejection(
        nativeAnswer,
        400,
        RequestValidationFailureException.class,
        () ->
            commit(
                base,
                base.getMetadata(),
                CommitContext.builder()
                    .kind(CommitKind.TABLE_UPDATE)
                    .principal(PRINCIPAL)
                    .requestedPolicies(legacy)
                    .build()));
  }

  @Test
  void candidateChangingWhatACommitDoesNotStoreIsRefused() throws Exception {
    String before = make("cc_unstored");
    TableDtoWithMetadata base = load("cc_unstored");
    RequestValidationFailureException refused =
        assertThrows(
            RequestValidationFailureException.class,
            () ->
                commit(
                    base,
                    TableMetadata.buildFrom(base.getMetadata())
                        .setLocation("/tmp/elsewhere")
                        .build(),
                    CommitKind.TABLE_UPDATE));
    assertTrue(refused.getMessage().contains("location"), refused.getMessage());
    expectUnchanged(mvc, before, "cc_unstored", "t");
  }
}
