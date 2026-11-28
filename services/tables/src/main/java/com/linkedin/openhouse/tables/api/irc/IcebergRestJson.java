package com.linkedin.openhouse.tables.api.irc;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.iceberg.SnapshotRef;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.exceptions.BadRequestException;
import org.apache.iceberg.rest.RESTRequest;
import org.apache.iceberg.rest.RESTResponse;
import org.apache.iceberg.rest.RESTSerializers;
import org.apache.iceberg.rest.responses.ConfigResponse;
import org.apache.iceberg.rest.responses.ErrorResponse;
import org.apache.iceberg.rest.responses.ErrorResponseParser;
import org.apache.iceberg.rest.responses.LoadTableResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * The JSON wire format of the IRC routes, and nothing else's: Iceberg's REST response classes
 * written with Iceberg's {@link RESTSerializers}, configured the way Iceberg's own
 * (package-private) {@code RESTObjectMapper} is, so the client reads exactly what Iceberg's
 * server-side code would write. The service's Spring-managed JSON configuration for the native
 * routes is not touched: every IRC body is written to bytes here and returned as such.
 */
public final class IcebergRestJson {

  private static final ObjectMapper MAPPER = newMapper();

  private IcebergRestJson() {}

  private static ObjectMapper newMapper() {
    ObjectMapper mapper = new ObjectMapper(new JsonFactory());
    mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    mapper.setPropertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE);
    RESTSerializers.registerAll(mapper);
    return mapper;
  }

  /** A 200 response with {@code body} as JSON. */
  public static ResponseEntity<byte[]> ok(RESTResponse body) {
    return respond(HttpStatus.OK, write(MAPPER.valueToTree(body)));
  }

  /**
   * A 200 config response: {@code config} and, as its {@code endpoints}, the spec routes served, in
   * the spec's format. Written at the JSON level because this server's Iceberg lines differ:
   * LinkedIn's 1.5.2 has no {@code endpoints} in {@code ConfigResponse} (and its clients ignore the
   * field), 1.12 has it with an {@code Endpoint} type 1.5.2 lacks.
   */
  public static ResponseEntity<byte[]> config(ConfigResponse config, List<String> endpoints) {
    ObjectNode node = MAPPER.valueToTree(config);
    ArrayNode list = node.putArray("endpoints");
    endpoints.forEach(list::add);
    return respond(HttpStatus.OK, write(node));
  }

  /** A response with no body. */
  public static ResponseEntity<byte[]> noContent() {
    return ResponseEntity.noContent().build();
  }

  /** An error response: the spec's {@code IcebergErrorResponse} with the error's code as status. */
  public static ResponseEntity<byte[]> error(ErrorResponse error) {
    return respond(
        HttpStatus.valueOf(error.code()),
        ErrorResponseParser.toJson(error).getBytes(StandardCharsets.UTF_8));
  }

  /**
   * A 200 load-table response for {@code table}.
   *
   * <p>{@code metadata-location} is set from {@link IcebergRestLoadedTable#getMetadataLocation()},
   * the location the native API reports for the same table, not from the metadata object, whose
   * file location is the internal catalog's own spelling of it. Setting it on the metadata would
   * mean rebuilding the metadata, which changes it (Iceberg's builder stamps a new last-updated
   * time and adds a metadata-log entry).
   *
   * <p>With {@code refsOnly}, the {@code snapshots} list holds only the snapshots that a branch or
   * tag references; every other field is as stored.
   */
  public static ResponseEntity<byte[]> loadTable(IcebergRestLoadedTable table) {
    LoadTableResponse response =
        LoadTableResponse.builder()
            .withTableMetadata(table.getMetadata())
            .addAllConfig(table.getConfig())
            .build();
    ObjectNode node = MAPPER.valueToTree(response);
    node.put("metadata-location", table.getMetadataLocation());
    if (table.isRefsOnly()) {
      Set<Long> referenced =
          table.getMetadata().refs().values().stream()
              .map(SnapshotRef::snapshotId)
              .collect(Collectors.toSet());
      JsonNode snapshots = node.path("metadata").path("snapshots");
      if (snapshots.isArray()) {
        ArrayNode kept = MAPPER.createArrayNode();
        snapshots.forEach(
            snapshot -> {
              if (referenced.contains(snapshot.path("snapshot-id").asLong())) {
                kept.add(snapshot);
              }
            });
        ((ObjectNode) node.get("metadata")).set("snapshots", kept);
      }
    }
    return respond(HttpStatus.OK, write(node));
  }

  /**
   * A 200 response for a staged create: the metadata the create would store, with no {@code
   * metadata-location}, since nothing is stored yet (the spec allows none for a staged table), and
   * the per-table {@code config}.
   */
  public static ResponseEntity<byte[]> staged(TableMetadata metadata, Map<String, String> config) {
    LoadTableResponse response =
        LoadTableResponse.builder().withTableMetadata(metadata).addAllConfig(config).build();
    return respond(HttpStatus.OK, write(MAPPER.valueToTree(response)));
  }

  /**
   * Reads a request body of {@code type} with Iceberg's serializers, as Iceberg's own server-side
   * code reads it, and runs its {@code validate()}. A body that does not parse, or names an update
   * or requirement this Iceberg line does not know, is a 400 {@code BadRequestException}.
   */
  public static <T extends RESTRequest> T read(byte[] body, Class<T> type) {
    T request = parse(body, type);
    try {
      request.validate();
    } catch (RuntimeException e) {
      throw new BadRequestException("Invalid %s: %s", type.getSimpleName(), e.getMessage());
    }
    return request;
  }

  /**
   * Reads a request body of {@code type} as {@link #read} does, without its {@code validate()}: for
   * a route whose checks run in another order than Iceberg's (the namespace property update, whose
   * key overlap the namespace service refuses after its authorization decision).
   */
  public static <T extends RESTRequest> T parse(byte[] body, Class<T> type) {
    if (body == null || body.length == 0) {
      throw new BadRequestException("Invalid %s: no body", type.getSimpleName());
    }
    T request;
    try {
      request = MAPPER.readValue(body, type);
    } catch (IOException | RuntimeException e) {
      throw new BadRequestException(
          "Invalid %s: %s", type.getSimpleName(), e.getMessage() != null ? e.getMessage() : e);
    }
    if (request == null) {
      throw new BadRequestException("Invalid %s: no body", type.getSimpleName());
    }
    return request;
  }

  private static byte[] write(JsonNode node) {
    try {
      return MAPPER.writeValueAsBytes(node);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Cannot write IRC response", e);
    }
  }

  private static ResponseEntity<byte[]> respond(HttpStatus status, byte[] body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return new ResponseEntity<>(body, headers, status);
  }
}
