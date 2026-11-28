package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.IrcHttp.body;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jayway.jsonpath.JsonPath;
import com.linkedin.openhouse.cluster.storage.StorageManager;
import com.linkedin.openhouse.cluster.storage.StorageType;
import com.linkedin.openhouse.cluster.storage.selector.StorageSelector;
import com.linkedin.openhouse.common.security.DummyTokenInterceptor.DummySecurityJWT;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.IrcClientParsers;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.rest.requests.CreateTableRequest;
import org.apache.iceberg.rest.responses.LoadTableResponse;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The FileIO class an Iceberg REST client is given for a table that the storage selector places on
 * another storage type than the cluster's default.
 *
 * <p>The config route advertises the default storage's class as the catalog's {@code io-impl}
 * ({@link IrcConfigFileIoTest}). The table's own responses (load, create, staged create) can carry
 * per-table {@code config}, which the client lays over the catalog's properties when it builds that
 * table's FileIO: a table on a storage whose class is not the default's is given its own class
 * there ({@code io-impl}, the class name only), and any other table nothing.
 *
 * <p>The clusters here have two storage types: a default one and {@code local}, which the regex
 * storage selector gives every table of {@link #LOCAL_DB}. The object stores are configured but
 * never contacted: only tables on local storage are created.
 */
public class IrcTableFileIoTest {

  static final String LOCAL_DB = "fio_local";
  static final String ROOT = "/tmp/irc_table_fileio";
  static final String HADOOP = "org.apache.iceberg.hadoop.HadoopFileIO";
  static final String S3 = "org.apache.iceberg.aws.s3.S3FileIO";

  static final Schema SCHEMA =
      new Schema(Types.NestedField.required(1, "id", Types.LongType.get()));

  /** The test cluster with {@code properties} on top, and {@link #LOCAL_DB} on local storage. */
  abstract static class TwoStorages extends PropertyOverrideContextInitializer {
    abstract String[] defaultStorage();

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
      super.initialize(applicationContext);
      TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
          applicationContext, defaultStorage());
      TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
          applicationContext,
          "cluster.storages.types.local.endpoint=file:",
          "cluster.storages.types.local.root-path=" + ROOT,
          "cluster.storages.storage-selector.name=RegexStorageSelector",
          "cluster.storages.storage-selector.parameters.regex=" + LOCAL_DB + "\\..*",
          "cluster.storages.storage-selector.parameters.storage-type=local");
    }
  }

  /** s3 by default, as {@link IrcConfigFileIoTest} configures it. */
  static class S3DefaultAndLocal extends TwoStorages {
    @Override
    String[] defaultStorage() {
      // The S3 client is built at start-up and needs a region, as a deployment's AWS_REGION gives.
      System.setProperty("aws.region", "us-east-1");
      return new String[] {
        "cluster.storages.default-type=s3",
        "cluster.storages.types.s3.endpoint=s3://",
        "cluster.storages.types.s3.root-path=openhouse-bucket",
        "cluster.storages.types.s3.parameters.s3.endpoint=http://localhost:1",
        "cluster.storages.types.s3.parameters.s3.access-key-id=test",
        "cluster.storages.types.s3.parameters.s3.secret-access-key=test"
      };
    }
  }

  /** hdfs by default: the two storage types share a client class. */
  static class HdfsDefaultAndLocal extends TwoStorages {
    @Override
    String[] defaultStorage() {
      return new String[] {
        "cluster.storages.default-type=hdfs",
        "cluster.storages.types.hdfs.endpoint=file:///",
        "cluster.storages.types.hdfs.root-path=" + ROOT + "_hdfs"
      };
    }
  }

  /** What each cluster's test asks of its context. */
  abstract static class Cluster {
    @Autowired MockMvc mvc;
    @Autowired StorageManager storageManager;
    @Autowired StorageSelector storageSelector;

    @AfterEach
    void cleanUp() throws Exception {
      for (String table : new String[] {"t", "s"}) {
        IrcWrite.drop(mvc, LOCAL_DB, table);
      }
      if (new File(ROOT).exists()) {
        try (Stream<Path> paths = Files.walk(new File(ROOT).toPath())) {
          paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        }
      }
    }

    static CreateTableRequest.Builder create(String table) {
      return CreateTableRequest.builder()
          .withName(table)
          .withSchema(SCHEMA)
          .withPartitionSpec(PartitionSpec.unpartitioned());
    }

    /** The {@code config} of a 200 load-table-shaped response, as the client parses it. */
    static Map<String, String> config(MvcResult result) throws Exception {
      assertEquals(200, result.getResponse().getStatus(), body(result));
      return IrcClientParsers.parse(body(result), LoadTableResponse.class).config();
    }

    /** The table's {@code config} from the create, the load and a staged create's responses. */
    Map<String, Map<String, String>> tableConfigs() throws Exception {
      assertEquals(
          StorageType.LOCAL, storageSelector.selectStorage(LOCAL_DB, "t").getType(), "selector");
      Map<String, Map<String, String>> configs = new HashMap<>();
      configs.put("create", config(IrcWrite.create(mvc, LOCAL_DB, create("t").build())));
      configs.put("load", config(IrcHttp.get(mvc, IrcWrite.table(LOCAL_DB, "t"))));
      configs.put(
          "staged create",
          config(IrcWrite.create(mvc, LOCAL_DB, create("s").stageCreate().build())));
      return configs;
    }

    static Map<String, Map<String, String>> each(Map<String, String> config) {
      Map<String, Map<String, String>> configs = new HashMap<>();
      for (String route : new String[] {"create", "load", "staged create"}) {
        configs.put(route, config);
      }
      return configs;
    }
  }

  @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
  @AutoConfigureMockMvc
  @Import(IrcReadRoutesTest.StubDefaults.class)
  @ContextConfiguration(initializers = {S3DefaultAndLocal.class})
  @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
  static class S3DefaultCluster extends Cluster {
    @LocalServerPort int port;
    RESTCatalog rest;

    @BeforeEach
    void start() throws Exception {
      Map<String, String> properties = new HashMap<>();
      properties.put("uri", "http://localhost:" + port + "/iceberg");
      properties.put("token", new DummySecurityJWT("irc_fileio").buildNoopJWT());
      rest =
          (RESTCatalog)
              CatalogUtil.loadCatalog(
                  RESTCatalog.class.getName(), "openhouse", properties, new Configuration());
    }

    @AfterEach
    void stop() throws Exception {
      rest.close();
    }

    /**
     * A table on local storage is given its own storage's class, HadoopFileIO, in the create, load
     * and staged create responses, and nothing else. The client then reads and writes it with that
     * class, though its catalog's class is S3FileIO. The native API's answer is as it was.
     */
    @Test
    void aLocalTableOnAnS3ClusterIsGivenTheHadoopClass() throws Exception {
      assertEquals(StorageType.S3, storageManager.getDefaultStorage().getType());
      assertEquals(each(Collections.singletonMap("io-impl", HADOOP)), tableConfigs());
      assertEquals(S3, rest.properties().get("io-impl"));

      Table table = rest.loadTable(TableIdentifier.of(LOCAL_DB, "t"));
      assertEquals(HADOOP, table.io().getClass().getName());
      // The client writes the table through that class: a manifest and a manifest list on local
      // storage, and a commit.
      table
          .newFastAppend()
          .appendFile(
              DataFiles.builder(table.spec())
                  .withPath(table.location() + "/data/a.orc")
                  .withFileSizeInBytes(10)
                  .withRecordCount(1)
                  .build())
          .commit();
      assertEquals(
          1,
          rest.loadTable(TableIdentifier.of(LOCAL_DB, "t"))
              .currentSnapshot()
              .allManifests(table.io())
              .size());

      // A table the client creates is given the class too.
      Table created =
          rest.createTable(
              TableIdentifier.of(LOCAL_DB, "s"), SCHEMA, PartitionSpec.unpartitioned());
      assertEquals(HADOOP, created.io().getClass().getName());

      // The native API's per-table config is untouched.
      String nativeJson = CommitPathHttp.json(CommitPathHttp.get(mvc, LOCAL_DB, "t"));
      Object nativeConfig = ((Map<?, ?>) JsonPath.read(nativeJson, "$")).get("config");
      assertTrue(
          nativeConfig == null || ((Map<?, ?>) nativeConfig).isEmpty(),
          "native config: " + nativeJson);
    }
  }

  @SpringBootTest
  @AutoConfigureMockMvc
  @Import(IrcReadRoutesTest.StubDefaults.class)
  @ContextConfiguration(initializers = {HdfsDefaultAndLocal.class})
  static class HdfsDefaultCluster extends Cluster {
    /** Both storage types are read with HadoopFileIO: there is nothing to say per table. */
    @Test
    void aLocalTableOnAnHdfsClusterGetsNoEntry() throws Exception {
      assertEquals(StorageType.HDFS, storageManager.getDefaultStorage().getType());
      assertEquals(each(Collections.emptyMap()), tableConfigs());
    }
  }
}
