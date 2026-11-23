package com.linkedin.openhouse.capabilities.rest;

import com.linkedin.openhouse.capabilities.AclPolicy;
import com.linkedin.openhouse.capabilities.GrantsCapability;
import com.linkedin.openhouse.capabilities.Privileges;
import com.linkedin.openhouse.capabilities.UnlockCapability;
import com.linkedin.openhouse.capabilities.UnlockReason;
import com.linkedin.openhouse.capabilities.UnlockTableTarget;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.ValidationException;
import org.apache.iceberg.rest.RESTUtil;
import org.apache.iceberg.rest.ResourcePaths;

/**
 * GRANT, REVOKE, SHOW GRANTS and UNLOCK for a plain Iceberg {@code RESTCatalog}: a small client for
 * the OpenHouse routes under the catalog's IRC base path, which sits beside the unmodified catalog
 * (decision D23).
 *
 * <ul>
 *   <li>{@code POST|GET v1/{prefix}/namespaces/{ns}/tables/{t}/openhouse/acl-policies}
 *   <li>{@code POST|GET v1/{prefix}/namespaces/{ns}/openhouse/acl-policies}
 *   <li>{@code DELETE v1/{prefix}/namespaces/{ns}/tables/{t}/openhouse/lock[/{reason}]}
 * </ul>
 *
 * <p>It is built from the catalog's own properties (as {@code RESTCatalog.properties()} returns
 * them, merged with the server's config) and uses what the catalog's own client uses: the {@code
 * uri}, the {@code prefix} and namespace separator through Iceberg's {@link ResourcePaths}, the
 * {@code header.*} values, Iceberg's {@code HTTPClient} and the catalog's authentication ({@code
 * token} or OAuth {@code credential}; on Iceberg 1.12 whatever {@code AuthManager} the properties
 * select). So a statement is authenticated as the session's catalog is. The per-line part (HTTP
 * client and authentication) is {@link RestTransport}; everything else is shared.
 *
 * <p>Each call opens its transport and closes it afterwards: the client holds no connection, no
 * token and no thread between statements, so it needs no closing and is cheap to create.
 *
 * <p>Request and response bodies are the native ones ({@code {role, principal, operation}}; {@code
 * {results: [{principal, role, ...}]}}), written and read by Iceberg's own JSON mapper. The checks
 * the Java client makes before sending (one namespace level; a known privilege) are made the same
 * way, with the same exceptions. Errors keep the server's message; see {@link
 * CapabilityErrorHandler} for the exception each status becomes.
 */
public final class RestCapabilityClient implements GrantsCapability, UnlockCapability {

  static final String ACL_POLICIES = "/openhouse/acl-policies";
  static final String LOCK = "/openhouse/lock";

  private final String catalogName;
  private final Map<String, String> properties;
  private final ResourcePaths paths;

  /**
   * @param catalogName the catalog's name, for the authentication manager
   * @param catalogProperties the catalog's properties, as {@code RESTCatalog.properties()} returns
   *     them
   */
  public RestCapabilityClient(String catalogName, Map<String, String> catalogProperties) {
    this.catalogName = catalogName;
    this.properties = Collections.unmodifiableMap(new HashMap<>(catalogProperties));
    this.paths = ResourcePaths.forCatalogProperties(this.properties);
  }

  /** The catalog properties this client was built from. */
  public Map<String, String> properties() {
    return properties;
  }

  @Override
  public void updateTableAclPolicies(
      TableIdentifier table, boolean isGrant, String privilege, String principal) {
    checkOneLevel(table.namespace());
    AclPolicyUpdate body = new AclPolicyUpdate(isGrant, principal, role(privilege));
    call(
        transport ->
            transport
                .client()
                .post(
                    paths.table(table) + ACL_POLICIES,
                    body,
                    null,
                    transport.headers(),
                    CapabilityErrorHandler.TABLE));
  }

  @Override
  public List<AclPolicy> getTableAclPolicies(TableIdentifier table) {
    checkOneLevel(table.namespace());
    return toAclPolicies(
        call(
            transport ->
                transport
                    .client()
                    .get(
                        paths.table(table) + ACL_POLICIES,
                        Collections.emptyMap(),
                        AclPolicyList.class,
                        transport.headers(),
                        CapabilityErrorHandler.TABLE)));
  }

  @Override
  public void updateDatabaseAclPolicies(
      Namespace database, boolean isGrant, String privilege, String principal) {
    checkOneLevel(database);
    AclPolicyUpdate body = new AclPolicyUpdate(isGrant, principal, role(privilege));
    call(
        transport ->
            transport
                .client()
                .post(
                    paths.namespace(database) + ACL_POLICIES,
                    body,
                    null,
                    transport.headers(),
                    CapabilityErrorHandler.NAMESPACE));
  }

  @Override
  public List<AclPolicy> getDatabaseAclPolicies(Namespace database) {
    checkOneLevel(database);
    return toAclPolicies(
        call(
            transport ->
                transport
                    .client()
                    .get(
                        paths.namespace(database) + ACL_POLICIES,
                        Collections.emptyMap(),
                        AclPolicyList.class,
                        transport.headers(),
                        CapabilityErrorHandler.NAMESPACE)));
  }

  @Override
  public void unlockTable(UnlockTableTarget target, UnlockReason reason) {
    String lock =
        paths.table(TableIdentifier.of(Namespace.of(target.getDatabaseId()), target.getTableId()))
            + LOCK;
    // As the Java client: the reasonless route removes only a LEGACY lock.
    String path =
        reason == UnlockReason.LEGACY
            ? lock
            : lock + "/" + RESTUtil.encodeString(reason.getValue());
    call(
        transport ->
            transport
                .client()
                .delete(path, null, transport.headers(), CapabilityErrorHandler.TABLE));
  }

  private <T> T call(Function<RestTransport, T> request) {
    try (RestTransport transport = RestTransport.open(catalogName, properties)) {
      return request.apply(transport);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to close the capability client's HTTP client", e);
    }
  }

  /** The Java client's check, with its exception and message. */
  private static void checkOneLevel(Namespace namespace) {
    if (namespace.levels().length > 1) {
      throw new ValidationException(
          "Input namespace has more than one levels " + String.join(".", namespace.levels()));
    }
  }

  /** The service role for a SQL privilege; NoSuchElementException as in the Java client. */
  private static String role(String privilege) {
    return Privileges.fromPrivilege(privilege).getRole();
  }

  private static List<AclPolicy> toAclPolicies(AclPolicyList response) {
    List<AclPolicy> policies = new ArrayList<>();
    if (response != null && response.results() != null) {
      for (AclPolicyList.Entry entry : response.results()) {
        policies.add(
            new AclPolicy(Privileges.fromRole(entry.role()).getPrivilege(), entry.principal()));
      }
    }
    return policies;
  }
}
