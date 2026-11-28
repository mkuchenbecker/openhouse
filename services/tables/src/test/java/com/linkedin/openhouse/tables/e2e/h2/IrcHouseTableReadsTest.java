package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.newTable;
import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.post;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.linkedin.openhouse.common.exception.checked.NotFoundException;
import com.linkedin.openhouse.common.schema.IcebergSchemaHelper;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.internal.catalog.repository.HouseNamespaceRepository;
import com.linkedin.openhouse.internal.catalog.repository.HouseTableRepository;
import com.linkedin.openhouse.tables.model.TableModelConstants;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.StreamSupport;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.requests.CreateTableRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.util.ClassUtils;

/**
 * What each Iceberg REST Catalog (IRC) route reads from House Tables (V17): every read call the
 * route makes on the {@link HouseTableRepository}, in order, with its kind and the number of rows
 * it returned, on a database of three tables. Three is enough to tell one keyed or one-row read
 * from a listing of the database, whose rows grow with the number of tables it holds.
 *
 * <p>The repository is wrapped, once, in a stand-in that records each read and hands it on; the
 * store behind it is the H2 one every service test uses. A call made from inside the store (a
 * default method calling another) is not seen, only what the service asks of the repository, which
 * is what a House Tables request is in production.
 *
 * <p>The namespace-record store ({@link HouseNamespaceRepository}, a keyed store in House Tables)
 * is recorded the same way: {@code getByName[key]=1} for a record read, {@code =0} for a miss, and
 * its writes as {@code create[write]}, {@code update[write]}, {@code delete[write]}.
 *
 * <p>Each record is {@code method[kind]=rows}: {@code list} for the unpaged listing of a database,
 * {@code page<n>x<size>} for a paged one, {@code key} for a keyed read; a listing of every database
 * ({@code findAll}) is recorded without its row count, which depends on what other tests left in
 * the shared store. Answers are pinned by the route tests (IrcReadRoutesTest, IrcViewRoutesTest,
 * IrcWriteRoutesTest, IrcDirectoryReuseRoutesTest) and the REST compatibility kit, not here: here
 * only the cost, so that a change in cost shows as a change in these records.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({IrcReadRoutesTest.StubDefaults.class, IrcHouseTableReadsTest.CountingStandIn.class})
@ContextConfiguration(initializers = {PropertyOverrideContextInitializer.class})
public class IrcHouseTableReadsTest {

  /** The database of three tables. */
  private static final String DB = "v17_three";

  /** A database that holds nothing. */
  private static final String NONE = "v17_none";

  /** A namespace that has a record and holds nothing. */
  private static final String REC = "v17_record";

  private static final String[] TABLES = {"t1", "t2", "t3"};

  private static final Schema SCHEMA =
      IcebergSchemaHelper.getSchemaFromSchemaJson(TableModelConstants.HEALTH_SCHEMA_LITERAL);

  /** Every House Tables read since the last {@link #reads()}, in order. */
  static final List<String> READS = new CopyOnWriteArrayList<>();

  /** Wraps the House Tables repository bean in the recording stand-in. */
  @TestConfiguration
  static class CountingStandIn {
    @Bean
    static BeanPostProcessor countHouseTableReads() {
      return new BeanPostProcessor() {
        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
          if (bean instanceof HouseNamespaceRepository) {
            return recordingNamespaces((HouseNamespaceRepository) bean);
          }
          return bean instanceof HouseTableRepository ? recording(bean) : bean;
        }
      };
    }
  }

  private static Object recording(Object target) {
    Class<?>[] interfaces = ClassUtils.getAllInterfaces(target);
    return Proxy.newProxyInstance(
        IrcHouseTableReadsTest.class.getClassLoader(),
        interfaces,
        (proxy, method, args) -> {
          Object result;
          try {
            result = method.invoke(target, args);
          } catch (InvocationTargetException e) {
            throw e.getCause();
          }
          if (HouseTableRepository.class.isAssignableFrom(method.getDeclaringClass())
              || method.getDeclaringClass().getName().startsWith("org.springframework.data")) {
            String name = method.getName();
            if (name.startsWith("find")
                || name.startsWith("search")
                || name.startsWith("exists")
                || name.startsWith("count")) {
              READS.add(describe(name, args, result));
            }
          }
          return result;
        });
  }

  private static Object recordingNamespaces(HouseNamespaceRepository target) {
    return Proxy.newProxyInstance(
        IrcHouseTableReadsTest.class.getClassLoader(),
        ClassUtils.getAllInterfaces(target),
        (proxy, method, args) -> {
          String name = method.getName();
          boolean isRead = name.equals("getByName");
          boolean isWrite = name.equals("create") || name.equals("update") || name.equals("delete");
          try {
            Object result = method.invoke(target, args);
            if (isRead) {
              READS.add("getByName[key]=1");
            } else if (isWrite) {
              READS.add(name + "[write]");
            }
            return result;
          } catch (InvocationTargetException e) {
            if (isRead && e.getCause() instanceof NotFoundException) {
              READS.add("getByName[key]=0");
            } else if (isWrite) {
              READS.add(name + "[write]");
            }
            throw e.getCause();
          }
        });
  }

  private static String describe(String name, Object[] args, Object result) {
    List<Object> arguments = args == null ? Collections.emptyList() : Arrays.asList(args);
    Optional<Pageable> pageable =
        arguments.stream().filter(Pageable.class::isInstance).map(Pageable.class::cast).findFirst();
    String kind;
    if (pageable.isPresent()) {
      kind = "page" + pageable.get().getPageNumber() + "x" + pageable.get().getPageSize();
    } else if (name.startsWith("findAll") && arguments.isEmpty()) {
      return name + "[all databases]";
    } else if (name.startsWith("findAll") || name.startsWith("search")) {
      kind = "list";
    } else {
      kind = "key";
    }
    return name + "[" + kind + "]=" + rows(result);
  }

  private static long rows(Object result) {
    if (result instanceof Page) {
      return ((Page<?>) result).getContent().size();
    }
    if (result instanceof Optional) {
      return ((Optional<?>) result).isPresent() ? 1 : 0;
    }
    if (result instanceof Iterable) {
      return StreamSupport.stream(((Iterable<?>) result).spliterator(), false).count();
    }
    if (result instanceof Boolean) {
      return Boolean.TRUE.equals(result) ? 1 : 0;
    }
    return result == null ? 0 : 1;
  }

  @Autowired MockMvc mvc;

  private final List<String[]> created = new ArrayList<>();

  @AfterEach
  void cleanUp() throws Exception {
    for (String[] key : created) {
      CommitPathHttp.delete(mvc, key[0], key[1]);
    }
    created.clear();
    HouseTablesH2Repository.softDeletedTables.clear();
  }

  /** {@link #DB} with its three tables, made through the native create. */
  private void threeTables() throws Exception {
    for (String table : TABLES) {
      created.add(new String[] {DB, table});
      assertEquals(201, CommitPathHttp.status(post(mvc, newTable(DB, table))));
    }
  }

  /** The reads recorded since the last call, and a fresh start. */
  private static List<String> reads() {
    List<String> reads = new ArrayList<>(READS);
    READS.clear();
    return reads;
  }

  private List<String> readsOf(MvcResult result, int expectedStatus) throws Exception {
    assertEquals(expectedStatus, result.getResponse().getStatus(), IrcHttp.body(result));
    return reads();
  }

  private static List<String> list(String... reads) {
    return Arrays.asList(reads);
  }

  // ---------------------------------------------------------------- namespaces

  @Test
  void namespaceExists() throws Exception {
    threeTables();
    reads();
    assertEquals(
        list("findAllByDatabaseId[page0x1]=1"),
        readsOf(IrcHttp.head(mvc, "/v1/namespaces/" + DB), 204));
  }

  /** A miss reads the record (one keyed read) before the database list. */
  @Test
  void namespaceExistsMissing() throws Exception {
    reads();
    assertEquals(
        list("findAllByDatabaseId[page0x1]=0", "getByName[key]=0", "findAll[all databases]"),
        readsOf(IrcHttp.head(mvc, "/v1/namespaces/" + NONE), 404));
  }

  /** A load reads the record first, for its properties; with none, the table probe as before. */
  @Test
  void loadNamespace() throws Exception {
    threeTables();
    reads();
    assertEquals(
        list("getByName[key]=0", "findAllByDatabaseId[page0x1]=1"),
        readsOf(IrcHttp.get(mvc, "/v1/namespaces/" + DB), 200));
  }

  /** A namespace with a record: the record alone, for load and exists. */
  @Test
  void loadAndExistsOfANamespaceWithARecordOnly() throws Exception {
    createRecord();
    assertEquals(list("getByName[key]=1"), readsOf(IrcHttp.get(mvc, "/v1/namespaces/" + REC), 200));
    assertEquals(
        list("findAllByDatabaseId[page0x1]=0", "getByName[key]=1"),
        readsOf(IrcHttp.head(mvc, "/v1/namespaces/" + REC), 204));
    assertEquals(
        list("findAllByDatabaseId[list]=0", "getByName[key]=1"),
        readsOf(IrcHttp.get(mvc, "/v1/namespaces/" + REC + "/tables"), 200));
  }

  /** A create probes for a table and a view (one row each), then writes the record. */
  @Test
  void createNamespace() throws Exception {
    reads();
    try {
      assertEquals(
          list(
              "findAllByDatabaseId[page0x1]=0",
              "findAllViewsByDatabaseId[page0x1]=0",
              "create[write]"),
          readsOf(IrcNamespaceRoutesTest.createNamespace(mvc, REC, Collections.emptyMap()), 200));
    } finally {
      IrcNamespaceRoutesTest.dropNamespace(mvc, REC);
      reads();
    }
  }

  /** A create of a namespace that holds a table: the table probe, and nothing else. */
  @Test
  void createNamespaceThatHoldsATable() throws Exception {
    threeTables();
    reads();
    assertEquals(
        list("findAllByDatabaseId[page0x1]=1"),
        readsOf(IrcNamespaceRoutesTest.createNamespace(mvc, DB, Collections.emptyMap()), 409));
  }

  /** An update is the namespace service's: one keyed read of the record and one replace. */
  @Test
  void updateNamespaceProperties() throws Exception {
    createRecord();
    try {
      assertEquals(
          list("getByName[key]=1", "update[write]"),
          readsOf(
              IrcNamespaceRoutesTest.updateProperties(
                  mvc, REC, Collections.singletonMap("k", "v"), Collections.emptyList()),
              200));
    } finally {
      IrcNamespaceRoutesTest.dropNamespace(mvc, REC);
      reads();
    }
  }

  /** A drop probes for a table and a view, then deletes the record. */
  @Test
  void dropNamespace() throws Exception {
    createRecord();
    assertEquals(
        list(
            "findAllByDatabaseId[page0x1]=0",
            "findAllViewsByDatabaseId[page0x1]=0",
            "delete[write]"),
        readsOf(IrcNamespaceRoutesTest.dropNamespace(mvc, REC), 204));
  }

  /** A drop of a namespace that holds a table: the table probe, and nothing else. */
  @Test
  void dropNamespaceThatHoldsATable() throws Exception {
    threeTables();
    reads();
    assertEquals(
        list("findAllByDatabaseId[page0x1]=1"),
        readsOf(IrcNamespaceRoutesTest.dropNamespace(mvc, DB), 409));
  }

  /** {@link #REC} with a record and no table, and a fresh start of the reads. */
  private void createRecord() throws Exception {
    assertEquals(
        200,
        IrcNamespaceRoutesTest.createNamespace(mvc, REC, Collections.emptyMap())
            .getResponse()
            .getStatus());
    reads();
  }

  @Test
  void listNamespacesUnderParent() throws Exception {
    threeTables();
    reads();
    assertEquals(
        list("findAllByDatabaseId[page0x1]=1"),
        readsOf(IrcHttp.get(mvc, "/v1/namespaces?parent=" + DB), 200));
  }

  /** The native database list reads the same: every database. */
  @Test
  void listNamespaces() throws Exception {
    threeTables();
    reads();
    assertEquals(list("findAll[all databases]"), readsOf(IrcHttp.get(mvc, "/v1/namespaces"), 200));
    MvcResult nativeList = mvc.perform(MockMvcRequestBuilders.get("/v1/databases")).andReturn();
    assertEquals(list("findAll[all databases]"), readsOf(nativeList, 200));
  }

  // ---------------------------------------------------------------- tables

  /** The answer is every table of the database: the listing is the answer, as natively. */
  @Test
  void listTables() throws Exception {
    threeTables();
    reads();
    assertEquals(
        list("findAllByDatabaseId[list]=3"),
        readsOf(IrcHttp.get(mvc, "/v1/namespaces/" + DB + "/tables"), 200));
    MvcResult nativeSearch =
        mvc.perform(
                MockMvcRequestBuilders.post("/v1/databases/" + DB + "/tables/search")
                    .accept(MediaType.APPLICATION_JSON))
            .andReturn();
    assertEquals(list("findAllByDatabaseId[list]=3"), readsOf(nativeSearch, 200));
  }

  /** A page size is accepted and the full list answered. */
  @Test
  void listTablesWithPageSize() throws Exception {
    threeTables();
    reads();
    assertEquals(
        list("findAllByDatabaseId[list]=3"),
        readsOf(IrcHttp.get(mvc, "/v1/namespaces/" + DB + "/tables?pageSize=1"), 200));
    MvcResult nativePage =
        mvc.perform(
                MockMvcRequestBuilders.post("/v2/databases/" + DB + "/tables/search")
                    .param("size", "1")
                    .accept(MediaType.APPLICATION_JSON))
            .andReturn();
    assertEquals(list("findAllByDatabaseId[page0x1]=1"), readsOf(nativePage, 200));
  }

  @Test
  void listTablesMissing() throws Exception {
    reads();
    assertEquals(
        list("findAllByDatabaseId[list]=0", "getByName[key]=0", "findAll[all databases]"),
        readsOf(IrcHttp.get(mvc, "/v1/namespaces/" + NONE + "/tables"), 404));
  }

  @Test
  void tableExists() throws Exception {
    threeTables();
    reads();
    assertEquals(
        list("findById[key]=1"), readsOf(IrcHttp.head(mvc, IrcWrite.table(DB, "t2")), 204));
  }

  // ---------------------------------------------------------------- views

  /** Views are not enabled for the database: the list is empty if the namespace exists. */
  @Test
  void listViewsDisabled() throws Exception {
    threeTables();
    reads();
    assertEquals(
        list("findAllByDatabaseId[page0x1]=1"),
        readsOf(IrcHttp.get(mvc, IrcViewWrite.views(DB)), 200));
  }

  // ---------------------------------------------------------------- writes

  /** A rename to another database checks the destination namespace, then is refused. */
  @Test
  void renameToAnotherDatabase() throws Exception {
    threeTables();
    reads();
    MvcResult result =
        IrcWrite.rename(mvc, TableIdentifier.of(NONE, "x"), TableIdentifier.of(DB, "y"));
    assertEquals(list("findAllByDatabaseId[page0x1]=1"), readsOf(result, 406));
  }

  /**
   * The commit that completes a staged create looks at every row of the database, live and
   * soft-deleted, for one that uses the directory it names: a table renamed away keeps its
   * directory under any name, so no keyed read can answer.
   */
  @Test
  void completeStagedCreate() throws Exception {
    threeTables();
    TableMetadata staged =
        IrcWrite.parsed(
            IrcWrite.create(
                mvc,
                DB,
                CreateTableRequest.builder()
                    .withName("a")
                    .withSchema(SCHEMA)
                    .withPartitionSpec(PartitionSpec.unpartitioned())
                    .stageCreate()
                    .build()));
    created.add(new String[] {DB, "a"});
    reads();
    MvcResult result =
        IrcWrite.commit(mvc, DB, "a", IrcWrite.createCommit(staged, Collections.emptyList()));
    assertEquals(
        list(
            "findById[key]=0",
            "findAllByDatabaseId[list]=3",
            "searchSoftDeletedTables[page0x100]=0",
            "findById[key]=0",
            "findById[key]=0",
            "findEntityById[key]=0",
            "findById[key]=0",
            "findById[key]=0",
            "findById[key]=1",
            "findById[key]=1",
            "findById[key]=1"),
        readsOf(result, 200));
  }
}
