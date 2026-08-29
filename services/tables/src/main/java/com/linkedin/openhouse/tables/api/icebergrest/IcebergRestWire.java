package com.linkedin.openhouse.tables.api.icebergrest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linkedin.openhouse.tables.exception.ViewApiException;
import com.linkedin.openhouse.tables.exception.ViewErrorCode;
import java.io.UncheckedIOException;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.RESTSerializers;
import org.apache.iceberg.rest.requests.CreateViewRequest;
import org.apache.iceberg.rest.requests.CreateViewRequestParser;
import org.apache.iceberg.rest.requests.UpdateTableRequest;
import org.apache.iceberg.rest.requests.UpdateTableRequestParser;
import org.apache.iceberg.rest.responses.ErrorResponse;
import org.apache.iceberg.rest.responses.ErrorResponseParser;
import org.apache.iceberg.rest.responses.ImmutableLoadViewResponse;
import org.apache.iceberg.rest.responses.LoadViewResponseParser;
import org.apache.iceberg.view.ViewMetadata;
import org.springframework.http.HttpStatus;

/**
 * Wire serialization for the Iceberg REST views surface, built on Iceberg's own parsers.
 *
 * <p>The controllers consume and produce {@code String} bodies ({@code RESTCatalogAdapter} style):
 * every request is parsed and every response serialized here with the reference implementation's
 * parsers, so kebab-case naming, required-field enforcement and update/requirement polymorphism
 * come from the dependency rather than from hand-rolled models. No custom Jackson converters are
 * registered, and the global Spring {@code ObjectMapper} plays no part in these routes.
 *
 * <p><b>Redaction invariant:</b> a parse failure message from Iceberg can echo fragments of the
 * submitted document (SQL text, schema snippets), and {@link ViewApiException} messages are copied
 * into the error body and into service audit events. Parse failures are therefore reported with
 * fixed messages; only the exception class is logged.
 */
@Slf4j
public final class IcebergRestWire {

  /**
   * A dedicated mapper with {@link RESTSerializers} registered, scoped to these routes. Used for
   * the response documents that have no dedicated Iceberg parser entry point in 1.5.2.17 (the
   * list-views and config bodies); everything else goes through the static parsers directly.
   */
  private static final ObjectMapper MAPPER = new ObjectMapper();

  static {
    RESTSerializers.registerAll(MAPPER);
  }

  private static final String MALFORMED_CREATE_VIEW_REQUEST =
      "Malformed CreateViewRequest: the request body must be a JSON document with the required "
          + "fields name, schema, view-version and properties, per the Iceberg REST catalog spec";

  private static final String MALFORMED_COMMIT_VIEW_REQUEST =
      "Malformed CommitViewRequest: the request body must be a JSON document carrying "
          + "requirements and updates, per the Iceberg REST catalog spec";

  private IcebergRestWire() {}

  /**
   * Parse a create-view request body with Iceberg's {@link CreateViewRequestParser}.
   *
   * <p>Only the two failure modes Iceberg raises for caller-supplied text are caught: a document
   * that parses as JSON but is not a valid request — wrong shapes, missing required fields, a Spark
   * {@code StructType} document where an Iceberg schema belongs — surfaces as {@link
   * IllegalArgumentException}, and text that is not JSON at all as an {@link UncheckedIOException}
   * wrapping Jackson's parse failure. Anything else is a server fault and must propagate to the 500
   * path rather than be reported to the caller as a bad request.
   *
   * @param json the raw request body, possibly {@code null} when the caller sent none
   * @return the parsed request
   * @throws ViewApiException 400 {@code BadRequestException}-typed on a missing or unparseable
   *     body. The message is fixed: parser messages may echo the submitted document.
   */
  public static CreateViewRequest parseCreateViewRequest(String json) {
    if (json == null || json.trim().isEmpty()) {
      throw badRequest(MALFORMED_CREATE_VIEW_REQUEST);
    }
    try {
      return CreateViewRequestParser.fromJson(json);
    } catch (IllegalArgumentException | UncheckedIOException e) {
      throw badRequest(MALFORMED_CREATE_VIEW_REQUEST, e);
    }
  }

  /**
   * Parse a commit-view (replace) request body with Iceberg's {@link UpdateTableRequestParser}: the
   * commit envelope is shared between tables and views in the Iceberg REST protocol, and 1.5.2.17
   * has no separate {@code CommitViewRequest} class. The caught failure modes mirror {@link
   * #parseCreateViewRequest(String)}.
   *
   * @param json the raw request body, possibly {@code null} when the caller sent none
   * @return the parsed request
   * @throws ViewApiException 400 {@code BadRequestException}-typed on a missing or unparseable
   *     body. The message is fixed: parser messages may echo the submitted document.
   */
  public static UpdateTableRequest parseCommitViewRequest(String json) {
    if (json == null || json.trim().isEmpty()) {
      throw badRequest(MALFORMED_COMMIT_VIEW_REQUEST);
    }
    try {
      return UpdateTableRequestParser.fromJson(json);
    } catch (IllegalArgumentException | UncheckedIOException e) {
      throw badRequest(MALFORMED_COMMIT_VIEW_REQUEST, e);
    }
  }

  /**
   * Serialize a {@code LoadViewResult}: the response of load, create and replace. The {@code
   * metadata-location} field is the metadata's own file location, and the complete view metadata
   * document is inlined.
   */
  public static String toLoadViewResultJson(ViewMetadata metadata) {
    return LoadViewResponseParser.toJson(
        ImmutableLoadViewResponse.builder()
            .metadataLocation(metadata.metadataFileLocation())
            .metadata(metadata)
            .build());
  }

  /**
   * Serialize a {@code ListTablesResponse} document: {@code identifiers} plus an optional {@code
   * next-page-token}. 1.5.2.17's {@code ListTablesResponse} model predates the spec's pagination
   * fields, so the document is assembled here field by field; the identifier elements themselves
   * are rendered by Iceberg's own {@code TableIdentifier} serializer. A {@code null} token means
   * the listing is complete and the field is omitted, which is the spec's termination signal.
   */
  public static String toListViewsJson(List<TableIdentifier> identifiers, String nextPageToken) {
    ObjectNode root = MAPPER.createObjectNode();
    ArrayNode identifiersNode = root.putArray("identifiers");
    for (TableIdentifier identifier : identifiers) {
      identifiersNode.add(MAPPER.valueToTree(identifier));
    }
    if (nextPageToken != null) {
      root.put("next-page-token", nextPageToken);
    }
    return root.toString();
  }

  /**
   * Serialize the {@code GET /v1/config} body: empty {@code defaults} and {@code overrides} plus
   * the explicit {@code endpoints} capability list. The endpoints field is spec-sanctioned
   * capability advertisement; without it a 1.6+ client would assume the default endpoint set, which
   * is wrong in both directions for this server.
   */
  public static String toCatalogConfigJson() {
    ObjectNode root = MAPPER.createObjectNode();
    root.putObject("defaults");
    root.putObject("overrides");
    ArrayNode endpointsNode = root.putArray("endpoints");
    IcebergRestViewPaths.IMPLEMENTED_ENDPOINTS.forEach(endpointsNode::add);
    return root.toString();
  }

  /**
   * Serialize an {@code IcebergErrorResponse} envelope: {@code {"error": {"message", "type",
   * "code"}}}. No stack is ever included: stack traces are server internals and leak nothing useful
   * to a REST catalog client.
   */
  public static String toErrorJson(HttpStatus status, String type, String message) {
    ErrorResponse errorResponse =
        ErrorResponse.builder()
            .responseCode(status.value())
            .withType(type)
            .withMessage(message)
            .build();
    try {
      return ErrorResponseParser.toJson(errorResponse);
    } catch (UncheckedIOException e) {
      // Not reachable with the inputs above; a fail-safe literal keeps the error path total.
      log.error("Failed to serialize an Iceberg error envelope", e);
      return "{\"error\":{\"message\":\"Internal Server Error\","
          + "\"type\":\"InternalServerError\",\"code\":500}}";
    }
  }

  private static ViewApiException badRequest(String message) {
    return new ViewApiException(ViewErrorCode.INVALID_VIEW_DEFINITION, message);
  }

  private static ViewApiException badRequest(String message, RuntimeException cause) {
    // Only the exception class is logged; the cause is chained for server-side diagnostics. The
    // advice never serializes stacks, and the redaction invariant governs messages only.
    log.warn("Rejected an unparseable views request: {}", cause.getClass().getName());
    return new ViewApiException(ViewErrorCode.INVALID_VIEW_DEFINITION, message, cause);
  }
}
