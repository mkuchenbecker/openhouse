package com.linkedin.openhouse.housetables.api.spec.model;

import static com.linkedin.openhouse.common.api.validator.ValidatorConstants.ALPHA_NUM_UNDERSCORE_ERROR_MSG;
import static com.linkedin.openhouse.common.api.validator.ValidatorConstants.ALPHA_NUM_UNDERSCORE_REGEX;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.media.Schema;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Pattern;
import lombok.Builder;
import lombok.Value;

/** The key type for the House table storing database entities: the logical database name. */
@Builder
@Value
public class DatabaseKey {
  @Schema(description = "Logical name of the database.", example = "my_database")
  @JsonProperty(value = "databaseId")
  @NotEmpty(message = "databaseId cannot be empty")
  @Pattern(regexp = ALPHA_NUM_UNDERSCORE_REGEX, message = ALPHA_NUM_UNDERSCORE_ERROR_MSG)
  private String databaseId;

  public String toJson() {
    return new Gson().toJson(this);
  }
}
