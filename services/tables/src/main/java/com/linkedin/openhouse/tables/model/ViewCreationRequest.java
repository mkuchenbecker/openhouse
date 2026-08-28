package com.linkedin.openhouse.tables.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.Builder;
import lombok.Value;
import org.apache.iceberg.Schema;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.view.ViewVersion;

/**
 * Everything the service needs to create one view, already unwrapped from the wire envelope.
 *
 * <p>Collecting these five values removes the only nullable parameter the create path had — the
 * caller-requested {@code location} — and stops the service signature from growing another
 * positional argument every time the spec's {@code CreateViewRequest} gains a field.
 *
 * <p>The four required fields are checked at construction. They arrive from {@code
 * CreateViewRequestParser}, which has already rejected a document missing any of them, so a null
 * here means a defect in the handler rather than bad input from a client; failing at the boundary
 * keeps that distinction legible instead of surfacing it as a {@code NullPointerException} deep in
 * a commit.
 */
@Value
public class ViewCreationRequest {

  /** Where the view will live: single-level namespace plus view name. */
  TableIdentifier identifier;

  /** The view's schema as supplied by the client. */
  Schema schema;

  /**
   * The version the client asked for. The service — not the client — owns version-id, schema-id and
   * timestamp assignment, so this carries the client's intent rather than the stored result.
   */
  ViewVersion requestedVersion;

  /** Caller-requested storage location; empty means the server assigns one. */
  Optional<String> location;

  /** User view properties, already screened for reserved keys by validation. Unmodifiable. */
  Map<String, String> properties;

  @Builder
  private ViewCreationRequest(
      TableIdentifier identifier,
      Schema schema,
      ViewVersion requestedVersion,
      String location,
      Map<String, String> properties) {
    this.identifier = Objects.requireNonNull(identifier, "identifier is required");
    this.schema = Objects.requireNonNull(schema, "schema is required");
    this.requestedVersion =
        Objects.requireNonNull(requestedVersion, "requestedVersion is required");
    this.location = Optional.ofNullable(location);
    this.properties =
        properties == null
            ? Collections.emptyMap()
            : Collections.unmodifiableMap(new LinkedHashMap<>(properties));
  }

  /**
   * Renders without the schema, the properties or the location.
   *
   * <p>This object is named in failure paths that reach logs and audit events, and the fields
   * omitted here can carry user data — column names in the schema, and a location that encodes a
   * storage layout. The identifier is the part that identifies the request.
   */
  @Override
  public String toString() {
    return "ViewCreationRequest{identifier=" + identifier + "}";
  }
}
