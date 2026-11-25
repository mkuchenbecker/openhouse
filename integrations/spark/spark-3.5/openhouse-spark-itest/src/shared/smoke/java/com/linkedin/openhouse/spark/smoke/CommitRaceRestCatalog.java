package com.linkedin.openhouse.spark.smoke;

import java.io.UnsupportedEncodingException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.catalog.SessionCatalog;
import org.apache.iceberg.rest.HTTPClient;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.rest.RESTClient;
import org.apache.iceberg.rest.requests.UpdateTableRequest;

/**
 * Iceberg's own {@link RESTCatalog} over its own HTTP client, wrapped so that a table commit (a
 * {@code POST .../namespaces/{ns}/tables/{t}} carrying an {@link UpdateTableRequest}) first calls
 * {@link CommitRace#beforeCommit}, and records how it ended: test only, the {@code catalog-impl} of
 * the suite's second catalog on the Iceberg REST paths (Spark 3.5 rest-http and Spark 4.2). The
 * request was built from the commit's base before it is sent, so what the armed step commits lands
 * between that base and the commit, by construction. Everything else reaches the client unchanged.
 *
 * <p>The client is wrapped with a dynamic proxy rather than a class implementing {@link
 * RESTClient}, because the interface differs between the Iceberg lines this source compiles on
 * (1.5.2 and 1.12). A client the wrapped one derives (Iceberg 1.12's {@code withAuthSession}) is
 * wrapped too, so commits sent through it are seen.
 */
public class CommitRaceRestCatalog extends RESTCatalog {

  public CommitRaceRestCatalog() {
    super(SessionCatalog.SessionContext.createEmpty(), CommitRaceRestCatalog::client);
  }

  private static RESTClient client(Map<String, String> config) {
    Map<String, String> headers = new HashMap<>();
    config.forEach(
        (key, value) -> {
          if (key.startsWith("header.")) {
            headers.put(key.substring("header.".length()), value);
          }
        });
    return wrap(
        HTTPClient.builder(config)
            .uri(config.get(CatalogProperties.URI))
            .withHeaders(headers)
            .build());
  }

  private static RESTClient wrap(RESTClient delegate) {
    return (RESTClient)
        Proxy.newProxyInstance(
            CommitRaceRestCatalog.class.getClassLoader(),
            new Class<?>[] {RESTClient.class},
            new Racing(delegate));
  }

  private static final class Racing implements InvocationHandler {
    private final RESTClient delegate;

    Racing(RESTClient delegate) {
      this.delegate = delegate;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      String table = "post".equals(method.getName()) ? tableOf(args) : null;
      if (table != null) {
        CommitRace.beforeCommit(table);
      }
      Throwable failure = null;
      try {
        Object result = method.invoke(delegate, args);
        if (result == delegate) {
          return proxy;
        }
        return result instanceof RESTClient ? wrap((RESTClient) result) : result;
      } catch (InvocationTargetException e) {
        failure = e.getCause();
        throw failure;
      } finally {
        if (table != null) {
          CommitRace.afterCommit(table, failure);
        }
      }
    }

    /** {@code ns.t} for a table commit's arguments, or {@code null}. */
    private static String tableOf(Object[] args) {
      if (args == null
          || args.length < 2
          || !(args[0] instanceof String)
          || !(args[1] instanceof UpdateTableRequest)) {
        return null;
      }
      String[] parts = ((String) args[0]).split("/");
      for (int i = parts.length - 2; i >= 1; i--) {
        if ("tables".equals(parts[i])) {
          return decode(parts[i - 1]) + "." + decode(parts[i + 1]);
        }
      }
      return null;
    }

    private static String decode(String part) {
      try {
        return URLDecoder.decode(part, "UTF-8");
      } catch (UnsupportedEncodingException e) {
        throw new IllegalStateException(e);
      }
    }
  }
}
