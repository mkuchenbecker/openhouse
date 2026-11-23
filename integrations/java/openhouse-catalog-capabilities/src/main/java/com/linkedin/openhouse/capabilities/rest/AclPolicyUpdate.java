package com.linkedin.openhouse.capabilities.rest;

import org.apache.iceberg.rest.RESTRequest;

/**
 * The native ACL update body ({@code UpdateAclPoliciesRequestBody}): {@code {role, principal,
 * operation}}, as the Java client sends it for GRANT and REVOKE. Written by Iceberg's own JSON
 * mapper, which reads fields and names them in kebab case; every field here is one word, so the
 * names are the native ones.
 */
final class AclPolicyUpdate implements RESTRequest {
  private final String role;
  private final String principal;
  private final String operation;

  AclPolicyUpdate(boolean isGrant, String principal, String role) {
    this.role = role;
    this.principal = principal;
    this.operation = isGrant ? "GRANT" : "REVOKE";
  }

  @Override
  public void validate() {}
}
