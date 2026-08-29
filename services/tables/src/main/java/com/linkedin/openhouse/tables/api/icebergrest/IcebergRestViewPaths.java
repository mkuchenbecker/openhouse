package com.linkedin.openhouse.tables.api.icebergrest;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.springframework.http.HttpMethod;
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
  private static final String VIEWS_COLLECTION_PATTERN = antPattern(VIEWS_COLLECTION_TEMPLATE);

  /** Ant pattern equivalent of {@link #VIEW_ITEM_TEMPLATE}, for URI matching. */
  private static final String VIEW_ITEM_PATTERN = antPattern(VIEW_ITEM_TEMPLATE);

  private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

  /**
   * The seven implemented endpoints, in the spec's capability-advertisement format, derived from
   * the path templates this class owns.
   *
   * <p>This list and IcebergRestViewsController's mapping annotations independently encode which
   * method serves which template; a route added to one must be added to the other.
   */
  public static final List<String> IMPLEMENTED_ENDPOINTS =
      Collections.unmodifiableList(
          Arrays.asList(
              endpoint(HttpMethod.GET, CONFIG_TEMPLATE),
              endpoint(HttpMethod.GET, VIEWS_COLLECTION_TEMPLATE),
              endpoint(HttpMethod.POST, VIEWS_COLLECTION_TEMPLATE),
              endpoint(HttpMethod.GET, VIEW_ITEM_TEMPLATE),
              endpoint(HttpMethod.POST, VIEW_ITEM_TEMPLATE),
              endpoint(HttpMethod.DELETE, VIEW_ITEM_TEMPLATE),
              endpoint(HttpMethod.HEAD, VIEW_ITEM_TEMPLATE)));

  private IcebergRestViewPaths() {}

  /**
   * Whether {@code uri} addresses either view route shape — the collection route or the item route
   * — regardless of the request method. Create and replace are the subset of those routes whose
   * bodies can carry a view definition, and so the subset that matters to the audit redactor.
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
  private static String endpoint(HttpMethod httpMethod, String template) {
    if (CONFIG_TEMPLATE.equals(template)) {
      return httpMethod.name() + " " + CONFIG_TEMPLATE;
    }
    return httpMethod.name() + " /v1/{prefix}" + template.substring("/v1".length());
  }

  private static String antPattern(String template) {
    return template.replaceAll("\\{[^/}]+\\}", "*");
  }
}
