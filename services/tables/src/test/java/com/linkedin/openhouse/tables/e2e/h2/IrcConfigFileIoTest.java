package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.IrcHttp.body;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.linkedin.openhouse.cluster.storage.Storage;
import com.linkedin.openhouse.cluster.storage.StorageManager;
import com.linkedin.openhouse.cluster.storage.StorageType;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.internal.catalog.fileio.FileIOManager;
import java.util.Arrays;
import java.util.Collections;
import java.util.TreeSet;
import org.apache.iceberg.rest.IrcClientParsers;
import org.apache.iceberg.rest.responses.ConfigResponse;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The FileIO the Iceberg REST Catalog (IRC) config route advertises as the client's default ({@code
 * io-impl}) follows the cluster's storage (work item V3b): on a cluster of each storage type the
 * service supports ({@code cluster.storages.default-type}: hdfs, local, s3, adls) it is the class
 * the service itself reads and writes that storage's tables with, each of them Iceberg's own. One
 * Spring context per storage type; the object stores are configured but never contacted. (A cluster
 * with no storages configured is a local one; that is the other IRC tests' context, and
 * IrcReadRoutesTest asserts its config.)
 *
 * <p>Only the class is advertised: no endpoint, bucket, account or credential from the cluster's
 * storage parameters reaches the response. A storage type the route has no client class for gets no
 * {@code io-impl} at all, and the client keeps its own default.
 */
public class IrcConfigFileIoTest {

  /** What every storage type's test asks of its context. */
  abstract static class Cluster {
    @Autowired MockMvc mvc;
    @Autowired StorageManager storageManager;
    @Autowired FileIOManager fileIOManager;

    /** The config route's answer as an Iceberg client parses it. */
    ConfigResponse config() throws Exception {
      MvcResult result = IrcHttp.get(mvc, "/v1/config");
      assertEquals(200, result.getResponse().getStatus(), body(result));
      return IrcClientParsers.parse(body(result), ConfigResponse.class);
    }

    /**
     * The config route advertises {@code fileIo}, and it is the class of the FileIO the service
     * itself uses for the cluster's default storage, which is of type {@code defaultStorage}.
     */
    void advertises(StorageType.Type defaultStorage, String fileIo) throws Exception {
      assertEquals(defaultStorage, storageManager.getDefaultStorage().getType());
      assertEquals(fileIo, fileIOManager.getFileIO(defaultStorage).getClass().getName());
      ConfigResponse config = config();
      assertEquals(fileIo, config.defaults().get("io-impl"));
      // The class and the two settings the route has always sent, and nothing of the storage's.
      assertEquals(
          new TreeSet<>(Arrays.asList("io-impl", "rest-metrics-reporting-enabled")),
          new TreeSet<>(config.defaults().keySet()));
      assertEquals(Collections.singleton("openhouse.capabilities"), config.overrides().keySet());
    }
  }

  /** The test cluster with {@code properties} on top: another default storage type, configured. */
  abstract static class DefaultStorage extends PropertyOverrideContextInitializer {
    abstract String[] properties();

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
      super.initialize(applicationContext);
      TestPropertySourceUtils.addInlinedPropertiesToEnvironment(applicationContext, properties());
    }
  }

  static class HdfsDefault extends DefaultStorage {
    @Override
    String[] properties() {
      return new String[] {
        "cluster.storages.default-type=hdfs",
        "cluster.storages.types.hdfs.endpoint=file:///",
        "cluster.storages.types.hdfs.root-path=/tmp/unittest"
      };
    }
  }

  static class LocalDefault extends DefaultStorage {
    @Override
    String[] properties() {
      return new String[] {
        "cluster.storages.default-type=local",
        "cluster.storages.types.local.endpoint=file:",
        "cluster.storages.types.local.root-path=/tmp/unittest"
      };
    }
  }

  static class S3Default extends DefaultStorage {
    @Override
    String[] properties() {
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

  static class AdlsDefault extends DefaultStorage {
    @Override
    String[] properties() {
      return new String[] {
        "cluster.storages.default-type=adls",
        "cluster.storages.types.adls.endpoint=abfs://",
        "cluster.storages.types.adls.root-path=testcontainer@teststorage.dfs.core.windows.net",
        "cluster.storages.types.adls.parameters.adls.auth.shared-key.account.name=teststorage",
        "cluster.storages.types.adls.parameters.adls.auth.shared-key.account.key=dGVzdA=="
      };
    }
  }

  @SpringBootTest
  @AutoConfigureMockMvc
  @Import(IrcReadRoutesTest.StubDefaults.class)
  @ContextConfiguration(initializers = {HdfsDefault.class})
  static class HdfsCluster extends Cluster {
    @Test
    void anHdfsClusterAdvertisesHadoopFileIO() throws Exception {
      advertises(StorageType.HDFS, "org.apache.iceberg.hadoop.HadoopFileIO");
    }
  }

  @SpringBootTest
  @AutoConfigureMockMvc
  @Import(IrcReadRoutesTest.StubDefaults.class)
  @ContextConfiguration(initializers = {LocalDefault.class})
  static class LocalCluster extends Cluster {
    @Test
    void aLocalClusterAdvertisesHadoopFileIO() throws Exception {
      advertises(StorageType.LOCAL, "org.apache.iceberg.hadoop.HadoopFileIO");
    }
  }

  @SpringBootTest
  @AutoConfigureMockMvc
  @Import(IrcReadRoutesTest.StubDefaults.class)
  @ContextConfiguration(initializers = {S3Default.class})
  static class S3Cluster extends Cluster {
    @Test
    void anS3ClusterAdvertisesS3FileIO() throws Exception {
      advertises(StorageType.S3, "org.apache.iceberg.aws.s3.S3FileIO");
    }
  }

  @SpringBootTest
  @AutoConfigureMockMvc
  @Import(IrcReadRoutesTest.StubDefaults.class)
  @ContextConfiguration(initializers = {AdlsDefault.class})
  static class AdlsCluster extends Cluster {
    @Test
    void anAdlsClusterAdvertisesADLSFileIO() throws Exception {
      advertises(StorageType.ADLS, "org.apache.iceberg.azure.adlsv2.ADLSFileIO");
    }
  }

  /**
   * A deployment can add a storage type of its own. The route knows no client class for it, so it
   * names none, and the client keeps its own default.
   */
  @SpringBootTest
  @AutoConfigureMockMvc
  @Import(IrcReadRoutesTest.StubDefaults.class)
  @ContextConfiguration(initializers = {PropertyOverrideContextInitializer.class})
  static class ClusterOfAnotherStorageType extends Cluster {
    @SpyBean StorageManager spiedStorageManager;

    @Test
    void aStorageTypeWithNoClientFileIOAdvertisesNone() throws Exception {
      Storage storage = Mockito.mock(Storage.class);
      Mockito.when(storage.getType()).thenReturn(new StorageType.Type("tape"));
      Mockito.doReturn(storage).when(spiedStorageManager).getDefaultStorage();

      ConfigResponse config = config();

      assertEquals(
          Collections.singleton("rest-metrics-reporting-enabled"), config.defaults().keySet());
      assertEquals(Collections.singleton("openhouse.capabilities"), config.overrides().keySet());
    }
  }
}
