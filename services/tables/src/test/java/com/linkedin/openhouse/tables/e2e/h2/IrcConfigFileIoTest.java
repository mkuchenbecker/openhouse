package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.IrcHttp.body;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.linkedin.openhouse.cluster.storage.StorageManager;
import com.linkedin.openhouse.cluster.storage.StorageType;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.internal.catalog.fileio.FileIOManager;
import org.apache.iceberg.rest.IrcClientParsers;
import org.apache.iceberg.rest.responses.ConfigResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The FileIO the Iceberg REST Catalog (IRC) config route advertises as the client's default ({@code
 * io-impl}), on a cluster of each storage type the service supports ({@code
 * cluster.storages.default-type}: hdfs, local, s3, adls). One Spring context per storage type; the
 * object stores are configured but never contacted. (A cluster with no storages configured is a
 * local one; that is the other IRC tests' context, and IrcReadRoutesTest asserts its config.)
 *
 * <p>As it is today (work item V3b): the route advertises {@code HadoopFileIO} whatever the
 * cluster's storage, although the service itself reads and writes an S3 cluster's tables through
 * {@code S3FileIO} and an ADLS cluster's through {@code ADLSFileIO}.
 */
public class IrcConfigFileIoTest {

  static final String HADOOP_FILE_IO = "org.apache.iceberg.hadoop.HadoopFileIO";
  static final String S3_FILE_IO = "org.apache.iceberg.aws.s3.S3FileIO";
  static final String ADLS_FILE_IO = "org.apache.iceberg.azure.adlsv2.ADLSFileIO";

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

    /** The class of the FileIO the service itself uses for the cluster's default storage. */
    String serviceFileIo(StorageType.Type expectedDefault) {
      assertEquals(expectedDefault, storageManager.getDefaultStorage().getType());
      return fileIOManager.getFileIO(expectedDefault).getClass().getName();
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
      assertEquals(HADOOP_FILE_IO, serviceFileIo(StorageType.HDFS));
      assertEquals(HADOOP_FILE_IO, config().defaults().get("io-impl"));
    }
  }

  @SpringBootTest
  @AutoConfigureMockMvc
  @Import(IrcReadRoutesTest.StubDefaults.class)
  @ContextConfiguration(initializers = {LocalDefault.class})
  static class LocalCluster extends Cluster {
    @Test
    void aLocalClusterAdvertisesHadoopFileIO() throws Exception {
      assertEquals(HADOOP_FILE_IO, serviceFileIo(StorageType.LOCAL));
      assertEquals(HADOOP_FILE_IO, config().defaults().get("io-impl"));
    }
  }

  @SpringBootTest
  @AutoConfigureMockMvc
  @Import(IrcReadRoutesTest.StubDefaults.class)
  @ContextConfiguration(initializers = {S3Default.class})
  static class S3Cluster extends Cluster {
    @Test
    void anS3ClusterAdvertisesHadoopFileIOAlthoughTheServiceUsesS3FileIO() throws Exception {
      assertEquals(S3_FILE_IO, serviceFileIo(StorageType.S3));
      assertEquals(HADOOP_FILE_IO, config().defaults().get("io-impl"));
    }
  }

  @SpringBootTest
  @AutoConfigureMockMvc
  @Import(IrcReadRoutesTest.StubDefaults.class)
  @ContextConfiguration(initializers = {AdlsDefault.class})
  static class AdlsCluster extends Cluster {
    @Test
    void anAdlsClusterAdvertisesHadoopFileIOAlthoughTheServiceUsesADLSFileIO() throws Exception {
      assertEquals(ADLS_FILE_IO, serviceFileIo(StorageType.ADLS));
      assertEquals(HADOOP_FILE_IO, config().defaults().get("io-impl"));
    }
  }
}
