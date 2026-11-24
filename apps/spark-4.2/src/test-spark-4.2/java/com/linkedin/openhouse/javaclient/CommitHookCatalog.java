package com.linkedin.openhouse.javaclient;

import java.io.IOException;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.catalog.SessionCatalog;
import org.apache.iceberg.rest.HTTPClient;
import org.apache.iceberg.rest.ParserContext;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.rest.RESTClient;
import org.apache.iceberg.rest.RESTRequest;
import org.apache.iceberg.rest.RESTResponse;
import org.apache.iceberg.rest.RESTUtil;
import org.apache.iceberg.rest.auth.AuthSession;
import org.apache.iceberg.rest.requests.UpdateTableRequest;
import org.apache.iceberg.rest.responses.ErrorResponse;

/**
 * The Spark 4.2 counterpart of apps/spark-3.5's class of the same name: on Spark 4.2 there is no
 * OpenHouse catalog, so this is Iceberg's own {@link RESTCatalog} whose HTTP client runs {@link
 * CommitHook}'s armed step before each table commit request ({@code POST .../tables/{t}} with an
 * {@link UpdateTableRequest}) and records each attempt's outcome, as the Spark 4.2 itest's
 * HookedRestCatalog does. Test only, set as {@code catalog-impl} on the session of the writer that
 * is to lose the race.
 */
public class CommitHookCatalog extends RESTCatalog {

  public CommitHookCatalog() {
    super(
        SessionCatalog.SessionContext.createEmpty(),
        config ->
            new HookedClient(
                HTTPClient.builder(config)
                    .uri(config.get(CatalogProperties.URI))
                    .withHeaders(RESTUtil.configHeaders(config))
                    .build()));
  }

  /** {@code ns.t} for a table commit's path, or {@code null}. */
  private static String tableOf(String path, RESTRequest body) {
    if (!(body instanceof UpdateTableRequest)) {
      return null;
    }
    String[] parts = path.split("/");
    int tables = -1;
    for (int i = 0; i < parts.length; i++) {
      if ("tables".equals(parts[i])) {
        tables = i;
      }
    }
    if (tables < 1 || tables + 1 >= parts.length) {
      return null;
    }
    return RESTUtil.decodeString(parts[tables - 1])
        + "."
        + RESTUtil.decodeString(parts[tables + 1]);
  }

  private interface Call<T> {
    T run();
  }

  private static <T> T commit(String path, RESTRequest body, Call<T> call) {
    String table = tableOf(path, body);
    if (table == null) {
      return call.run();
    }
    CommitHook.beforeAttempt(table);
    T result;
    try {
      result = call.run();
    } catch (RuntimeException e) {
      CommitHook.afterAttempt(table, e);
      throw e;
    }
    CommitHook.afterAttempt(table, null);
    return result;
  }

  /** Passes every call to {@code delegate}; a table commit goes through {@link #commit}. */
  private static final class HookedClient implements RESTClient {
    private final RESTClient delegate;

    HookedClient(RESTClient delegate) {
      this.delegate = delegate;
    }

    @Override
    public void head(
        String path, Supplier<Map<String, String>> headers, Consumer<ErrorResponse> errorHandler) {
      delegate.head(path, headers, errorHandler);
    }

    @Override
    public void head(
        String path, Map<String, String> headers, Consumer<ErrorResponse> errorHandler) {
      delegate.head(path, headers, errorHandler);
    }

    @Override
    public <T extends RESTResponse> T delete(
        String path,
        Map<String, String> queryParams,
        Class<T> responseType,
        Supplier<Map<String, String>> headers,
        Consumer<ErrorResponse> errorHandler) {
      return delegate.delete(path, queryParams, responseType, headers, errorHandler);
    }

    @Override
    public <T extends RESTResponse> T delete(
        String path,
        Class<T> responseType,
        Supplier<Map<String, String>> headers,
        Consumer<ErrorResponse> errorHandler) {
      return delegate.delete(path, responseType, headers, errorHandler);
    }

    @Override
    public <T extends RESTResponse> T delete(
        String path,
        Class<T> responseType,
        Map<String, String> headers,
        Consumer<ErrorResponse> errorHandler) {
      return delegate.delete(path, responseType, headers, errorHandler);
    }

    @Override
    public <T extends RESTResponse> T delete(
        String path,
        Map<String, String> queryParams,
        Class<T> responseType,
        Map<String, String> headers,
        Consumer<ErrorResponse> errorHandler) {
      return delegate.delete(path, queryParams, responseType, headers, errorHandler);
    }

    @Override
    public <T extends RESTResponse> T get(
        String path,
        Class<T> responseType,
        Supplier<Map<String, String>> headers,
        Consumer<ErrorResponse> errorHandler) {
      return delegate.get(path, responseType, headers, errorHandler);
    }

    @Override
    public <T extends RESTResponse> T get(
        String path,
        Class<T> responseType,
        Map<String, String> headers,
        Consumer<ErrorResponse> errorHandler) {
      return delegate.get(path, responseType, headers, errorHandler);
    }

    @Override
    public <T extends RESTResponse> T get(
        String path,
        Map<String, String> queryParams,
        Class<T> responseType,
        Supplier<Map<String, String>> headers,
        Consumer<ErrorResponse> errorHandler) {
      return delegate.get(path, queryParams, responseType, headers, errorHandler);
    }

    @Override
    public <T extends RESTResponse> T get(
        String path,
        Map<String, String> queryParams,
        Class<T> responseType,
        Map<String, String> headers,
        Consumer<ErrorResponse> errorHandler,
        ParserContext parserContext) {
      return delegate.get(path, queryParams, responseType, headers, errorHandler, parserContext);
    }

    @Override
    public <T extends RESTResponse> T get(
        String path,
        Map<String, String> queryParams,
        Class<T> responseType,
        Supplier<Map<String, String>> headers,
        Consumer<ErrorResponse> errorHandler,
        Consumer<Map<String, String>> responseHeaders) {
      return delegate.get(path, queryParams, responseType, headers, errorHandler, responseHeaders);
    }

    @Override
    public <T extends RESTResponse> T get(
        String path,
        Map<String, String> queryParams,
        Class<T> responseType,
        Map<String, String> headers,
        Consumer<ErrorResponse> errorHandler,
        Consumer<Map<String, String>> responseHeaders) {
      return delegate.get(path, queryParams, responseType, headers, errorHandler, responseHeaders);
    }

    @Override
    public <T extends RESTResponse> T get(
        String path,
        Map<String, String> queryParams,
        Class<T> responseType,
        Map<String, String> headers,
        Consumer<ErrorResponse> errorHandler) {
      return delegate.get(path, queryParams, responseType, headers, errorHandler);
    }

    @Override
    public <T extends RESTResponse> T post(
        String path,
        RESTRequest body,
        Class<T> responseType,
        Supplier<Map<String, String>> headers,
        Consumer<ErrorResponse> errorHandler) {
      return commit(
          path, body, () -> delegate.post(path, body, responseType, headers, errorHandler));
    }

    @Override
    public <T extends RESTResponse> T post(
        String path,
        RESTRequest body,
        Class<T> responseType,
        Supplier<Map<String, String>> headers,
        Consumer<ErrorResponse> errorHandler,
        Consumer<Map<String, String>> responseHeaders) {
      return commit(
          path,
          body,
          () -> delegate.post(path, body, responseType, headers, errorHandler, responseHeaders));
    }

    @Override
    public <T extends RESTResponse> T post(
        String path,
        RESTRequest body,
        Class<T> responseType,
        Map<String, String> headers,
        Consumer<ErrorResponse> errorHandler,
        Consumer<Map<String, String>> responseHeaders,
        ParserContext parserContext) {
      return commit(
          path,
          body,
          () ->
              delegate.post(
                  path, body, responseType, headers, errorHandler, responseHeaders, parserContext));
    }

    @Override
    public <T extends RESTResponse> T post(
        String path,
        RESTRequest body,
        Class<T> responseType,
        Map<String, String> headers,
        Consumer<ErrorResponse> errorHandler,
        Consumer<Map<String, String>> responseHeaders) {
      return commit(
          path,
          body,
          () -> delegate.post(path, body, responseType, headers, errorHandler, responseHeaders));
    }

    @Override
    public <T extends RESTResponse> T post(
        String path,
        RESTRequest body,
        Class<T> responseType,
        Map<String, String> headers,
        Consumer<ErrorResponse> errorHandler) {
      return commit(
          path, body, () -> delegate.post(path, body, responseType, headers, errorHandler));
    }

    @Override
    public <T extends RESTResponse> T postForm(
        String path,
        Map<String, String> formData,
        Class<T> responseType,
        Supplier<Map<String, String>> headers,
        Consumer<ErrorResponse> errorHandler) {
      return delegate.postForm(path, formData, responseType, headers, errorHandler);
    }

    @Override
    public <T extends RESTResponse> T postForm(
        String path,
        Map<String, String> formData,
        Class<T> responseType,
        Map<String, String> headers,
        Consumer<ErrorResponse> errorHandler) {
      return delegate.postForm(path, formData, responseType, headers, errorHandler);
    }

    @Override
    public RESTClient withAuthSession(AuthSession session) {
      return new HookedClient(delegate.withAuthSession(session));
    }

    @Override
    public void close() throws IOException {
      delegate.close();
    }
  }
}
