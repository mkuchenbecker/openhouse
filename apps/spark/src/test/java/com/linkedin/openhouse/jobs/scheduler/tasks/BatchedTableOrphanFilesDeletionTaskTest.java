package com.linkedin.openhouse.jobs.scheduler.tasks;

import com.linkedin.openhouse.jobs.client.JobsClient;
import com.linkedin.openhouse.jobs.client.TablesClient;
import com.linkedin.openhouse.jobs.client.model.JobConf;
import com.linkedin.openhouse.jobs.util.TableMetadata;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class BatchedTableOrphanFilesDeletionTaskTest {
  private TablesClient tablesClient;
  private JobsClient jobsClient;
  private List<TableMetadata> metadataList;

  @BeforeEach
  void setup() {
    tablesClient = Mockito.mock(TablesClient.class);
    jobsClient = Mockito.mock(JobsClient.class);
    metadataList = new ArrayList<>();
  }

  @Test
  void testGetTypeReturnsOrphanFilesDeletion() {
    BatchedTableOrphanFilesDeletionTask task =
        new BatchedTableOrphanFilesDeletionTask(jobsClient, tablesClient, metadataList, 10);
    Assertions.assertEquals(JobConf.JobTypeEnum.ORPHAN_FILES_DELETION, task.getType());
  }

  @Test
  void testGetArgsWithSingleTable() {
    TableMetadata metadata1 = Mockito.mock(TableMetadata.class);
    Mockito.when(metadata1.fqtn()).thenReturn("db.table1");
    metadataList.add(metadata1);

    BatchedTableOrphanFilesDeletionTask task =
        new BatchedTableOrphanFilesDeletionTask(jobsClient, tablesClient, metadataList, 5);

    List<String> expectedArgs = Arrays.asList("--tableNames", "db.table1", "--parallelism", "5");
    Assertions.assertEquals(expectedArgs, task.getArgs());
  }

  @Test
  void testGetArgsWithMultipleTables() {
    TableMetadata metadata1 = Mockito.mock(TableMetadata.class);
    TableMetadata metadata2 = Mockito.mock(TableMetadata.class);
    TableMetadata metadata3 = Mockito.mock(TableMetadata.class);
    Mockito.when(metadata1.fqtn()).thenReturn("db.table1");
    Mockito.when(metadata2.fqtn()).thenReturn("db.table2");
    Mockito.when(metadata3.fqtn()).thenReturn("db.table3");
    metadataList.add(metadata1);
    metadataList.add(metadata2);
    metadataList.add(metadata3);

    BatchedTableOrphanFilesDeletionTask task =
        new BatchedTableOrphanFilesDeletionTask(jobsClient, tablesClient, metadataList, 10);

    List<String> expectedArgs =
        Arrays.asList("--tableNames", "db.table1,db.table2,db.table3", "--parallelism", "10");
    Assertions.assertEquals(expectedArgs, task.getArgs());
  }

  @Test
  void testGetArgsWithDifferentParallelism() {
    TableMetadata metadata1 = Mockito.mock(TableMetadata.class);
    Mockito.when(metadata1.fqtn()).thenReturn("db.table1");
    metadataList.add(metadata1);

    BatchedTableOrphanFilesDeletionTask task =
        new BatchedTableOrphanFilesDeletionTask(jobsClient, tablesClient, metadataList, 20);

    List<String> expectedArgs = Arrays.asList("--tableNames", "db.table1", "--parallelism", "20");
    Assertions.assertEquals(expectedArgs, task.getArgs());
  }

  @Test
  void testTaskCreationWithNonEmptyList() {
    TableMetadata metadata1 = Mockito.mock(TableMetadata.class);
    Mockito.when(metadata1.fqtn()).thenReturn("db.table1");
    metadataList.add(metadata1);

    BatchedTableOrphanFilesDeletionTask task =
        new BatchedTableOrphanFilesDeletionTask(jobsClient, tablesClient, metadataList, 10);

    Assertions.assertNotNull(task);
  }

  @Test
  void testTaskCreationWithEmptyList() {
    BatchedTableOrphanFilesDeletionTask task =
        new BatchedTableOrphanFilesDeletionTask(jobsClient, tablesClient, metadataList, 10);

    Assertions.assertNotNull(task);
  }
}
