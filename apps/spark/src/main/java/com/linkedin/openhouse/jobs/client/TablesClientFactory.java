package com.linkedin.openhouse.jobs.client;

import com.google.common.annotations.VisibleForTesting;
import com.linkedin.openhouse.client.ssl.TablesApiClientFactory;
import com.linkedin.openhouse.client.ssl.WebClientFactory;
import com.linkedin.openhouse.cluster.storage.filesystem.FsStorageProvider;
import com.linkedin.openhouse.jobs.util.DatabaseTableFilter;
import com.linkedin.openhouse.jobs.util.RetryUtil;
import com.linkedin.openhouse.tables.client.api.DatabaseApi;
import com.linkedin.openhouse.tables.client.api.TableApi;
import com.linkedin.openhouse.tables.client.invoker.ApiClient;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import javax.annotation.Nullable;
import javax.net.ssl.SSLException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.retry.support.RetryTemplate;

/** A factory class for {@link TablesClient}. */
@Slf4j
@RequiredArgsConstructor
public class TablesClientFactory {
  /**
   * Declared on every request where the Spark catalogs of the same Iceberg line declare it ({@link
   * #declaresColumnDefaults()}). The client reads only table metadata, never rows, but the jobs it
   * schedules read through those catalogs, so it sees exactly the tables they can use.
   */
  private static final String COLUMN_DEFAULTS = "column-defaults";

  /**
   * {@code com.linkedin.openhouse.javaclient.IcebergLine}, the client's one Iceberg-line detector.
   * Not on this module's compile classpath (the OpenHouse Spark runtime is compile-only and does
   * not expose the Java client), so it is loaded by name. Joined from parts so the uber jar's
   * shadow relocation, which rewrites string literals that look like class names, leaves it.
   */
  private static final String ICEBERG_LINE_CLASS =
      String.join(".", "com", "linkedin", "openhouse", "javaclient", "IcebergLine");

  private final String basePath;
  private final DatabaseTableFilter filter;
  private final @Nullable String token;
  protected final FsStorageProvider fsStorageProvider;
  private boolean systemAction;

  /** Makes clients created afterwards declare SYSTEM on every request. */
  public TablesClientFactory withSystemAction(boolean systemAction) {
    this.systemAction = systemAction;
    return this;
  }

  public TablesClient create() {
    return create(RetryUtil.getTablesApiRetryTemplate());
  }

  private TablesClient create(RetryTemplate retryTemplate) {
    ApiClient client = null;
    try {
      client = TablesApiClientFactory.getInstance().createApiClient(basePath, token, null);
    } catch (MalformedURLException | SSLException e) {
      throw new RuntimeException(
          "Tables Client initialization failed: Failure while initializing ApiClient", e);
    }
    client.setBasePath(basePath);
    if (declaresColumnDefaults()) {
      client.addDefaultHeader(WebClientFactory.HTTP_HEADER_CLIENT_CAPABILITIES, COLUMN_DEFAULTS);
    }
    if (systemAction) {
      client.addDefaultHeader(
          WebClientFactory.HTTP_HEADER_ACTION_TYPE, WebClientFactory.ACTION_TYPE_SYSTEM);
    }
    return create(retryTemplate, new TableApi(client), new DatabaseApi(client));
  }

  /**
   * Whether clients declare {@code column-defaults}: by the same rule as the Spark catalogs
   * (decision D11a), so on the legacy Iceberg line as before and on the v3 line not. Detected once.
   */
  static boolean declaresColumnDefaults() {
    return DeclaresColumnDefaults.VALUE;
  }

  /**
   * {@code IcebergLine.current().usesReadBridge()} for the class named {@code icebergLineClass}.
   * When that class or the Iceberg it probes cannot be loaded, as in the scheduler's own uber jar,
   * which ships neither, the line is unknown and the client declares {@code column-defaults} as it
   * did before the rule existed, with a warning.
   */
  @VisibleForTesting
  static boolean declaresColumnDefaults(String icebergLineClass) {
    try {
      Class<?> line =
          Class.forName(icebergLineClass, true, TablesClientFactory.class.getClassLoader());
      Object current = line.getMethod("current").invoke(null);
      return (Boolean) line.getMethod("usesReadBridge").invoke(current);
    } catch (ClassNotFoundException | LinkageError e) {
      return undetected(e);
    } catch (InvocationTargetException e) {
      if (e.getCause() instanceof LinkageError) {
        return undetected(e.getCause());
      }
      throw new IllegalStateException("Cannot detect the Iceberg line", e.getCause());
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Cannot detect the Iceberg line", e);
    }
  }

  private static boolean undetected(Throwable cause) {
    log.warn(
        "Iceberg line not detectable ({}); declaring {} as on the legacy line",
        cause.toString(),
        COLUMN_DEFAULTS);
    return true;
  }

  private static final class DeclaresColumnDefaults {
    private static final boolean VALUE = declaresColumnDefaults(ICEBERG_LINE_CLASS);
  }

  public TablesClient create(RetryTemplate retryTemplate, TableApi tableApi, DatabaseApi dbApi) {
    return new TablesClient(
        retryTemplate, tableApi, dbApi, filter, new StorageClient(fsStorageProvider));
  }

  @VisibleForTesting
  public TablesClient create(
      RetryTemplate retryTemplate, TableApi tableApi, DatabaseApi dbApi, StorageClient client) {
    return new TablesClient(retryTemplate, tableApi, dbApi, filter, client);
  }
}
