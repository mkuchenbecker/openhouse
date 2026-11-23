package com.linkedin.openhouse.capabilities.rest;

import java.io.Closeable;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.rest.HTTPClient;
import org.apache.iceberg.rest.RESTClient;
import org.apache.iceberg.rest.RESTUtil;
import org.apache.iceberg.rest.ResourcePaths;
import org.apache.iceberg.rest.auth.OAuth2Properties;
import org.apache.iceberg.rest.auth.OAuth2Util;
import org.apache.iceberg.rest.responses.OAuthTokenResponse;

/**
 * The capability client's HTTP client and authentication on the Iceberg 1.5 line (compiled when the
 * build uses Iceberg 1.5.2; {@code src/iceberg-1.12} holds the 1.12 one). It authenticates as
 * {@code RESTSessionCatalog} 1.5.2 does at initialization: Iceberg's {@link HTTPClient} on the
 * catalog {@code uri} with the {@code header.*} values; an OAuth {@code credential}, when set, is
 * exchanged for a token at the catalog's token endpoint; otherwise the {@code token}, when set, is
 * the bearer token.
 */
final class RestTransport implements Closeable {
  private static final String HEADER_PREFIX = "header.";

  private final HTTPClient client;
  private final Map<String, String> headers;

  private RestTransport(HTTPClient client, Map<String, String> headers) {
    this.client = client;
    this.headers = headers;
  }

  /**
   * @param catalogName unused on this line (1.12's authentication managers take it)
   * @param properties the catalog's properties
   */
  static RestTransport open(String catalogName, Map<String, String> properties) {
    Map<String, String> configHeaders = RESTUtil.extractPrefixMap(properties, HEADER_PREFIX);
    HTTPClient client =
        HTTPClient.builder(properties)
            .uri(properties.get(CatalogProperties.URI))
            .withHeaders(configHeaders)
            .build();
    try {
      String token = properties.get(OAuth2Properties.TOKEN);
      String credential = properties.get(OAuth2Properties.CREDENTIAL);
      Map<String, String> auth;
      if (credential != null && !credential.isEmpty()) {
        OAuthTokenResponse response =
            OAuth2Util.fetchToken(
                client,
                OAuth2Util.authHeaders(token),
                credential,
                properties.getOrDefault(OAuth2Properties.SCOPE, OAuth2Properties.CATALOG_SCOPE),
                properties.getOrDefault(
                    OAuth2Properties.OAUTH2_SERVER_URI, ResourcePaths.tokens()));
        auth = OAuth2Util.authHeaders(response.token());
      } else {
        auth = OAuth2Util.authHeaders(token);
      }
      return new RestTransport(client, Collections.unmodifiableMap(auth));
    } catch (RuntimeException e) {
      try {
        client.close();
      } catch (IOException suppressed) {
        e.addSuppressed(suppressed);
      }
      throw e;
    }
  }

  RESTClient client() {
    return client;
  }

  /** The per-request headers: the authorization header, if any. */
  Map<String, String> headers() {
    return headers;
  }

  @Override
  public void close() throws IOException {
    client.close();
  }
}
