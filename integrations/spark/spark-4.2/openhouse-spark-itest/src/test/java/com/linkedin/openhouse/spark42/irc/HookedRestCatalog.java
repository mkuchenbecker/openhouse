package com.linkedin.openhouse.spark42.irc;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
 * Iceberg's own {@link RESTCatalog} with its own HTTP client, wrapped so that a table commit
 * ({@code POST .../namespaces/{ns}/tables/{t}} with an {@link UpdateTableRequest}) first runs the
 * step armed for that table, to completion, and records how each commit attempt ended: test only,
 * for the concurrency tests (work item X10), as {@code catalog-impl} of the writer that is to lose
 * the race. The commit was built and validated against its base before the request is sent, so what
 * the step commits lands between the writer's base and its commit, by construction. Everything else
 * is passed to the client unchanged.
 */
public class HookedRestCatalog extends RESTCatalog {
  private static final Map<String, Runnable> ARMED = new ConcurrentHashMap<>();
  private static final Map<String, List<String>> ATTEMPTS = new ConcurrentHashMap<>();
  private static final Map<String, String> RACED = new ConcurrentHashMap<>();

  public HookedRestCatalog() {
    super(
        SessionCatalog.SessionContext.createEmpty(),
        config ->
            new HookedClient(
                HTTPClient.builder(config)
                    .uri(config.get(CatalogProperties.URI))
                    .withHeaders(RESTUtil.configHeaders(config))
                    .build()));
  }

  /** Runs {@code step} before the next commit request on {@code table} ({@code ns.t}). */
  static void arm(String table, Runnable step) {
    ATTEMPTS.put(table, Collections.synchronizedList(new ArrayList<>()));
    RACED.remove(table);
    ARMED.put(table, step);
  }

  static boolean armed(String table) {
    return ARMED.containsKey(table);
  }

  static void disarm(String table) {
    ARMED.remove(table);
  }

  static List<String> attempts(String table) {
    List<String> attempts = ATTEMPTS.get(table);
    return attempts == null ? Collections.emptyList() : new ArrayList<>(attempts);
  }

  /** How the commit request the step ran before ended. */
  static String racedAttempt(String table) {
    return RACED.get(table);
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
    Runnable step = ARMED.remove(table);
    if (step != null) {
      step.run();
    }
    String outcome = "committed";
    try {
      return call.run();
    } catch (RuntimeException e) {
      outcome = e.getClass().getSimpleName() + ": " + e.getMessage();
      throw e;
    } finally {
      List<String> attempts = ATTEMPTS.get(table);
      if (attempts != null) {
        attempts.add(outcome);
      }
      if (step != null) {
        RACED.put(table, outcome);
      }
    }
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
