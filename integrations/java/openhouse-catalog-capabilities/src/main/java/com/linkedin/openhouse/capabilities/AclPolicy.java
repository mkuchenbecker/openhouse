package com.linkedin.openhouse.capabilities;

import java.util.Objects;

/** One grant: a privilege held by a principal, as SHOW GRANTS lists it. */
public final class AclPolicy {
  private final String privilege;
  private final String principal;

  public AclPolicy(String privilege, String principal) {
    this.privilege = privilege;
    this.principal = principal;
  }

  /** The SQL privilege, for example {@code SELECT}. */
  public String getPrivilege() {
    return privilege;
  }

  /**
   * The principal as the service stores it; {@code *} is the global group (see {@link Principal}).
   */
  public String getPrincipal() {
    return principal;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof AclPolicy)) {
      return false;
    }
    AclPolicy that = (AclPolicy) other;
    return Objects.equals(privilege, that.privilege) && Objects.equals(principal, that.principal);
  }

  @Override
  public int hashCode() {
    return Objects.hash(privilege, principal);
  }

  @Override
  public String toString() {
    return "AclPolicy(" + privilege + ", " + principal + ")";
  }
}
