package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.linkedin.openhouse.cluster.configs.ClusterProperties;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.internal.catalog.CatalogConstants;
import com.linkedin.openhouse.tables.api.spec.v0.request.CreateUpdateTableRequestBody;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.iceberg.BaseTable;
import org.apache.iceberg.TableMetadataParser;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * A cluster that creates tables at format version 1 ({@code cluster.iceberg.format-version=1}),
 * with the format v3 switch off: a create that carries its client's schema ({@code
 * client.table.schema}, as a CTAS or a replica create sends it) is rebuilt from empty metadata,
 * which is format v2 on both Iceberg lines, and is created at v2, as it always was. A create
 * without it is created at v1.
 */
@SpringBootTest(properties = "cluster.iceberg.format-version=1")
@AutoConfigureMockMvc
@Import(IrcReadRoutesTest.StubDefaults.class)
@ContextConfiguration(initializers = {PropertyOverrideContextInitializer.class})
public class FormatVersionV1ClusterTest {

  @Autowired MockMvc mvc;
  @Autowired Catalog catalog;
  @Autowired ClusterProperties clusterProperties;

  private final List<String> created = new ArrayList<>();

  @AfterEach
  void cleanUp() throws Exception {
    for (String db : created) {
      delete(mvc, db, "t");
    }
    created.clear();
  }

  private static CreateUpdateTableRequestBody plain(String db) {
    return newTable(db, "t")
        .toBuilder()
        .policies(null)
        .timePartitioning(null)
        .clustering(null)
        .build();
  }

  private int version(String db) {
    return ((BaseTable) catalog.loadTable(TableIdentifier.of(db, "t")))
        .operations()
        .current()
        .formatVersion();
  }

  @Test
  void aCreateWithItsClientsSchemaIsCreatedAtV2AndOneWithoutAtV1() throws Exception {
    created.add("fv1_plain");
    assertEquals(201, status(post(mvc, plain("fv1_plain"))));
    assertEquals(1, version("fv1_plain"));

    created.add("fv1_ctas");
    CreateUpdateTableRequestBody body = plain("fv1_ctas");
    Map<String, String> properties = new HashMap<>(body.getTableProperties());
    properties.put("client.table.schema", body.getSchema());
    ResultActions ctas = post(mvc, body.toBuilder().tableProperties(properties).build());
    assertEquals(201, status(ctas), json(ctas));
    assertEquals(2, version("fv1_ctas"));
  }

  private static CreateUpdateTableRequestBody replaceable(CreateUpdateTableRequestBody body) {
    Map<String, String> properties = new HashMap<>(body.getTableProperties());
    properties.put(CatalogConstants.RTAS_ENABLED_TABLE_PROP, "true");
    return body.toBuilder().tableProperties(properties).build();
  }

  /**
   * A replace is computed as a create was: at the cluster's version. So a replace of a v2 table
   * (one rebuilt from its client's schema) on this v1 cluster is Iceberg's refusal of a downgrade,
   * answered 500, as on LinkedIn main, whose replace asked Iceberg for the cluster's version.
   */
  @Test
  void aReplaceOfAV2TableOnAV1ClusterIsIcebergsRefusalOfADowngrade() throws Exception {
    created.add("fv1_replace_v2");
    CreateUpdateTableRequestBody body = replaceable(plain("fv1_replace_v2"));
    Map<String, String> properties = new HashMap<>(body.getTableProperties());
    properties.put("client.table.schema", body.getSchema());
    assertEquals(201, status(post(mvc, body.toBuilder().tableProperties(properties).build())));
    assertEquals(2, version("fv1_replace_v2"));
    String stored = json(get(mvc, "fv1_replace_v2", "t"));
    ResultActions replaced = post(mvc, bodyFromGet(stored).toBuilder().stageReplace(true).build());
    assertEquals(500, status(replaced), json(replaced));
    assertTrue(message(replaced).contains("Cannot downgrade v2 table to v1"), message(replaced));
    assertEquals(2, version("fv1_replace_v2"));
  }

  /**
   * A replace of a v1 table on a cluster at v2 gives a v2 table, as on LinkedIn main, whose replace
   * asked Iceberg for the cluster's version.
   */
  @Test
  void aReplaceOfAV1TableOnAV2ClusterUpgradesItToV2() throws Exception {
    created.add("fv1_replace_v1");
    assertEquals(201, status(post(mvc, replaceable(plain("fv1_replace_v1")))));
    assertEquals(1, version("fv1_replace_v1"));
    String stored = json(get(mvc, "fv1_replace_v1", "t"));
    ReflectionTestUtils.setField(clusterProperties, "clusterIcebergFormatVersion", 2);
    try {
      ResultActions replaced =
          post(mvc, bodyFromGet(stored).toBuilder().stageReplace(true).build());
      assertEquals(201, status(replaced), json(replaced));
      assertEquals(
          2,
          TableMetadataParser.read(IrcWrite.IO, str(json(replaced), "$.tableLocation"))
              .formatVersion());
    } finally {
      ReflectionTestUtils.setField(clusterProperties, "clusterIcebergFormatVersion", 1);
    }
  }
}
