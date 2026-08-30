package com.linkedin.openhouse.housetables.api.spec.model;

import static com.linkedin.openhouse.common.api.validator.ValidatorConstants.ALPHA_NUM_UNDERSCORE_ERROR_MSG;
import static com.linkedin.openhouse.common.api.validator.ValidatorConstants.ALPHA_NUM_UNDERSCORE_REGEX;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Pattern;
import lombok.Builder;
import lombok.Value;

/**
 * The row type for the House table storing databases, that is, the stored form of a namespace.
 *
 * <p>{@code databaseId} is the encoded namespace. At the shipped namespace depth of 1 that is a
 * plain database name and this charset is the one every existing database already satisfies.
 */
@Builder(toBuilder = true)
@Value
public class Database {
  @Schema(
      description = "Unique Resource identifier for a Database, that is, the encoded namespace.",
      example = "my_database")
  @JsonProperty(value = "databaseId")
  @NotEmpty(message = "databaseId cannot be empty")
  @Pattern(regexp = ALPHA_NUM_UNDERSCORE_REGEX, message = ALPHA_NUM_UNDERSCORE_ERROR_MSG)
  private String databaseId;

  @Schema(
      description =
          "Properties of the database. Keys prefixed with 'openhouse.' are server-owned and are not"
              + " writable by clients.",
      example = "{\"owner\": \"user\"}")
  @JsonProperty(value = "properties")
  private Map<String, String> properties;

  @Schema(description = "Creation time of the database in milliseconds.", example = "1651002318265")
  @JsonProperty(value = "creationTime")
  private Long creationTime;

  @Schema(
      description = "Last modification time of the database in milliseconds.",
      example = "1651002318265")
  @JsonProperty(value = "lastModifiedTime")
  private Long lastModifiedTime;

  public String toJson() {
    return new Gson().toJson(this);
  }
}
