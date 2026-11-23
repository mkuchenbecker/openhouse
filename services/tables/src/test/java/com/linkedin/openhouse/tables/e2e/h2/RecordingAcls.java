package com.linkedin.openhouse.tables.e2e.h2;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.linkedin.openhouse.tables.api.spec.v0.response.components.AclPolicy;
import com.linkedin.openhouse.tables.authorization.AuthorizationHandler;
import com.linkedin.openhouse.tables.authorization.Privileges;
import com.linkedin.openhouse.tables.model.DatabaseDto;
import com.linkedin.openhouse.tables.model.TableDto;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * An in-memory ACL store behind a mocked {@link AuthorizationHandler}: what the service stores for
 * a grant or a revoke is what it hands the handler, so the store is the state a native or an IRC
 * ACL route leaves. Access decisions deny exactly the privileges in {@link #deny}; everything else
 * is allowed. Test-only.
 */
final class RecordingAcls {

  /** Resource key ({@code db} or {@code db.table}) to principal to the policy stored for it. */
  private final Map<String, Map<String, AclPolicy>> store = new TreeMap<>();

  private final Set<Privileges> denied = EnumSet.noneOf(Privileges.class);

  /** Every access decision the service asked for, as {@code PRIVILEGE resource}. */
  private final List<String> decisions = new ArrayList<>();

  /** Resets {@code handler} and makes it answer from this store. */
  void install(AuthorizationHandler handler) {
    reset(handler);
    store.clear();
    denied.clear();
    decisions.clear();
    when(handler.checkAccessDecision(any(), any(TableDto.class), any(Privileges.class)))
        .thenAnswer(i -> decide(i.getArgument(2), key((TableDto) i.getArgument(1))));
    when(handler.checkAccessDecision(any(), any(DatabaseDto.class), any(Privileges.class)))
        .thenAnswer(
            i -> decide(i.getArgument(2), ((DatabaseDto) i.getArgument(1)).getDatabaseId()));
    when(handler.checkSystemOnlyLockAccess(any(), any(), any())).thenCallRealMethod();
    doAnswer(
            i -> {
              grant(
                  key((TableDto) i.getArgument(4)),
                  AclPolicy.builder()
                      .role(i.getArgument(0))
                      .principal(i.getArgument(1))
                      .expirationEpochTimeSeconds(i.getArgument(2))
                      .properties(i.getArgument(3))
                      .build());
              return null;
            })
        .when(handler)
        .grantRole(any(), any(), any(), any(), any(TableDto.class), any());
    doAnswer(
            i -> {
              grant(
                  ((DatabaseDto) i.getArgument(2)).getDatabaseId(),
                  AclPolicy.builder().role(i.getArgument(0)).principal(i.getArgument(1)).build());
              return null;
            })
        .when(handler)
        .grantRole(any(), any(), any(DatabaseDto.class), any());
    doAnswer(
            i -> {
              revoke(key((TableDto) i.getArgument(2)), i.getArgument(0), i.getArgument(1));
              return null;
            })
        .when(handler)
        .revokeRole(any(), any(), any(TableDto.class), any());
    doAnswer(
            i -> {
              revoke(
                  ((DatabaseDto) i.getArgument(2)).getDatabaseId(),
                  i.getArgument(0),
                  i.getArgument(1));
              return null;
            })
        .when(handler)
        .revokeRole(any(), any(), any(DatabaseDto.class), any());
    when(handler.listAclPolicies(any(TableDto.class)))
        .thenAnswer(i -> list(key((TableDto) i.getArgument(0))));
    when(handler.listAclPolicies(any(DatabaseDto.class)))
        .thenAnswer(i -> list(((DatabaseDto) i.getArgument(0)).getDatabaseId()));
  }

  void deny(Privileges... privileges) {
    for (Privileges privilege : privileges) {
      denied.add(privilege);
    }
  }

  void allowAll() {
    denied.clear();
  }

  /** The policies stored for {@code resource}, as {@code principal=role exp props}, sorted. */
  synchronized List<String> stored(String resource) {
    return list(resource).stream()
        .map(
            p ->
                p.getPrincipal()
                    + "="
                    + p.getRole()
                    + " exp="
                    + p.getExpirationEpochTimeSeconds()
                    + " props="
                    + p.getProperties())
        .collect(Collectors.toList());
  }

  /** The access decisions asked for {@code resource} since the last call, in order. */
  synchronized List<String> takeDecisions(String resource) {
    List<String> taken =
        decisions.stream()
            .filter(d -> d.endsWith(" " + resource))
            .map(d -> d.substring(0, d.indexOf(' ')))
            .collect(Collectors.toList());
    decisions.removeIf(d -> d.endsWith(" " + resource));
    return taken;
  }

  private synchronized boolean decide(Privileges privilege, String resource) {
    decisions.add(privilege + " " + resource);
    return !denied.contains(privilege);
  }

  private synchronized void grant(String resource, AclPolicy policy) {
    store.computeIfAbsent(resource, r -> new TreeMap<>()).put(policy.getPrincipal(), policy);
  }

  private synchronized void revoke(String resource, String role, String principal) {
    Map<String, AclPolicy> policies = store.get(resource);
    if (policies != null
        && policies.containsKey(principal)
        && role.equals(policies.get(principal).getRole())) {
      policies.remove(principal);
    }
  }

  private synchronized List<AclPolicy> list(String resource) {
    return store.getOrDefault(resource, new TreeMap<>()).values().stream()
        .sorted(Comparator.comparing(AclPolicy::getPrincipal))
        .collect(Collectors.toList());
  }

  static String key(TableDto table) {
    return table.getDatabaseId() + "." + table.getTableId();
  }
}
