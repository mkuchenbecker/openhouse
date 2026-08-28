package com.linkedin.openhouse.tables.api.icebergrest;

import org.springframework.util.AntPathMatcher;

/**
 * Single owner of the Iceberg REST view path shapes. The controller mappings, the audit redactor's
 * route scoping, the exception handler's per-route 404 decision and the {@code /v1/config}
 * endpoints list all consume these constants, so the route shape cannot drift apart across those
 * four security-relevant consumers.
 */
public final class IcebergRestViewPaths {

  /** The client-bootstrap route. */
  public static final String CONFIG_TEMPLATE = "/v1/config";

  /** The view collection route: list (GET) and create (POST). */
  public static final String VIEWS_COLLECTION_TEMPLATE = "/v1/namespaces/{namespace}/views";

  /** The per-view route: load (GET), replace (POST), drop (DELETE) and exists (HEAD). */
  public static final String VIEW_ITEM_TEMPLATE = VIEWS_COLLECTION_TEMPLATE + "/{view}";

  /** Ant pattern equivalent of {@link #VIEWS_COLLECTION_TEMPLATE}, for URI matching. */
  public static final String VIEWS_COLLECTION_PATTERN = antPattern(VIEWS_COLLECTION_TEMPLATE);

  /** Ant pattern equivalent of {@link #VIEW_ITEM_TEMPLATE}, for URI matching. */
  public static final String VIEW_ITEM_PATTERN = antPattern(VIEW_ITEM_TEMPLATE);

  private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

  private IcebergRestViewPaths() {}

  /**
   * Whether {@code uri} addresses one of the two view write routes (create or replace), which are
   * the routes whose bodies can carry a view definition.
   */
  public static boolean isViewRoute(String uri) {
    return uri != null
        && (PATH_MATCHER.match(VIEWS_COLLECTION_PATTERN, uri)
            || PATH_MATCHER.match(VIEW_ITEM_PATTERN, uri));
  }

  /**
   * Whether {@code uri} addresses the per-view route (load, replace, drop, exists). Tolerant of a
   * trailing slash so the per-route 404 vocabulary cannot be flipped by URL normalization.
   */
  public static boolean isViewItemUri(String uri) {
    if (uri == null) {
      return false;
    }
    String normalized =
        uri.length() > 1 && uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri;
    return PATH_MATCHER.match(VIEW_ITEM_PATTERN, normalized);
  }

  /**
   * The spec's capability-advertisement form of a served route: the resource path from the OpenAPI
   * document, which carries the optional {@code {prefix}} segment this server does not use.
   */
  public static String endpoint(String httpMethod, String template) {
    if (CONFIG_TEMPLATE.equals(template)) {
      return httpMethod + " " + CONFIG_TEMPLATE;
    }
    return httpMethod + " /v1/{prefix}" + template.substring("/v1".length());
  }

  private static String antPattern(String template) {
    return template.replaceAll("\\{[^/}]+\\}", "*");
  }
}
