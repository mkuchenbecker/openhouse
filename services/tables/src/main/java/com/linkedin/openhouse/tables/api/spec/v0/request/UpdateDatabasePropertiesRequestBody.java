package com.linkedin.openhouse.tables.api.spec.v0.request;

import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Sparse update of database properties following the Iceberg REST catalog {@code updateProperties}
 * contract: keys in {@code updates} are set, keys in {@code removals} are removed, and properties
 * not named in the request are not modified. A key present in both sets is rejected with 422.
 */
@Builder
@EqualsAndHashCode
@Getter
@AllArgsConstructor(access = AccessLevel.PROTECTED)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UpdateDatabasePropertiesRequestBody {
  @Schema(
      description = "Properties to set on the database. Existing keys are overwritten.",
      example = "{\"retention.days\": \"30\"}")
  @Nullable
  Map<String, String> updates;

  @Schema(description = "Property keys to remove from the database.", example = "[\"owner\"]")
  @Nullable
  List<String> removals;

  public String toJson() {
    return new Gson().toJson(this);
  }
}
