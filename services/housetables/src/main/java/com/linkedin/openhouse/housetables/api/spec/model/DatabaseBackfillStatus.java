package com.linkedin.openhouse.housetables.api.spec.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;
import lombok.Value;

/**
 * The state of the database backfill, plus what the call that returned it did.
 *
 * <p>The counters describe one call and are zero on a plain status read; the marker fields describe
 * the durable state and outlive every call.
 */
@Builder(toBuilder = true)
@Value
public class DatabaseBackfillStatus {

  @Schema(description = "Distinct databases read from the table store by this call.")
  @JsonProperty(value = "databasesScanned")
  private long databasesScanned;

  @Schema(description = "Databases this call registered because they had no row.")
  @JsonProperty(value = "databasesRegistered")
  private long databasesRegistered;

  @Schema(
      description =
          "Databases this call found already registered, including one another writer registered"
              + " while this call was registering it.")
  @JsonProperty(value = "databasesAlreadyRegistered")
  private long databasesAlreadyRegistered;

  @Schema(
      description =
          "The watermark this call resumed after, when it resumed an interrupted run. Databases at"
              + " or before it are not read at all, which is why there is no count of them.",
      example = "my_database")
  @JsonProperty(value = "resumedFrom")
  private String resumedFrom;

  @Schema(
      description =
          "The greatest databaseId a scan has finished registering. Absent when no scan is in"
              + " flight; a scan that reached the end of the stream clears it.",
      example = "my_database")
  @JsonProperty(value = "watermark")
  private String watermark;

  @Schema(
      description =
          "When a scan last ran to the end of the distinct-database stream. This is 'ran', not"
              + " 'complete': it does not assert that every database has a row now.")
  @JsonProperty(value = "scanCompleteTimeMs")
  private Long scanCompleteTimeMs;

  @Schema(
      description =
          "When a verification pass last read the store back and found every database in the table"
              + " store registered. This is the only field that asserts completeness, and a"
              + " verification that finds a gap clears it.")
  @JsonProperty(value = "verifiedCompleteTimeMs")
  private Long verifiedCompleteTimeMs;

  @Schema(description = "When a verification pass last ran, whatever it found.")
  @JsonProperty(value = "lastVerifyTimeMs")
  private Long lastVerifyTimeMs;

  @Schema(
      description =
          "Databases the last verification found in the table store with no row. Absent when no"
              + " verification has run.")
  @JsonProperty(value = "missingCount")
  private Long missingCount;

  @Schema(
      description =
          "A bounded sample of the databases the last verification found missing, for diagnosis."
              + " Never the whole set.")
  @JsonProperty(value = "missingSample")
  private List<String> missingSample;

  public String toJson() {
    return new Gson().toJson(this);
  }
}
