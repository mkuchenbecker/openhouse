package com.linkedin.openhouse.housetables.api.spec.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Sparse update of database properties following the Iceberg REST catalog {@code updateProperties}
 * contract: keys in {@code updates} are set, keys in {@code removals} are removed, and every other
 * property is left untouched. A key present in both sets is rejected.
 */
@Builder
@Getter
@EqualsAndHashCode
@AllArgsConstructor(access = AccessLevel.PROTECTED)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UpdateDatabasePropertiesRequestBody {
  @Schema(
      description = "Properties to set on the database. Existing keys are overwritten.",
      example = "{\"retention.days\": \"30\"}")
  @JsonProperty(value = "updates")
  Map<String, String> updates;

  @Schema(description = "Property keys to remove from the database.", example = "[\"owner\"]")
  @JsonProperty(value = "removals")
  List<String> removals;

  public String toJson() {
    return new Gson().toJson(this);
  }
}
