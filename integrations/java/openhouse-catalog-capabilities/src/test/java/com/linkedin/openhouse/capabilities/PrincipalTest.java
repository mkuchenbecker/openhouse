package com.linkedin.openhouse.capabilities;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class PrincipalTest {

  @Test
  void publicIsTheGlobalGroupInAnyCase() {
    Assertions.assertEquals("*", Principal.toAcl("PUBLIC"));
    Assertions.assertEquals("*", Principal.toAcl("public"));
    Assertions.assertEquals("*", Principal.toAcl("Public"));
  }

  @Test
  void otherPrincipalsPassThroughUnchanged() {
    Assertions.assertEquals("sraikar", Principal.toAcl("sraikar"));
    Assertions.assertEquals("Group:Team", Principal.toAcl("Group:Team"));
  }

  @Test
  void theGlobalGroupReadsBackAsPublic() {
    Assertions.assertEquals("PUBLIC", Principal.fromAcl("*"));
    Assertions.assertEquals("sraikar", Principal.fromAcl("sraikar"));
    Assertions.assertEquals("public", Principal.fromAcl("public"));
  }
}
