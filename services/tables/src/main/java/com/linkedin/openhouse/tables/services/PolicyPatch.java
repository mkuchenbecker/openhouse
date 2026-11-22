package com.linkedin.openhouse.tables.services;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.linkedin.openhouse.common.exception.RequestValidationFailureException;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.History;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.LockState;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.Policies;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.PolicyTag;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.Replication;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.Retention;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The policy patch OpenHouse's policy statements write as the table property {@value #KEY}, applied
 * on the server the way the OpenHouse Java client applies it before it sends a native request
 * ({@code OpenHouseTableOperations.buildUpdatedPolicies}), so that a plain Iceberg client that
 * sends the key raw (the Iceberg REST Catalog routes) gets the same policies the OpenHouse client's
 * request would carry.
 *
 * <ul>
 *   <li><b>Parse:</b> with Gson, as the client parses it, so the same text is accepted: Gson reads
 *       leniently (the column-tag statement writes its tags unquoted, {@code [PII, HC]}; the
 *       sharing statement writes the BOOLEAN token as typed, {@code TRUE}), ignores unknown fields,
 *       and reads an enum constant it does not know (for example {@code pii}) as null. The target
 *       is {@link Patch}, which has the generated client model's field names and boxed types, so an
 *       absent field is null, as in the client's model (the server's {@link Policies} has a
 *       primitive {@code sharingEnabled}, which cannot say "absent"), except where the client's
 *       model has a default: a replication without {@code config} has an empty one.
 *   <li><b>Merge</b> onto the stored policies with the client's five field rules: retention,
 *       sharing, replication and history replace the stored value when the patch has one; column
 *       tags merge per column, the patch's columns first and winning, then the stored columns the
 *       patch does not name. With no stored policies, the patch is the policies (lock state
 *       included, as the client sends it). A stored lock state is kept; the patch's is ignored.
 *   <li><b>As the native request reads it:</b> the client writes the merged policies as JSON and
 *       the native route reads them with Jackson, which, among other things, reads a set of column
 *       tags into a {@code HashSet}. The merged policies take the same Jackson round trip here, so
 *       what is stored next is byte-equal to what the native request stores.
 * </ul>
 *
 * <p>Not a third copy of the merge by choice: the client's merge works on the generated client
 * model and the server has none, so the five rules are restated here on the server model. The
 * native mapping and validation (default retention patterns, replication schedules, the policy
 * validators, feature compatibility) are not repeated: the callers hand the result to the native
 * code that runs them.
 */
public final class PolicyPatch {
  /** The request property that carries a policy patch. Never stored as a table property. */
  public static final String KEY = "updated.openhouse.policy";

  /** As the client parses the patch: a default Gson. */
  private static final Gson GSON = new Gson();

  /** As the native route reads a request body (unknown properties are ignored there too). */
  private static final ObjectMapper JACKSON =
      new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  private PolicyPatch() {}

  /**
   * The generated client model's {@code Policies} fields, as the client's Gson parse fills them:
   * null when absent.
   */
  static final class Patch {
    Retention retention;
    Boolean sharingEnabled;
    Map<String, PolicyTag> columnTags;
    Replication replication;
    History history;
    LockState lockState;
  }

  /**
   * The policies the OpenHouse client's request would carry for a table whose stored policies are
   * {@code stored} and whose properties hold {@code patch} under {@link #KEY}.
   *
   * @param table the table, for the refusal's wording
   * @throws RequestValidationFailureException if the client could not parse {@code patch} either
   */
  public static Policies apply(Policies stored, String patch, String table) {
    Patch parsed = parse(patch, table);
    Policies merged = stored == null ? asPolicies(parsed) : merge(stored, parsed);
    return asTheNativeRequestReadsIt(merged);
  }

  private static Patch parse(String patch, String table) {
    Patch parsed;
    try {
      parsed = GSON.fromJson(patch, Patch.class);
    } catch (JsonParseException e) {
      throw unreadable(table, e.getMessage());
    }
    if (parsed == null) {
      // An empty patch: the client's parse yields no object, and its merge then fails.
      throw unreadable(table, "the patch is empty");
    }
    if (parsed.replication != null && parsed.replication.getConfig() == null) {
      // The generated client model's one default: its Replication starts with an empty config
      // list (the field is required), so the client reads {"replication": {}}, which is how the
      // UNSET POLICY (REPLICATION) statement writes it, as a replication with no destinations.
      parsed.replication = Replication.builder().config(new ArrayList<>()).build();
    }
    return parsed;
  }

  private static RequestValidationFailureException unreadable(String table, String why) {
    return new RequestValidationFailureException(
        String.format(
            "Write to table %s sets the table property %s to a value that is not a policy patch "
                + "(%s): it must be a JSON object of policies, as OpenHouse's policy statements "
                + "write it",
            table, KEY, why));
  }

  /** No stored policies: the patch is the policies. */
  private static Policies asPolicies(Patch patch) {
    return Policies.builder()
        .retention(patch.retention)
        .sharingEnabled(patch.sharingEnabled != null && patch.sharingEnabled)
        .columnTags(patch.columnTags)
        .replication(patch.replication)
        .history(patch.history)
        .lockState(patch.lockState)
        .build();
  }

  private static Policies merge(Policies stored, Patch patch) {
    Policies.PoliciesBuilder merged = stored.toBuilder();
    if (patch.retention != null) {
      merged.retention(patch.retention);
    }
    if (patch.sharingEnabled != null) {
      merged.sharingEnabled(patch.sharingEnabled);
    }
    if (patch.columnTags != null) {
      Map<String, PolicyTag> columnTags = new LinkedHashMap<>(patch.columnTags);
      if (stored.getColumnTags() != null) {
        stored.getColumnTags().forEach(columnTags::putIfAbsent);
      }
      merged.columnTags(columnTags);
    }
    if (patch.replication != null) {
      merged.replication(patch.replication);
    }
    if (patch.history != null) {
      merged.history(patch.history);
    }
    return merged.build();
  }

  private static Policies asTheNativeRequestReadsIt(Policies policies) {
    try {
      return JACKSON.readValue(JACKSON.writeValueAsBytes(policies), Policies.class);
    } catch (IOException e) {
      throw new IllegalStateException("Cannot copy policies as a request reads them", e);
    }
  }
}
