package com.linkedin.openhouse.tablestest;

import com.linkedin.openhouse.internal.catalog.OpenHouseInternalCatalog;
import com.linkedin.openhouse.internal.catalog.model.HouseTable;
import com.linkedin.openhouse.internal.catalog.view.ViewCommitEngine;
import com.linkedin.openhouse.internal.catalog.view.model.ViewCommitIntent;
import com.linkedin.openhouse.internal.catalog.view.model.ViewCommitResult;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.apache.iceberg.catalog.Catalog;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The view beans and the view commit contract of the embedded server. Both fixtures compile and run
 * this.
 *
 * <p>It does not audit bean signatures for Iceberg view types or check which views service is
 * registered on which Iceberg line: those checks guarded the Iceberg 1.2 line, which the build no
 * longer has.
 */
public class IcebergViewBeanBoundaryTest {

  private ConfigurableApplicationContext boot() {
    try {
      org.apache.catalina.webresources.TomcatURLStreamHandlerFactory.register();
    } catch (Error e) {
      org.apache.catalina.webresources.TomcatURLStreamHandlerFactory.disable();
    }
    SpringApplication application = new SpringApplication(SpringH2TestApplication.class);
    application.setDefaultProperties(Collections.singletonMap("server.port", "0"));
    return application.run();
  }

  /**
   * Directly exercises the nullable create-flag / base-row builder contract, and that the retired
   * operation and null-token APIs are gone.
   */
  @Test
  public void theVersionNeutralIntentExposesTheCreateFlagAndBaseRowAndDropsTheOldApis() {
    HouseTable base =
        HouseTable.builder()
            .databaseId("db")
            .tableId("v")
            .tableLocation("/loc/00001-a.metadata.json")
            .storageType("local")
            .entityType("VIEW")
            .build();
    Assertions.assertEquals("/loc/00001-a.metadata.json", base.getTableLocation());

    ViewCommitIntent replace =
        ViewCommitIntent.builder()
            .databaseId("db")
            .viewId("v")
            .isCreate(false)
            .baseRow(base)
            .build();
    Assertions.assertEquals(Boolean.FALSE, replace.getIsCreate());
    Assertions.assertSame(base, replace.getBaseRow());

    // An unchanged toBuilder round-trip retains both new fields (the two-runtime retention
    // guarantee).
    ViewCommitIntent retained = replace.toBuilder().build();
    Assertions.assertEquals(
        Boolean.FALSE, retained.getIsCreate(), "toBuilder retains the create flag");
    Assertions.assertSame(base, retained.getBaseRow(), "toBuilder retains the captured row");

    // toBuilder round-trips the new fields and can flip the flag and clear the row.
    ViewCommitIntent create = replace.toBuilder().isCreate(true).baseRow(null).build();
    Assertions.assertEquals(Boolean.TRUE, create.getIsCreate());
    Assertions.assertNull(create.getBaseRow());

    // The boxed flag round-trips true, false, and null (an omitted flag is null, never a default).
    Assertions.assertEquals(
        Boolean.TRUE, ViewCommitIntent.builder().isCreate(true).build().getIsCreate());
    Assertions.assertEquals(
        Boolean.FALSE, ViewCommitIntent.builder().isCreate(false).build().getIsCreate());
    Assertions.assertNull(
        ViewCommitIntent.builder().isCreate(null).build().getIsCreate(),
        "a null create flag stays null");
    Assertions.assertNull(
        ViewCommitIntent.builder().build().getIsCreate(), "an omitted create flag is null");

    // The retired operation enum API is gone: no getOperation getter, no operation builder method.
    Assertions.assertTrue(
        Arrays.stream(ViewCommitIntent.class.getMethods())
            .noneMatch(method -> method.getName().equals("getOperation")),
        "the retired getOperation getter must not exist on the intent");
    Assertions.assertTrue(
        Arrays.stream(ViewCommitIntent.ViewCommitIntentBuilder.class.getMethods())
            .noneMatch(method -> method.getName().equals("operation")),
        "the retired operation builder method must not exist");

    // The retired null-token API is also gone: no getter on the value, no method on the builder.
    Assertions.assertTrue(
        Arrays.stream(ViewCommitIntent.class.getMethods())
            .noneMatch(method -> method.getName().equals("getBaseViewVersion")),
        "the retired baseViewVersion getter must not exist on the intent");
    Assertions.assertTrue(
        Arrays.stream(ViewCommitIntent.ViewCommitIntentBuilder.class.getMethods())
            .noneMatch(method -> method.getName().equals("baseViewVersion")),
        "the retired baseViewVersion builder method must not exist");
  }

  /**
   * The contract is metadata publication only, with a signature that names no Iceberg view type.
   */
  @Test
  public void theEngineContractDeclaresOnlyTheCommitSignature() {
    List<Method> operations = new ArrayList<>();
    for (Method method : ViewCommitEngine.class.getMethods()) {
      if (!method.isSynthetic()) {
        operations.add(method);
      }
    }
    Assertions.assertEquals(
        1, operations.size(), "the engine contract must expose only commit: " + operations);
    Method commit = operations.get(0);
    Assertions.assertEquals("commit", commit.getName());
    Assertions.assertEquals(
        Collections.singletonList(ViewCommitIntent.class),
        Arrays.asList(commit.getParameterTypes()));
    Assertions.assertEquals(ViewCommitResult.class, commit.getReturnType());
  }

  @Test
  public void unqualifiedCatalogStillResolvesToTheTableCatalog() {
    try (ConfigurableApplicationContext context = boot()) {
      Assertions.assertTrue(
          context.getBean(Catalog.class) instanceof OpenHouseInternalCatalog,
          "an unqualified Catalog lookup must keep resolving to OpenHouseInternalCatalog");
    }
  }
}
