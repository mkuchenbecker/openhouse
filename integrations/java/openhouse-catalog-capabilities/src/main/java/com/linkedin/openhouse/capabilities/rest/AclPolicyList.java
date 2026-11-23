package com.linkedin.openhouse.capabilities.rest;

import java.util.List;
import org.apache.iceberg.rest.RESTResponse;

/**
 * The native ACL listing ({@code GetAclPoliciesResponseBody}): {@code {results: [{principal, role,
 * expirationEpochTimeSeconds, properties}]}}. Read by Iceberg's own JSON mapper, which reads fields
 * under kebab-case names and ignores unknown ones; SHOW GRANTS needs only the one-word fields
 * {@code principal} and {@code role}, so no annotation (and no Jackson type, which the engines'
 * Iceberg runtimes relocate) is needed.
 */
final class AclPolicyList implements RESTResponse {
  private List<Entry> results;

  private AclPolicyList() {}

  List<Entry> results() {
    return results;
  }

  @Override
  public void validate() {}

  /** One grant: a principal and the role it holds. */
  static final class Entry {
    private String principal;
    private String role;

    private Entry() {}

    String principal() {
      return principal;
    }

    String role() {
      return role;
    }
  }
}
