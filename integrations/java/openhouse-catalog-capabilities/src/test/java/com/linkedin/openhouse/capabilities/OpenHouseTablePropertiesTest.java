package com.linkedin.openhouse.capabilities;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class OpenHouseTablePropertiesTest {

  @Test
  void keysAreTheOnesThePolicyStatementsUse() {
    // The policy statements gate on the table id and send the patch under this key; the legacy
    // client and the server both read it by this exact spelling.
    Assertions.assertEquals("openhouse.tableId", OpenHouseTableProperties.TABLE_ID);
    Assertions.assertEquals("updated.openhouse.policy", OpenHouseTableProperties.POLICY_PATCH);
  }
}
