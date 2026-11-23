package com.linkedin.openhouse.capabilities;

import java.util.Arrays;

/** The SQL privileges GRANT and REVOKE accept, and the service role each one maps to. */
public enum Privileges {
  SELECT("SELECT", "TABLE_VIEWER"),
  DESCRIBE("DESCRIBE", "TABLE_VIEWER"),
  GRANT_REVOKE("MANAGE GRANTS", "ACL_EDITOR"),
  ALTER("ALTER", "TABLE_ADMIN"),
  CREATE_TABLE("CREATE TABLE", "TABLE_CREATOR");

  private final String privilege;
  private final String role;

  Privileges(String privilege, String role) {
    this.privilege = privilege;
    this.role = role;
  }

  /** The SQL spelling. */
  public String getPrivilege() {
    return privilege;
  }

  /** The service role. */
  public String getRole() {
    return role;
  }

  /** @throws java.util.NoSuchElementException if no privilege is spelled {@code privilegeString} */
  public static Privileges fromPrivilege(String privilegeString) {
    return Arrays.stream(values())
        .filter(x -> x.getPrivilege().equals(privilegeString))
        .findFirst()
        .get();
  }

  /**
   * @return the first privilege that maps to the role
   * @throws java.util.NoSuchElementException if no privilege maps to {@code roleString}
   */
  public static Privileges fromRole(String roleString) {
    return Arrays.stream(values()).filter(x -> x.getRole().equals(roleString)).findFirst().get();
  }
}
