package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.*;
import static com.linkedin.openhouse.tables.e2e.h2.FormatV3.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mockingDetails;

import com.linkedin.openhouse.common.audit.AuditHandler;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.tables.audit.model.OperationStatus;
import com.linkedin.openhouse.tables.audit.model.OperationType;
import com.linkedin.openhouse.tables.audit.model.TableAuditEvent;
import com.linkedin.openhouse.tables.formatversion.IcebergV3;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.iceberg.MetadataUpdate;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.catalog.Catalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

/**
 * An upgrade of a table to format v3 cannot be undone and cuts the table off from LinkedIn's
 * Iceberg 1.5.2 readers, so the write that makes it is followed in the table audit by an event of
 * its own, {@link OperationType#UPGRADE_FORMAT_VERSION}, and in the log by a warning that names the
 * table, both versions and the principal. Native and Iceberg REST upgrades alike; a write that does
 * not upgrade gets no such event.
 */
@SpringBootTest(properties = SWITCH_ON)
@AutoConfigureMockMvc
@Import(IrcReadRoutesTest.StubDefaults.class)
@ContextConfiguration(initializers = {PropertyOverrideContextInitializer.class})
@ExtendWith(OutputCaptureExtension.class)
public class FormatV3UpgradeAuditTest {

  @Autowired MockMvc mvc;
  @Autowired Catalog catalog;
  @Autowired AuditHandler<TableAuditEvent> tableAuditHandler;

  private final List<String> created = new ArrayList<>();

  @AfterEach
  void cleanUp() throws Exception {
    for (String db : created) {
      delete(mvc, db, "t");
    }
    created.clear();
  }

  private String make(String db) throws Exception {
    created.add(db);
    assertEquals(201, status(post(mvc, plain(db))));
    return json(get(mvc, db, "t"));
  }

  /** The audit events sent since the last call, as {@code TYPE STATUS}, for {@code db} only. */
  private List<String> audited(String db) {
    List<String> events =
        mockingDetails(tableAuditHandler).getInvocations().stream()
            .map(invocation -> (TableAuditEvent) invocation.getArgument(0))
            .filter(event -> db.equals(event.getDatabaseName()))
            .filter(event -> event.getOperationType() != OperationType.READ)
            .map(event -> event.getOperationType() + " " + event.getOperationStatus())
            .collect(Collectors.toList());
    clearInvocations(tableAuditHandler);
    return events;
  }

  @Test
  void aNativeUpgradeIsAuditedAndLoggedAfterTheUpdate(CapturedOutput output) throws Exception {
    assumeTrue(IcebergV3.supported());
    String db = "fv3_audit_native";
    String v2 = make(db);
    audited(db);

    assertEquals(200, status(put(mvc, asking(bodyFromGet(v2), "3"))));
    assertEquals(
        java.util.Arrays.asList(
            OperationType.UPDATE + " " + OperationStatus.SUCCESS,
            OperationType.UPGRADE_FORMAT_VERSION + " " + OperationStatus.SUCCESS),
        audited(db));
    assertTrue(
        output.getOut().contains("Table " + db + ".t was upgraded from format version 2 to 3"),
        "no upgrade log line");

    // A later write to the v3 table upgrades nothing, and is audited as itself only.
    String v3 = json(get(mvc, db, "t"));
    assertEquals(200, status(put(mvc, withProp(v3, "user.note", "x"))));
    assertEquals(
        Collections.singletonList(OperationType.UPDATE + " " + OperationStatus.SUCCESS),
        audited(db));
  }

  @Test
  void anIcebergRestUpgradeIsAuditedAfterTheCommit() throws Exception {
    assumeTrue(IcebergV3.supported());
    String db = "fv3_audit_irc";
    make(db);
    audited(db);

    TableMetadata base = IrcWrite.load(mvc, db, "t");
    IrcWrite.commit(
        mvc,
        db,
        "t",
        IrcWrite.simple(
            base, Collections.singletonList(new MetadataUpdate.UpgradeFormatVersion(3))));
    assertEquals(3, metadata(catalog, db, "t").formatVersion());
    assertEquals(
        java.util.Arrays.asList(
            OperationType.UPDATE + " " + OperationStatus.SUCCESS,
            OperationType.UPGRADE_FORMAT_VERSION + " " + OperationStatus.SUCCESS),
        audited(db));
  }

  /** A v2 table's write that upgrades nothing has no upgrade event, nor does a refused upgrade. */
  @Test
  void aWriteThatUpgradesNothingHasNoUpgradeEvent() throws Exception {
    String db = "fv3_audit_none";
    String v2 = make(db);
    audited(db);
    assertEquals(200, status(put(mvc, withProp(v2, "user.note", "x"))));
    assertEquals(
        Collections.singletonList(OperationType.UPDATE + " " + OperationStatus.SUCCESS),
        audited(db));
    String v2b = json(get(mvc, db, "t"));
    assertEquals(400, status(put(mvc, asking(bodyFromGet(v2b), "4"))));
    assertEquals(
        Collections.singletonList(OperationType.UPDATE + " " + OperationStatus.FAILED),
        audited(db));
  }
}
