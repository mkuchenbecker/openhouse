package com.linkedin.openhouse.tables.api.spec.v0.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import lombok.Builder;
import lombok.Value;

@Builder(toBuilder = true)
@Value
public class GetDatabaseResponseBody {
  @Schema(description = "Unique Resource identifier for the Database", example = "my_database")
  @JsonProperty(access = JsonProperty.Access.READ_ONLY)
  private String databaseId;

  @Schema(
      description = "Unique Resource identifier for the Cluster containing the Database",
      example = "my_cluster")
  @JsonProperty(access = JsonProperty.Access.READ_ONLY)
  private String clusterId;

  @Schema(
      description =
          "Server-generated UUID that is the canonical identity of the database entity. Absent for"
              + " databases listed from table metadata that have no database entity.",
      example = "3f1c1a4e-8e3f-4d0a-9c6c-0c3b2a5e7d11",
      accessMode = Schema.AccessMode.READ_ONLY)
  @JsonProperty(access = JsonProperty.Access.READ_ONLY)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  private String databaseUUID;

  @Schema(
      description =
          "Key-value properties of the database entity. Absent for databases listed from table"
              + " metadata that have no database entity.",
      example = "{\"retention.days\": \"30\"}",
      accessMode = Schema.AccessMode.READ_ONLY)
  @JsonProperty(access = JsonProperty.Access.READ_ONLY)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  private Map<String, String> properties;

  public String toJson() {
    return new Gson().toJson(this);
  }
}
