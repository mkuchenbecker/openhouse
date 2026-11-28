package com.linkedin.openhouse.tables.api.irc;

import static com.linkedin.openhouse.common.security.AuthenticationUtils.extractAuthenticatedUserPrincipal;

import com.linkedin.openhouse.tables.authorization.Privileges;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.apache.iceberg.rest.requests.RegisterViewRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.annotation.Secured;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The IRC view register route ({@code POST .../namespaces/{namespace}/register-view}), secured as
 * the other view routes are; the work is {@link IcebergRestViewRegistration}'s. Hidden from the
 * generated OpenAPI spec, like the other IRC routes.
 *
 * <p>Compiled only on the Iceberg 1.12 line ({@code src/iceberg-1.12}), whose {@code
 * RegisterViewRequest} it reads and whose client has {@code registerView}; LinkedIn's Iceberg 1.5.2
 * reference line has neither, and there the route answers 406 as before. Registered only where that
 * class exists, so the config lists the route exactly where it is served.
 */
@Hidden
@ConditionalOnIcebergRest
@RestController
@ConditionalOnClass(name = IcebergRestRegisterViewController.REGISTER_VIEW_CLASS)
public class IcebergRestRegisterViewController {

  /** The Iceberg class the route reads, and whose presence registers it. */
  static final String REGISTER_VIEW_CLASS = "org.apache.iceberg.rest.requests.RegisterViewRequest";

  private static final String V1 = IcebergRestCatalogController.BASE_PATH + "/v1";

  @Autowired private IcebergRestViewRegistration registration;

  @ApiResponses(@ApiResponse(responseCode = "200", description = "IRC register view: OK"))
  @PostMapping(V1 + "/namespaces/{namespace}/register-view")
  @Secured(value = Privileges.Privilege.AUTHENTICATED_VIEWS_OPS)
  public ResponseEntity<byte[]> registerView(
      @PathVariable("namespace") String namespace, @RequestBody(required = false) byte[] body) {
    RegisterViewRequest request = IcebergRestJson.read(body, RegisterViewRequest.class);
    return IcebergRestJson.ok(
        registration.registerView(
            namespace,
            request.name(),
            request.metadataLocation(),
            extractAuthenticatedUserPrincipal()));
  }
}
