package com.linkedin.openhouse.jobs.spark;

import com.google.common.annotations.VisibleForTesting;
import com.linkedin.openhouse.client.ssl.WebClientFactory;
import com.linkedin.openhouse.common.metrics.OtelEmitter;
import com.linkedin.openhouse.jobs.spark.state.StateManager;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import lombok.extern.slf4j.Slf4j;
import org.apache.spark.sql.SparkSession;

/**
 * Base app class implemented common operations, e.g. heartbeats, state update, Spark session
 * creation, args parsing.
 */
@Slf4j
public abstract class BaseSparkApp extends BaseApp {
  protected static final String METRICS_SCOPE = BaseSparkApp.class.getName();
  private final ScheduledExecutorService scheduledExecutorService =
      Executors.newSingleThreadScheduledExecutor();

  protected BaseSparkApp(String jobId, StateManager stateManager, OtelEmitter otelEmitter) {
    super(jobId, stateManager, HEARTBEAT_INTERVAL_SECONDS_DEFAULT, otelEmitter);
  }

  protected BaseSparkApp(
      String jobId,
      StateManager stateManager,
      long heartbeatIntervalSeconds,
      OtelEmitter otelEmitter) {
    super(jobId, stateManager, heartbeatIntervalSeconds, otelEmitter);
  }

  public void run() {
    log.info("Running");
    String className = this.getClass().getSimpleName();
    Throwable failure = null;
    try (Operations ops =
        Operations.withCatalog(
            declareSystemActionOverRest(sessionBuilder(className).getOrCreate()), otelEmitter)) {
      log.info("Session created");
      onStarted();
      runInner(ops);
      runValidations(ops);
    } catch (Throwable e) {
      log.error("Run failed, reason: {}", e.getMessage(), e);
      failure = e;
    } finally {
      onFinished(failure == null);
    }
    if (failure != null) {
      throw new RuntimeException(String.format("Job %s failed", jobId), failure);
    }
  }

  /**
   * Whether the app declares SYSTEM, so it also maintains SYSTEM_ONLY-locked tables. A catalog
   * already loaded by an existing session ignores it.
   */
  protected boolean isSystemAction() {
    return false;
  }

  private SparkSession.Builder sessionBuilder(String appName) {
    SparkSession.Builder builder = SparkSession.builder().appName(appName);
    return isSystemAction()
        ? builder.config(
            "spark.sql.catalog.openhouse.action-type", WebClientFactory.ACTION_TYPE_SYSTEM)
        : builder;
  }

  /** The catalog property that makes Iceberg's RESTCatalog send the SYSTEM action header. */
  static final String REST_ACTION_TYPE_HEADER =
      "spark.sql.catalog.openhouse.header." + WebClientFactory.HTTP_HEADER_ACTION_TYPE;

  /**
   * For an app that declares SYSTEM ({@link #isSystemAction()}) on a session whose {@code
   * openhouse} catalog is Iceberg's RESTCatalog (a platform that reaches OpenHouse through the
   * tables service's Iceberg REST routes): RESTCatalog does not read {@code action-type}, the
   * OpenHouse catalog's property, so the header is declared with RESTCatalog's {@code header.}
   * property as well. With the OpenHouse catalog nothing is added, so it sends what it always did.
   * Set before the session first loads the catalog, as {@code action-type} is.
   */
  @VisibleForTesting
  SparkSession declareSystemActionOverRest(SparkSession spark) {
    if (isSystemAction() && Operations.isRestCatalog(spark)) {
      spark.conf().set(REST_ACTION_TYPE_HEADER, WebClientFactory.ACTION_TYPE_SYSTEM);
    }
    return spark;
  }

  /**
   * runValidation performs post job validations. Override it to implement job specific validations
   */
  protected void runValidations(Operations ops) {
    log.info("Default validation for spark app {}", this.getClass().getSimpleName());
  }

  protected abstract void runInner(Operations ops) throws Exception;
}
