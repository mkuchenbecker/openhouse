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
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.iceberg.SnapshotRef;
import org.apache.iceberg.rest.RESTResponse;
import org.apache.iceberg.rest.RESTSerializers;
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
