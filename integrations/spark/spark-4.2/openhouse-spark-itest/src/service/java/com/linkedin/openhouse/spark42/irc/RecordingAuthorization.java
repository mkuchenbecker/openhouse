package com.linkedin.openhouse.spark42.irc;

import com.linkedin.openhouse.tables.api.spec.v0.response.components.AclPolicy;
import com.linkedin.openhouse.tables.authorization.AuthorizationHandler;
import com.linkedin.openhouse.tables.authorization.Privileges;
import com.linkedin.openhouse.tables.model.DatabaseDto;
import com.linkedin.openhouse.tables.model.TableDto;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * An authorization handler that stores grants: the embedded service's default handler (OPA with no
 * base URI) drops every grant and lists none, so SHOW GRANTS and the stored ACLs could not be
 * compared. This one keeps, per resource ({@code db} or {@code db.table}), the roles the service
 * hands it, lists them back in grant order, and denies every privilege to {@link #STRANGER} only,
 * as {@link IrcReadTestService.DenyStrangerConfig} does. The same class as the Spark 3.5 itest's
 * {@code RecordingAuthorization}, so both lines' statements meet the same service behavior.
 */
final class RecordingAuthorization implements AuthorizationHandler {
  static final String STRANGER = IrcReadTestService.STRANGER;

  /** Resource to {@code principal|role} to the stored policy, in grant order. */
  private final Map<String, Map<String, AclPolicy>> store = new TreeMap<>();

  /** Registers the handler in place of the service's own. */
  static class Config {
    @Bean
    @Primary
    RecordingAuthorization recordingAuthorization() {
      return new RecordingAuthorization();
    }
  }

  private static String key(TableDto table) {
    return table.getDatabaseId() + "." + table.getTableId();
  }

  /** The stored policies of {@code resource}, as {@code principal=role}, in grant order. */
  synchronized List<String> stored(String resource) {
    return list(resource).stream()
        .map(p -> p.getPrincipal() + "=" + p.getRole())
        .collect(Collectors.toList());
  }

  @Override
  public boolean checkAccessDecision(String principal, DatabaseDto database, Privileges privilege) {
    return !STRANGER.equals(principal);
  }

  @Override
  public boolean checkAccessDecision(String principal, TableDto table, Privileges privilege) {
    return !STRANGER.equals(principal);
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
