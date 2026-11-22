package com.linkedin.openhouse.capabilities;

import java.util.NoSuchElementException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class PrivilegesTest {

  @Test
  void sqlPrivilegesMapToTheServiceRoles() {
    Assertions.assertEquals("TABLE_VIEWER", Privileges.fromPrivilege("SELECT").getRole());
    Assertions.assertEquals("TABLE_VIEWER", Privileges.fromPrivilege("DESCRIBE").getRole());
    Assertions.assertEquals("ACL_EDITOR", Privileges.fromPrivilege("MANAGE GRANTS").getRole());
    Assertions.assertEquals("TABLE_ADMIN", Privileges.fromPrivilege("ALTER").getRole());
    Assertions.assertEquals("TABLE_CREATOR", Privileges.fromPrivilege("CREATE TABLE").getRole());
    Assertions.assertEquals(5, Privileges.values().length);
  }

  @Test
  void rolesMapBackToTheFirstPrivilege() {
    Assertions.assertSame(Privileges.SELECT, Privileges.fromRole("TABLE_VIEWER"));
    Assertions.assertSame(Privileges.GRANT_REVOKE, Privileges.fromRole("ACL_EDITOR"));
    Assertions.assertSame(Privileges.ALTER, Privileges.fromRole("TABLE_ADMIN"));
    Assertions.assertSame(Privileges.CREATE_TABLE, Privileges.fromRole("TABLE_CREATOR"));
  }

  @Test
  void unknownNamesFailAsInTheJavaClient() {
    Assertions.assertThrows(NoSuchElementException.class, () -> Privileges.fromPrivilege("drop"));
    Assertions.assertThrows(NoSuchElementException.class, () -> Privileges.fromRole("OWNER"));
  }
}
