package com.linkedin.openhouse.tables.api.handler.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.linkedin.openhouse.tables.api.spec.v0.request.components.Policies;
import java.util.HashMap;
import java.util.Map;
import org.apache.iceberg.exceptions.ValidationException;
import org.junit.jupiter.api.Test;

/**
 * The merge is the only thing standing between an OpenHouse {@code SET POLICY} sent over Iceberg
 * REST and a silent no-op, so every plane it can carry is pinned here rather than left to the
 * conformance suite, which does not know OpenHouse policies exist.
 */
public class IcebergRestPolicyMergerTest {

  private static Map<String, String> props(String policies, String patch) {
    Map<String, String> map = new HashMap<>();
    if (policies != null) {
      map.put(IcebergRestPolicyMerger.POLICIES_KEY, policies);
    }
    if (patch != null) {
      map.put(IcebergRestPolicyMerger.UPDATED_POLICY_KEY, patch);
    }
    return map;
  }

  @Test
  void noPatchLeavesThePolicyDocumentAlone() {
    Policies merged = IcebergRestPolicyMerger.merge(props("{\"sharingEnabled\":true}", null));
    assertThat(merged.isSharingEnabled()).isTrue();
  }

  @Test
  void aPatchOnATableWithNoPoliciesBecomesThePolicies() {
    Policies merged = IcebergRestPolicyMerger.merge(props(null, "{\"sharingEnabled\":true}"));
    assertThat(merged.isSharingEnabled()).isTrue();
  }

  @Test
  void aPatchThatDoesNotMentionSharingLeavesSharingOn() {
    // The regression this guards: the server's sharingEnabled is a primitive, so a patch parsed
    // into a Policies object reports false for "absent" as well as for "off". Merging off the
    // object would unshare a table whose commit only changed its retention.
    Policies merged =
        IcebergRestPolicyMerger.merge(
            props(
                "{\"sharingEnabled\":true}",
                "{\"retention\":{\"count\":3,\"granularity\":\"DAY\"}}"));
    assertThat(merged.isSharingEnabled()).isTrue();
    assertThat(merged.getRetention().getCount()).isEqualTo(3);
  }

  @Test
  void aPatchThatSaysSharingIsOffTurnsSharingOff() {
    Policies merged =
        IcebergRestPolicyMerger.merge(
            props("{\"sharingEnabled\":true}", "{\"sharingEnabled\":false}"));
    assertThat(merged.isSharingEnabled()).isFalse();
  }

  @Test
  void columnTagsMergePerColumnRatherThanReplacingTheMap() {
    Policies merged =
        IcebergRestPolicyMerger.merge(
            props(
                "{\"columnTags\":{\"col1\":{\"tags\":[\"PII\"]}}}",
                "{\"columnTags\":{\"col2\":{\"tags\":[\"HC\"]}}}"));
    assertThat(merged.getColumnTags()).containsOnlyKeys("col1", "col2");
  }

  @Test
  void aPatchWinsOnAColumnBothSidesName() {
    Policies merged =
        IcebergRestPolicyMerger.merge(
            props(
                "{\"columnTags\":{\"col1\":{\"tags\":[\"PII\"]}}}",
                "{\"columnTags\":{\"col1\":{\"tags\":[\"HC\"]}}}"));
    assertThat(merged.getColumnTags().get("col1").getTags())
        .containsExactly(
            com.linkedin.openhouse.tables.api.spec.v0.request.components.PolicyTag.Tag.HC);
  }

  @Test
  void retentionAndHistoryAreReplacedWholesaleByThePatch() {
    Policies merged =
        IcebergRestPolicyMerger.merge(
            props(
                "{\"retention\":{\"count\":3,\"granularity\":\"DAY\"},"
                    + "\"history\":{\"maxAge\":2,\"granularity\":\"DAY\"}}",
                "{\"retention\":{\"count\":7,\"granularity\":\"DAY\"}}"));
    assertThat(merged.getRetention().getCount()).isEqualTo(7);
    assertThat(merged.getHistory().getMaxAge()).isEqualTo(2);
  }

  @Test
  void thePatchKeyIsConsumedAndNeverPersisted() {
    Map<String, String> stored =
        IcebergRestPolicyMerger.withoutPatchKey(props("{}", "{\"sharingEnabled\":true}"));
    assertThat(stored).doesNotContainKey(IcebergRestPolicyMerger.UPDATED_POLICY_KEY);
    assertThat(stored).containsKey(IcebergRestPolicyMerger.POLICIES_KEY);
  }

  @Test
  void anUnparseablePatchIsRejectedRatherThanIgnored() {
    assertThatThrownBy(() -> IcebergRestPolicyMerger.merge(props("{}", "{not json")))
        .isInstanceOf(ValidationException.class);
  }
}
