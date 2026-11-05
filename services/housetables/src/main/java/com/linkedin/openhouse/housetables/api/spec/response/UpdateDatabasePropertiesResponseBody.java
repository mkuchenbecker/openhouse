package com.linkedin.openhouse.housetables.api.spec.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

/**
 * Outcome of a sparse property update, following the Iceberg REST catalog {@code
 * UpdateNamespacePropertiesResponse} shape.
 */
@Builder
@Getter
public class UpdateDatabasePropertiesResponseBody {
  @Schema(description = "Property keys that were set.", required = true)
  @JsonProperty(value = "updated", access = JsonProperty.Access.READ_ONLY)
  List<String> updated;

  @Schema(description = "Property keys that were removed.", required = true)
  @JsonProperty(value = "removed", access = JsonProperty.Access.READ_ONLY)
  List<String> removed;

  @Schema(
      description = "Property keys requested for removal that were not present on the database.")
  @JsonProperty(value = "missing", access = JsonProperty.Access.READ_ONLY)
  List<String> missing;

  public String toJson() {
    return new Gson().toJson(this);
  }
}
