package com.linkedin.openhouse.capabilities.rest;

import java.io.Closeable;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.rest.HTTPClient;
import org.apache.iceberg.rest.RESTClient;
import org.apache.iceberg.rest.RESTUtil;
import org.apache.iceberg.rest.auth.AuthManager;
import org.apache.iceberg.rest.auth.AuthManagers;
import org.apache.iceberg.rest.auth.AuthSession;

/**
 * The capability client's HTTP client and authentication on the Iceberg 1.12 line (compiled by the
 * default build; {@code src/iceberg-1.5} holds the 1.5 one). It authenticates as {@code
 * RESTSessionCatalog} 1.12 does: Iceberg's {@link HTTPClient} on the catalog {@code uri} with the
 * {@code header.*} values, and the catalog session of the {@link AuthManager} the properties select
 * ({@code rest.auth.type}; by default OAuth2 with the {@code token} or {@code credential}).
 */
final class RestTransport implements Closeable {
  private final HTTPClient http;
  private final AuthManager authManager;
  private final AuthSession session;
  private final RESTClient client;

  private RestTransport(
      HTTPClient http, AuthManager authManager, AuthSession session, RESTClient client) {
    this.http = http;
    this.authManager = authManager;
    this.session = session;
    this.client = client;
  }

  /**
   * @param catalogName the catalog's name, for the authentication manager
   * @param properties the catalog's properties
   */
  static RestTransport open(String catalogName, Map<String, String> properties) {
    HTTPClient http =
        HTTPClient.builder(properties)
            .uri(properties.get(CatalogProperties.URI))
            .withHeaders(RESTUtil.configHeaders(properties))
            .build();
    AuthManager authManager = null;
    try {
      authManager = AuthManagers.loadAuthManager(catalogName, properties);
      AuthSession session = authManager.catalogSession(http, properties);
      return new RestTransport(http, authManager, session, http.withAuthSession(session));
    } catch (RuntimeException e) {
      closeQuietly(authManager, e);
      closeQuietly(http, e);
      throw e;
    }
  }

  private static void closeQuietly(AutoCloseable closeable, RuntimeException failure) {
    if (closeable != null) {
      try {
        closeable.close();
      } catch (Exception suppressed) {
        failure.addSuppressed(suppressed);
      }
    }
  }

  RESTClient client() {
    return client;
  }

  /** The per-request headers: none, the auth session authenticates each request. */
  Map<String, String> headers() {
    return Collections.emptyMap();
  }

  @Override
  public void close() throws IOException {
    try {
      session.close();
      authManager.close();
    } finally {
      http.close();
    }
  }
}
