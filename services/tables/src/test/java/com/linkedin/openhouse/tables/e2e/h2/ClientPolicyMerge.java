package com.linkedin.openhouse.tables.e2e.h2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.linkedin.openhouse.tables.client.invoker.ApiClient;
import com.linkedin.openhouse.tables.client.model.LockState;
import com.linkedin.openhouse.tables.client.model.Policies;
import java.io.IOException;
import java.util.Map;

/**
 * The test oracle for "what the OpenHouse Java client sends": the client's policy merge, copied
 * line for line from {@code OpenHouseTableOperations.buildUpdatedPolicies} and {@code
 * toPoliciesObject} (Iceberg 1.2 Java runtime, the source the 1.5 runtime is built from), over the
 * same generated client model and the same JSON libraries (Gson to parse, the generated {@code
 * ApiClient}'s Jackson mapper to write the request body). The runtime itself cannot be on the
 * service's test classpath (it brings its own Iceberg line), so it is copied here; the Spark itest
 * {@code IcebergRestPolicySqlTest} compares the server against the real client end to end.
 *
 * <p>Only the copy's types differ: the runtime's relocated {@code gen.tables.client.model} classes
 * are the generated {@code tables.client.model} classes before relocation.
 */
final class ClientPolicyMerge {
  static final String POLICIES_KEY = "policies";
  static final String UPDATED_OPENHOUSE_POLICY_KEY = "updated.openhouse.policy";

  private static final ObjectMapper CLIENT_MAPPER =
      ApiClient.createDefaultObjectMapper(ApiClient.createDefaultDateFormat());

  private ClientPolicyMerge() {}

  /**
   * The {@code policies} field of the native request the client sends for metadata whose properties
   * are {@code properties}, as the client's request body writes it.
   */
  static JsonNode requestPolicies(Map<String, String> properties) {
    return CLIENT_MAPPER.valueToTree(buildUpdatedPolicies(properties));
  }

  // ---- copied from OpenHouseTableOperations (only `metadata.properties()` became a parameter)

  static Policies buildUpdatedPolicies(Map<String, String> properties) {
    Policies policies =
        properties.containsKey(POLICIES_KEY)
            ? toPoliciesObject(properties.get(POLICIES_KEY))
            : null;
    if (!properties.containsKey(UPDATED_OPENHOUSE_POLICY_KEY)) {
      return policies;
    }
    Policies patchUpdatedPolicy = toPoliciesObject(properties.get(UPDATED_OPENHOUSE_POLICY_KEY));

    // Nothing the patch, patchUpdatedPolicy is the new Policy.
    if (policies == null) {
      return patchUpdatedPolicy;
    }

    // Update retention config
    if (patchUpdatedPolicy.getRetention() != null) {
      policies.setRetention(patchUpdatedPolicy.getRetention());
    }

    // Update sharing config
    if (patchUpdatedPolicy.getSharingEnabled() != null) {
      policies.sharingEnabled(patchUpdatedPolicy.getSharingEnabled());
    }
    // Update column policy tag config
    if (patchUpdatedPolicy.getColumnTags() != null) {
      if (policies.getColumnTags() != null) {
        policies
            .getColumnTags()
            .forEach(
                (k, v) ->
                    patchUpdatedPolicy
                        .getColumnTags()
                        .merge(k, v, (updatedSet, oldSet) -> updatedSet));
      }
      policies.setColumnTags(patchUpdatedPolicy.getColumnTags());
    }
    // Update replication config
    if (patchUpdatedPolicy.getReplication() != null) {
      policies.replication(patchUpdatedPolicy.getReplication());
    }
    // Update history config
    if (patchUpdatedPolicy.getHistory() != null) {
      policies.setHistory(patchUpdatedPolicy.getHistory());
    }

    return policies;
  }

  private static Policies toPoliciesObject(String policiesString) throws JsonParseException {
    if (policiesString.length() != 0) {
      try {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        return gson.fromJson(policiesString, Policies.class);
      } catch (JsonParseException e) {
        Policies systemOnlyPolicies = toActiveSystemOnlyPoliciesObject(policiesString);
        if (systemOnlyPolicies != null) {
          return systemOnlyPolicies;
        }
        throw new JsonParseException(
            "OpenHouse: Cannot convert policies string to policies object");
      }
    }
    return null;
  }

  private static Policies toActiveSystemOnlyPoliciesObject(String policiesString) {
    ObjectMapper mapper = ApiClient.createDefaultObjectMapper(ApiClient.createDefaultDateFormat());
    try {
      JsonNode lockState = mapper.readTree(policiesString).path("lockState");
      if (lockState.path("locked").booleanValue()
          && LockState.ReasonEnum.SYSTEM_ONLY
              .getValue()
              .equals(lockState.path("reason").textValue())) {
        return mapper.readValue(policiesString, Policies.class);
      }
    } catch (IOException e) {
      // The caller still throws the existing parse failure.
    }
    return null;
  }
}
