package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.internal.catalog.CatalogConstants;
import com.linkedin.openhouse.tables.api.spec.v0.request.CreateUpdateTableRequestBody;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * What a native replace reads from House Tables: the same on both Iceberg lines, because the format
 * version the replace leaves is decided on the base the replace transaction loaded, with no read of
 * its own. The reads are recorded by the stand-in of {@link IrcHouseTableReadsTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({IrcReadRoutesTest.StubDefaults.class, IrcHouseTableReadsTest.CountingStandIn.class})
@ContextConfiguration(initializers = {PropertyOverrideContextInitializer.class})
public class NativeReplaceHouseTableReadsTest {

  @Autowired MockMvc mvc;

  private final List<String[]> created = new ArrayList<>();

  @AfterEach
  void cleanUp() throws Exception {
    for (String[] key : created) {
      delete(mvc, key[0], key[1]);
    }
    created.clear();
  }

  private static CreateUpdateTableRequestBody replaceable(String db) {
    CreateUpdateTableRequestBody body = newTable(db, "t");
    Map<String, String> properties = new HashMap<>(body.getTableProperties());
    properties.put(CatalogConstants.RTAS_ENABLED_TABLE_PROP, "true");
    return body.toBuilder()
        .policies(body.getPolicies().toBuilder().replication(null).build())
        .tableProperties(properties)
        .build();
  }

  @Test
  void aStagedReplaceReadsTheTableAsManyTimesOnBothLines() throws Exception {
    String db = "replace_reads";
    created.add(new String[] {db, "t"});
    assertEquals(201, status(post(mvc, replaceable(db))));
    String stored = json(get(mvc, db, "t"));
    IrcHouseTableReadsTest.READS.clear();
    ResultActions replaced = post(mvc, bodyFromGet(stored).toBuilder().stageReplace(true).build());
    assertEquals(201, status(replaced), json(replaced));
    List<String> reads = new ArrayList<>(IrcHouseTableReadsTest.READS);
    IrcHouseTableReadsTest.READS.clear();
    assertEquals(EXPECTED_KEYED_READS, keyed(reads), reads.toString());
  }

  /**
   * The keyed reads of a staged replace: the occupancy check, the replace's own checks of the
   * stored table, and the replace transaction's load of its base.
   */
  private static final int EXPECTED_KEYED_READS = 10;

  private static long keyed(List<String> reads) {
    return reads.stream().filter(read -> read.contains("[key]")).count();
  }
}
