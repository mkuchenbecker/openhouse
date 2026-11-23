package org.apache.iceberg.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.iceberg.rest.responses.ErrorResponse;
import org.apache.iceberg.rest.responses.ErrorResponseParser;

/**
 * Parses IRC response bodies exactly as Iceberg's {@code RESTCatalog} does: with the object mapper
 * its {@code HTTPClient} uses ({@link RESTObjectMapper}, package-private, hence this class's
 * package) and with {@link ErrorResponseParser} for error bodies. Test-only, so that wire-shape
 * tests do not depend on the server's own mapper.
 */
public final class IrcClientParsers {

  private IrcClientParsers() {}

  /** Parses {@code json} as a {@code type} and runs its {@code validate()}, as the client does. */
  public static <T extends RESTResponse> T parse(String json, Class<T> type) throws Exception {
    ObjectMapper mapper = RESTObjectMapper.mapper();
    T response = mapper.readValue(json, type);
    response.validate();
    return response;
  }

  /** Parses {@code json} as a request of {@code type}, with the client's own mapper. */
  public static <T extends RESTRequest> T parseRequest(String json, Class<T> type)
      throws Exception {
    T request = RESTObjectMapper.mapper().readValue(json, type);
    request.validate();
    return request;
  }

  /** Writes a request body exactly as the client's HTTP client does. */
  public static String toJson(Object request) throws Exception {
    return RESTObjectMapper.mapper().writeValueAsString(request);
  }

  /** Parses an error body as the client's error handlers do. */
  public static ErrorResponse parseError(String json) {
    return ErrorResponseParser.fromJson(json);
  }
}
