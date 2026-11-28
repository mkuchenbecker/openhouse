package com.linkedin.openhouse.spark.grants;

import com.linkedin.openhouse.tables.api.spec.v0.response.components.AclPolicy;
import com.linkedin.openhouse.tables.authorization.AuthorizationHandler;
import com.linkedin.openhouse.tables.authorization.Privileges;
import com.linkedin.openhouse.tables.model.DatabaseDto;
import com.linkedin.openhouse.tables.model.TableDto;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The test services' authorization backend that stores grants. The embedded service's own handler
 * ({@code OpaAuthorizationHandler} with no OPA base URI) allows everything, drops every grant and
 * lists none, so SHOW GRANTS was always empty and a grant had no effect. This one, registered in
 * its place through Spring ({@link Config}: a {@code @Primary} bean, the existing way a test
 * replaces a bean of the service; no production code or wiring changes):
 *
 * <ul>
 *   <li>keeps, per resource ({@code db} or {@code db.table}), the roles the service hands it on
 *       GRANT and removes them on REVOKE, and lists them back in grant order, which is what SHOW
 *       GRANTS shows;
 *   <li>answers the privilege checks of {@value #ONLY_GRANTED} from what it stores, so a grant has
 *       an effect that a statement can observe: that principal holds no privilege until granted
 *       one;
 *   <li>denies {@value #STRANGER} every privilege, and allows every other principal everything, as
 *       the service's own handler does without OPA.
 * </ul>
 *
 * <p>One source, compiled by the Spark 3.5 itest's tests and by the Spark 4.2 itest's service JVMs
 * ({@code src/shared/authorization/java}), so every path meets the same service behavior.
 */
public final class GrantStoringAuthorization implements AuthorizationHandler {

  /** Denied every privilege. */
  public static final String STRANGER = "irc_stranger";

  /** Holds exactly the privileges of the roles granted to it (or to PUBLIC), and nothing else. */
  public static final String ONLY_GRANTED = "only_granted";

  /** How the service stores a grant to PUBLIC. */
  private static final String PUBLIC = "*";

  /**
   * What each role the SQL statements grant allows, as this test backend models it. The deployed
   * policy (OPA's or another handler's) is not in this repository; this is the reading of the role
   * names the OpenHouse client maps the SQL privileges to: SELECT and DESCRIBE to TABLE_VIEWER,
   * ALTER to TABLE_ADMIN, MANAGE GRANTS to ACL_EDITOR, CREATE TABLE to TABLE_CREATOR.
   */
  static final Map<String, Set<Privileges>> ROLE_PRIVILEGES;

  static {
    Map<String, Set<Privileges>> roles = new HashMap<>();
    roles.put("TABLE_VIEWER", EnumSet.of(Privileges.GET_TABLE_METADATA, Privileges.SELECT));
    roles.put(
        "TABLE_ADMIN",
        EnumSet.of(
            Privileges.GET_TABLE_METADATA, Privileges.SELECT, Privileges.UPDATE_TABLE_METADATA));
    roles.put("ACL_EDITOR", EnumSet.of(Privileges.GET_TABLE_METADATA, Privileges.UPDATE_ACL));
    roles.put("TABLE_CREATOR", EnumSet.of(Privileges.CREATE_TABLE));
    ROLE_PRIVILEGES = Collections.unmodifiableMap(roles);
  }

  /** Resource to {@code principal|role} to the stored policy, in grant order. */
  private final Map<String, Map<String, AclPolicy>> store = new TreeMap<>();

  /** Registers the handler in place of the service's own. */
  public static class Config {
    @Bean
    @Primary
    GrantStoringAuthorization grantStoringAuthorization() {
      return new GrantStoringAuthorization();
    }
  }

  private static String key(TableDto table) {
    return table.getDatabaseId() + "." + table.getTableId();
  }

  /** The stored policies of {@code resource}, as {@code principal=role}, in grant order. */
  public synchronized List<String> stored(String resource) {
    return list(resource).stream()
        .map(p -> p.getPrincipal() + "=" + p.getRole())
        .collect(Collectors.toList());
  }

  @Override
  public synchronized boolean checkAccessDecision(
      String principal, DatabaseDto database, Privileges privilege) {
    if (ONLY_GRANTED.equals(principal)) {
      return holds(principal, privilege, database.getDatabaseId());
    }
    return !STRANGER.equals(principal);
  }

  @Override
  public synchronized boolean checkAccessDecision(
      String principal, TableDto table, Privileges privilege) {
    if (ONLY_GRANTED.equals(principal)) {
      return holds(principal, privilege, key(table), table.getDatabaseId());
    }
    return !STRANGER.equals(principal);
  }

  /** Whether a role stored on one of {@code resources} for the principal or PUBLIC allows it. */
  private boolean holds(String principal, Privileges privilege, String... resources) {
    return Arrays.stream(resources)
        .flatMap(resource -> list(resource).stream())
        .filter(p -> p.getPrincipal().equals(principal) || p.getPrincipal().equals(PUBLIC))
        .anyMatch(
            p ->
                ROLE_PRIVILEGES
                    .getOrDefault(p.getRole(), EnumSet.noneOf(Privileges.class))
                    .contains(privilege));
  }

  @Override
  public synchronized void grantRole(
      String role, String principal, DatabaseDto database, String grantingPrincipal) {
    grant(database.getDatabaseId(), role, principal);
  }

  @Override
  public synchronized void grantRole(
      String role,
      String principal,
      Long expirationEpochTimeSeconds,
      Map<String, String> properties,
      TableDto table,
      String grantingPrincipal) {
    grant(key(table), role, principal);
  }

  @Override
  public synchronized void revokeRole(
      String role, String principal, DatabaseDto database, String revokingPrincipal) {
    revoke(database.getDatabaseId(), role, principal);
  }

  @Override
  public synchronized void revokeRole(
      String role, String principal, TableDto table, String revokingPrincipal) {
    revoke(key(table), role, principal);
  }

  @Override
  public synchronized List<AclPolicy> listAclPolicies(TableDto table) {
    return list(key(table));
  }

  @Override
  public synchronized List<AclPolicy> listAclPolicies(TableDto table, String userPrincipal) {
    return list(key(table)).stream()
        .filter(p -> p.getPrincipal().equals(userPrincipal))
        .collect(Collectors.toList());
  }

  @Override
  public synchronized List<AclPolicy> listAclPolicies(DatabaseDto database) {
    return list(database.getDatabaseId());
  }

  private void grant(String resource, String role, String principal) {
    store
        .computeIfAbsent(resource, r -> new LinkedHashMap<>())
        .put(principal + "|" + role, AclPolicy.builder().role(role).principal(principal).build());
  }

  private void revoke(String resource, String role, String principal) {
    Map<String, AclPolicy> policies = store.get(resource);
    if (policies != null) {
      policies.remove(principal + "|" + role);
    }
  }

  private List<AclPolicy> list(String resource) {
    return new ArrayList<>(store.getOrDefault(resource, new LinkedHashMap<>()).values());
  }
}
