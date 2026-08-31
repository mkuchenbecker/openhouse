package com.linkedin.openhouse.tables.api.handler.impl;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.Policies;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.PolicyTag;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.iceberg.exceptions.ValidationException;

/**
 * Server-side port of the policy patch merge the OpenHouse Java client performs today in {@code
 * OpenHouseTableOperations#buildUpdatedPolicies}.
 *
 * <p><b>Why this has to exist for the REST facade.</b> Every OpenHouse {@code SET POLICY} statement
 * -- retention, sharing, column tags, replication, history, and the unset variants -- is compiled
 * by the Spark extensions into a single table property, {@value #UPDATED_POLICY_KEY}, holding the
 * <em>patch</em> the statement expresses. On the {@code /v1} path that key never reaches the
 * server: the client reads it, merges it into the table's current policies, sends the merged result
 * in {@code CreateUpdateTableRequestBody.policies}, and drops the key from the property map. The
 * server has therefore never seen it.
 *
 * <p>Over Iceberg REST there is no OpenHouse client in the path. A {@code SET POLICY} arrives as a
 * plain {@code SetProperties} update carrying {@value #UPDATED_POLICY_KEY}, and a server that did
 * not know the key would store it as an ordinary user property and leave {@code policies}
 * untouched: the statement would report success and change nothing. A silent no-op on a governance
 * write -- retention, sharing, PII column tags -- is the one outcome that must not happen, so the
 * key is reserved here and the merge is applied.
 *
 * <p><b>Presence, not truthiness.</b> A patch sets only the planes it mentions. The client's
 * generated {@code Policies} model uses a boxed {@code Boolean} for {@code sharingEnabled} and can
 * read absence off the object; the server's {@link Policies} uses a primitive, where an absent
 * {@code sharingEnabled} and an explicit {@code false} are the same value. Merging off the parsed
 * object would therefore turn "this patch says nothing about sharing" into "disable sharing" and
 * silently unshare a table. The raw JSON is consulted for presence instead.
 */
final class IcebergRestPolicyMerger {

  /** The patch key the OpenHouse Spark extensions emit for every {@code SET POLICY} statement. */
  static final String UPDATED_POLICY_KEY = "updated.openhouse.policy";

  /** The table property holding the table's current, already-merged policies. */
  static final String POLICIES_KEY = "policies";

  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

  private IcebergRestPolicyMerger() {}

  /**
   * Returns the policies the commit should end up with: the table's current policies with the patch
   * in {@value #UPDATED_POLICY_KEY} applied, or the current policies unchanged when there is no
   * patch.
   *
   * @param properties the property map of the metadata the commit intends to publish
   * @throws ValidationException if either policy document is unparseable
   */
  static Policies merge(Map<String, String> properties) {
    if (properties == null) {
      return null;
    }
    Policies policies = parse(properties.get(POLICIES_KEY));
    if (!properties.containsKey(UPDATED_POLICY_KEY)) {
      return policies;
    }
    String patchJson = properties.get(UPDATED_POLICY_KEY);
    Policies patch = parse(patchJson);
    if (patch == null) {
      return policies;
    }
    if (policies == null) {
      // Nothing to patch: the patch is the whole policy document.
      return patch;
    }

    JsonObject stated = asObject(patchJson);
    Policies.PoliciesBuilder merged = policies.toBuilder();
    if (patch.getRetention() != null) {
      merged.retention(patch.getRetention());
    }
    if (stated.has("sharingEnabled")) {
      merged.sharingEnabled(patch.isSharingEnabled());
    }
    if (patch.getColumnTags() != null) {
      merged.columnTags(mergeColumnTags(policies.getColumnTags(), patch.getColumnTags()));
    }
    if (patch.getReplication() != null) {
      merged.replication(patch.getReplication());
    }
    if (patch.getHistory() != null) {
      merged.history(patch.getHistory());
    }
    return merged.build();
  }

  /** The property map to persist: the patch key itself is consumed here and never stored. */
  static Map<String, String> withoutPatchKey(Map<String, String> properties) {
    if (properties == null || !properties.containsKey(UPDATED_POLICY_KEY)) {
      return properties;
    }
    Map<String, String> result = new LinkedHashMap<>(properties);
    result.remove(UPDATED_POLICY_KEY);
    return result;
  }

  /**
   * Column tags are merged per column, not replaced wholesale: a patch that tags one column must
   * not drop the tags on every other column. Where both sides name a column the patch wins.
   */
  private static Map<String, PolicyTag> mergeColumnTags(
      Map<String, PolicyTag> existing, Map<String, PolicyTag> patch) {
    Map<String, PolicyTag> result =
        existing == null ? new LinkedHashMap<>() : new LinkedHashMap<>(existing);
    result.putAll(patch);
    return result;
  }

  private static Policies parse(String json) {
    if (json == null || json.isEmpty()) {
      return null;
    }
    try {
      return GSON.fromJson(json, Policies.class);
    } catch (JsonParseException e) {
      throw new ValidationException("Cannot parse policies document: %s", e.getMessage());
    }
  }

  private static JsonObject asObject(String json) {
    try {
      return JsonParser.parseString(json).getAsJsonObject();
    } catch (RuntimeException e) {
      throw new ValidationException("Cannot parse policies document: %s", e.getMessage());
    }
  }
}
