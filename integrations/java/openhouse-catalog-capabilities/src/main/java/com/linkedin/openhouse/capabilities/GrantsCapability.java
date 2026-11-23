package com.linkedin.openhouse.capabilities;

import java.util.List;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;

/**
 * The {@link CapabilityNames#GRANTS} capability: GRANT, REVOKE and SHOW GRANTS on tables and
 * databases.
 */
public interface GrantsCapability {

  /**
   * {@code GRANT|REVOKE <privilege> ON TABLE <db.table> TO|FROM <principal>}.
   *
   * @param table the table, for example {@code db.table}
   * @param isGrant true for GRANT, false for REVOKE
   * @param privilege the SQL privilege, for example {@code SELECT} (see {@link Privileges})
   * @param principal the principal as access policies spell it (see {@link Principal#toAcl})
   */
  void updateTableAclPolicies(
      TableIdentifier table, boolean isGrant, String privilege, String principal);

  /** {@code SHOW GRANTS ON TABLE <db.table>}: every principal and its privileges. */
  List<AclPolicy> getTableAclPolicies(TableIdentifier table);

  /**
   * {@code GRANT|REVOKE <privilege> ON DATABASE <db> TO|FROM <principal>}.
   *
   * @param database the database, for example {@code db}
   * @param isGrant true for GRANT, false for REVOKE
   * @param privilege the SQL privilege, for example {@code CREATE TABLE}
   * @param principal the principal as access policies spell it
   */
  void updateDatabaseAclPolicies(
      Namespace database, boolean isGrant, String privilege, String principal);

  /** {@code SHOW GRANTS ON DATABASE <db>}: every principal and its privileges. */
  List<AclPolicy> getDatabaseAclPolicies(Namespace database);
}
