package com.linkedin.openhouse.tables.api.irc;

import com.linkedin.openhouse.common.exception.UnprocessableEntityException;
import com.linkedin.openhouse.common.exception.checked.DuplicateCreationException;
import com.linkedin.openhouse.common.exception.checked.NotFoundException;
import com.linkedin.openhouse.internal.catalog.model.NamespaceMetadata;
import com.linkedin.openhouse.internal.catalog.model.NamespacePropertyUpdate;
import com.linkedin.openhouse.tables.authorization.Privileges;
import com.linkedin.openhouse.tables.exception.InvalidPropertyUpdateException;
import com.linkedin.openhouse.tables.services.NamespacesService;
import com.linkedin.openhouse.tables.utils.AuthorizationUtils;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import javax.validation.ConstraintViolation;
import javax.validation.Validator;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.exceptions.BadRequestException;
import org.apache.iceberg.exceptions.NamespaceNotEmptyException;
import org.apache.iceberg.exceptions.NoSuchNamespaceException;
import org.apache.iceberg.rest.requests.CreateNamespaceRequest;
import org.apache.iceberg.rest.requests.UpdateNamespacePropertiesRequest;
import org.apache.iceberg.rest.responses.CreateNamespaceResponse;
import org.apache.iceberg.rest.responses.UpdateNamespacePropertiesResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The namespace writes of the Iceberg REST Catalog (IRC) protocol (create, update properties, drop)
 * as a thin layer over the namespace service ({@link NamespacesService}), which stores a namespace
 * record apart from tables. Its rules are used, not restated: its authorization (the database
 * privilege {@code SYSTEM_ADMIN} for every write), the validation of names and properties of its
 * request classes, and its own refusals (a duplicate, a missing record, overlapping updates and
 * removals, a lost race). What this layer adds is the spec's error answers, and the two places
 * where a namespace that exists by holding tables or views meets the record:
 *
 * <ul>
 *   <li>A create of a namespace that holds a table or a view is refused as an existing namespace
 *       (409), as the spec has it: the namespace exists already, record or not.
 *   <li>A drop of a namespace that holds a table or a view is refused as not empty (409), where the
 *       namespace service would delete the record alone ({@link
 *       #refuseDropWhileItHoldsATableOrView}).
 * </ul>
 *
 * <p>Order on every route: the namespace's name (in the body for create, in the path otherwise) and
 * its one level, then the authorization decision, before anything else is checked, read or
 * answered; so a principal refused the write is refused (403) whether or not the namespace exists,
 * as on the namespace service's own routes, where it decides before it reads the record. The
 * service makes the same decision again when it is called.
 */
@Component
public class IcebergRestNamespaceHandler {

  @Autowired private NamespacesService namespacesService;

  @Autowired private AuthorizationUtils authorizationUtils;

  @Autowired private IcebergRestApiHandler readHandler;

  /**
   * The application's bean validator, the one Spring MVC validates the namespace service's request
   * bodies with on its own routes.
   */
  @Autowired private Validator validator;

  public CreateNamespaceResponse createNamespace(
      CreateNamespaceRequest request, String actingPrincipal) {
    String databaseId = singleLevel(request.namespace());
    authorizeWrite(databaseId, actingPrincipal);
    validate(
        com.linkedin.openhouse.tables.api.spec.namespace.CreateNamespaceRequest.builder()
            .namespace(Collections.singletonList(databaseId))
            .properties(request.properties())
            .build(),
        databaseId);
    if (readHandler.holdsATableOrView(databaseId)) {
      throw alreadyExists(databaseId);
    }
    NamespaceMetadata created;
    try {
      created =
          namespacesService.createNamespace(databaseId, request.properties(), actingPrincipal);
    } catch (DuplicateCreationException e) {
      throw alreadyExists(databaseId);
    }
    return CreateNamespaceResponse.builder()
        .withNamespace(Namespace.of(created.getNamespaceName()))
        .setProperties(new HashMap<>(created.getProperties()))
        .build();
  }

  /**
   * Sets and removes properties of the namespace's record. A namespace that holds tables but has no
   * record has none to update: the service's answer for a missing record stands (404). No record is
   * created here.
   */
  public UpdateNamespacePropertiesResponse updateProperties(
      String databaseId, byte[] body, String actingPrincipal) {
    authorizeWrite(databaseId, actingPrincipal);
    UpdateNamespacePropertiesRequest request =
        IcebergRestJson.parse(body, UpdateNamespacePropertiesRequest.class);
    Set<String> removals = new HashSet<>(request.removals());
    validate(
        com.linkedin.openhouse.tables.api.spec.namespace.UpdateNamespacePropertiesRequest.builder()
            .updates(request.updates())
            .removals(removals)
            .build(),
        databaseId);
    NamespacePropertyUpdate result;
    try {
      result =
          namespacesService.updateNamespaceProperties(
              databaseId, request.updates(), removals, actingPrincipal);
    } catch (NotFoundException e) {
      throw noSuchNamespace(databaseId);
    } catch (InvalidPropertyUpdateException e) {
      throw new UnprocessableEntityException(e.getMessage());
    }
    return UpdateNamespacePropertiesResponse.builder()
        .addUpdated(result.getUpdated())
        .addRemoved(result.getRemoved())
        .addMissing(result.getMissing())
        .build();
  }

  public void dropNamespace(String databaseId, String actingPrincipal) {
    authorizeWrite(databaseId, actingPrincipal);
    refuseDropWhileItHoldsATableOrView(databaseId);
    try {
      namespacesService.dropNamespace(databaseId, actingPrincipal);
    } catch (NotFoundException e) {
      throw noSuchNamespace(databaseId);
    }
  }

  /**
   * The spec refuses the drop of a namespace that is not empty. The namespace service's drop
   * deletes the record alone and leaves the tables and views, so this route is stricter than the
   * service's own: a namespace that holds a table or a view is not dropped, record or not (one
   * holding tables and no record has nothing to drop and is not empty either). Checked with the
   * one-row table and view probes, before the record is touched; a table created after the probe is
   * not seen.
   */
  private void refuseDropWhileItHoldsATableOrView(String databaseId) {
    if (readHandler.holdsATableOrView(databaseId)) {
      throw new NamespaceNotEmptyException("Namespace %s is not empty", databaseId);
    }
  }

  /**
   * The namespace service's write decision ({@code SYSTEM_ADMIN} on the database, by its lower-case
   * name), made here first so that it precedes this layer's own checks.
   */
  private void authorizeWrite(String databaseId, String actingPrincipal) {
    authorizationUtils.checkDatabasePrivilege(
        databaseId.toLowerCase(Locale.ROOT), actingPrincipal, Privileges.SYSTEM_ADMIN);
  }

  /** The database of a create's namespace: exactly one non-empty level, as on every route. */
  private static String singleLevel(Namespace namespace) {
    if (namespace.length() != 1 || namespace.level(0).isEmpty()) {
      throw new BadRequestException(
          "OpenHouse namespaces are databases and have exactly one level: %s", namespace);
    }
    return namespace.level(0);
  }

  /** The namespace service's request validation, on its own request class. */
  private void validate(Object serviceRequest, String databaseId) {
    Set<ConstraintViolation<Object>> violations = validator.validate(serviceRequest);
    if (violations.isEmpty()) {
      return;
    }
    if (violations.stream().anyMatch(v -> v.getPropertyPath().toString().startsWith("namespace"))) {
      throw new BadRequestException(
          "Namespace '%s' must contain exactly one name of 1 to 128 letters, digits, or"
              + " underscores",
          databaseId);
    }
    throw new BadRequestException(
        "Invalid namespace request: %s",
        violations.stream()
            .map(v -> v.getPropertyPath() + " " + v.getMessage())
            .sorted()
            .collect(Collectors.joining("; ")));
  }

  private static AlreadyExistsException alreadyExists(String databaseId) {
    return new AlreadyExistsException("Namespace already exists: %s", databaseId);
  }

  private static NoSuchNamespaceException noSuchNamespace(String databaseId) {
    return new NoSuchNamespaceException("Namespace does not exist: %s", databaseId);
  }
}
