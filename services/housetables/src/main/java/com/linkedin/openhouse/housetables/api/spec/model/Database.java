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
 * The row type for the House table storing database entities. {@link JsonProperty} annotation is
 * needed when a field is declared as private to instruct jackson library initialize and deserialize
 * the model object without no-arg constructor provided.
 *
 * <p>{@code databaseUuid} and {@code creationTime} are server-stamped and read-only: a value sent
 * by a client is ignored on deserialization and never stored.
 */
@Builder(toBuilder = true)
@Value
public class Database {
  @Schema(
      description =
          "Server-generated UUID that is the canonical identity of the database. Read-only.",
      example = "3f1c1a4e-8e3f-4d0a-9c6c-0c3b2a5e7d11",
      accessMode = Schema.AccessMode.READ_ONLY)
  @JsonProperty(value = "databaseUuid", access = JsonProperty.Access.READ_ONLY)
  private String databaseUuid;

  @Schema(
      description = "Logical name of the database. Unique ignoring case.",
      example = "my_database")
  @JsonProperty(value = "databaseId")
  @NotEmpty(message = "databaseId cannot be empty")
  @Pattern(regexp = ALPHA_NUM_UNDERSCORE_REGEX, message = ALPHA_NUM_UNDERSCORE_ERROR_MSG)
  private String databaseId;

  @Schema(
      description = "Creation time of the database in epoch milliseconds. Read-only.",
      example = "1651002318265",
      accessMode = Schema.AccessMode.READ_ONLY)
  @JsonProperty(value = "creationTime", access = JsonProperty.Access.READ_ONLY)
  private Long creationTime;

  @Schema(
      description = "Key-value properties of the database.",
      example = "{\"retention.days\": \"30\"}")
  @JsonProperty(value = "properties")
  private Map<String, String> properties;

  public String toJson() {
    return new Gson().toJson(this);
  }
}
