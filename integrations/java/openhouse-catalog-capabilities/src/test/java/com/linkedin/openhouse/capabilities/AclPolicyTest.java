package com.linkedin.openhouse.capabilities;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AclPolicyTest {

  @Test
  void holdsPrivilegeAndPrincipal() {
    AclPolicy policy = new AclPolicy("SELECT", "sraikar");
    Assertions.assertEquals("SELECT", policy.getPrivilege());
    Assertions.assertEquals("sraikar", policy.getPrincipal());
  }

  @Test
  void equalityIsByValue() {
    Assertions.assertEquals(new AclPolicy("SELECT", "a"), new AclPolicy("SELECT", "a"));
    Assertions.assertEquals(
        new AclPolicy("SELECT", "a").hashCode(), new AclPolicy("SELECT", "a").hashCode());
    Assertions.assertNotEquals(new AclPolicy("SELECT", "a"), new AclPolicy("SELECT", "b"));
    Assertions.assertNotEquals(new AclPolicy("SELECT", "a"), new AclPolicy("ALTER", "a"));
    Assertions.assertNotEquals(new AclPolicy("SELECT", "a"), null);
  }
}
