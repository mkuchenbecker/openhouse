package com.linkedin.openhouse.common.audit;

import static com.linkedin.openhouse.common.audit.CachingRequestBodyFilter.ATTRIBUTE_START_TIME;
import static com.linkedin.openhouse.common.security.AuthenticationUtils.extractAuthenticatedUserPrincipal;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.linkedin.openhouse.cluster.configs.ClusterProperties;
import com.linkedin.openhouse.cluster.metrics.micrometer.MetricsReporter;
import com.linkedin.openhouse.common.api.spec.ErrorResponseBody;
import com.linkedin.openhouse.common.audit.model.ServiceAuditEvent;
import com.linkedin.openhouse.common.audit.model.ServiceName;
import com.linkedin.openhouse.common.metrics.MetricsConstant;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import javax.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.WebUtils;

/**
 * Aspect class to support request level auditing for all services including tables, jobs, and hts.
 * It enhances the ability of particular methods by adding logic of building and emitting audit
 * events.
 */
@Aspect
@Component
@Slf4j
public class ServiceAuditAspect {

  @Autowired private ClusterProperties clusterProperties;

  @Autowired private AuditHandler<ServiceAuditEvent> serviceAuditHandler;

  private static final MetricsReporter METRICS_REPORTER =
      MetricsReporter.of(MetricsConstant.SERVICE_AUDIT);

  /**
   * The tables service's Iceberg REST Catalog routes (its {@code IcebergRestCatalogController}).
   */
  private static final String ICEBERG_REST_BASE_PATH = "/iceberg";

  private static final String SESSION_ID = "session-id";
  private static final String ACTION_TYPE = "X-OpenHouse-Action-Type";

  /**
   * Install the Around advice for all controller methods.
   *
   * @param point The controller method being enhanced
   * @param apiResponses The controller method's annotation, bound by type so that relocating
   *     io.swagger (as the shaded test fixtures do) updates the pointcut with it
   * @return Result of the controller method
   * @throws Throwable Any exception during execution of the controller method
   */
  @Around(value = "@annotation(apiResponses)", argNames = "point,apiResponses")
  protected Object auditSuccessfulRequests(ProceedingJoinPoint point, ApiResponses apiResponses)
      throws Throwable {
    Object result = null;
    try {
      result = point.proceed();
      try {
        RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
        if (requestAttributes != null) {
          HttpServletRequest request = ((ServletRequestAttributes) requestAttributes).getRequest();
          ResponseEntity<?> response = (ResponseEntity<?>) result;
          ServiceAuditEvent event =
              buildServiceAuditEvent(
                  (Instant) request.getAttribute(ATTRIBUTE_START_TIME),
                  Instant.now(),
                  request,
                  response,
                  false);
          serviceAuditHandler.audit(event);
        }
      } catch (Exception e) {
        log.error("Exception during auditing successful requests:\n", e);
        METRICS_REPORTER.count(MetricsConstant.FAILED_SERVICE_AUDIT);
      }
    } catch (Throwable t) {
      // Controller method failed. Just throw the error to be captured by {@link
      // com.linkedin.openhouse.common.exception.handler.OpenHouseExceptionHandler }
      // and {@link #auditFailedRequests(ProceedingJoinPoint)} will be invoked.
      throw t;
    }
    return result;
  }

  /**
   * Install the Around advice for all exception handler methods.
   *
   * @param point The exception handler method being enhanced
   * @return Result of the exception handler method
   * @throws Throwable Any exception during execution of the exception handler method
   */
  @Around(
      "execution(* com.linkedin.openhouse.common.exception.handler.*.*(..)) "
          + "|| (execution(* com.linkedin.openhouse.tables.exception.ViewExceptionHandler*.*(..)) "
          + "&& @annotation(org.springframework.web.bind.annotation.ExceptionHandler))")
  protected Object auditFailedRequests(ProceedingJoinPoint point) throws Throwable {
    Object result = null;
    try {
      result = point.proceed();
      try {
        RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
        if (requestAttributes != null) {
          HttpServletRequest request = ((ServletRequestAttributes) requestAttributes).getRequest();
          ResponseEntity<ErrorResponseBody> response = (ResponseEntity<ErrorResponseBody>) result;
          ServiceAuditEvent event =
              buildServiceAuditEvent(
                  (Instant) request.getAttribute(ATTRIBUTE_START_TIME),
                  Instant.now(),
                  request,
                  response,
                  true);
          serviceAuditHandler.audit(event);
          return buildUpdatedResponseEntity(response);
        }
      } catch (Exception e) {
        log.error("Exception during auditing failed requests:\n", e);
        METRICS_REPORTER.count(MetricsConstant.FAILED_SERVICE_AUDIT);
      }
    } catch (Throwable t) {
      // If there's an error during exception handling, we just throw it because audit module should
      // not impact exception handling.
      throw t;
    }
    return result;
  }

  /**
   * Audits a failed request whose error is rendered by a handler this aspect does not advise,
   * because its error body is not an {@link ErrorResponseBody}: the Iceberg REST Catalog routes
   * answer in that specification's error shape. The event is the one {@link #auditFailedRequests}
   * emits for a native failure, built from the same request; the caller supplies what a native
   * handler puts in its error body. Like the advice, it never fails the request it audits.
   *
   * @param statusCode the status answered
   * @param responseErrorMessage the message answered
   * @param stacktrace the failure's stack trace, abbreviated as the native handlers abbreviate it
   * @param cause the failure's cause, as the native handlers word it
   */
  public void auditFailedRequest(
      int statusCode, String responseErrorMessage, String stacktrace, String cause) {
    try {
      RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
      if (requestAttributes != null) {
        HttpServletRequest request = ((ServletRequestAttributes) requestAttributes).getRequest();
        serviceAuditHandler.audit(
            buildServiceAuditEvent(
                (Instant) request.getAttribute(ATTRIBUTE_START_TIME),
                Instant.now(),
                request,
                statusCode,
                responseErrorMessage,
                stacktrace,
                cause));
      }
    } catch (Exception e) {
      log.error("Exception during auditing failed requests:\n", e);
      METRICS_REPORTER.count(MetricsConstant.FAILED_SERVICE_AUDIT);
    }
  }

  private ServiceAuditEvent buildServiceAuditEvent(
      Instant startTime,
      Instant endTime,
      HttpServletRequest request,
      ResponseEntity<?> response,
      boolean isFailed) {
    // Get response error message
    String responseErrorMessage = null;
    String stacktrace = null;
    String cause = null;
    if (isFailed) {
      ErrorResponseBody errorResponseBody =
          ((ResponseEntity<ErrorResponseBody>) response).getBody();
      if (errorResponseBody != null) {
        responseErrorMessage = errorResponseBody.getMessage();
        stacktrace = errorResponseBody.getStacktrace();
        cause = errorResponseBody.getCause();
      }
    }
    return buildServiceAuditEvent(
        startTime,
        endTime,
        request,
        response.getStatusCodeValue(),
        responseErrorMessage,
        stacktrace,
        cause);
  }

  private ServiceAuditEvent buildServiceAuditEvent(
      Instant startTime,
      Instant endTime,
      HttpServletRequest request,
      int statusCode,
      String responseErrorMessage,
      String stacktrace,
      String cause) {

    // Get request payload
    JsonElement requestPayload = null;
    try {
      ContentCachingRequestWrapper wrapper =
          WebUtils.getNativeRequest(request, ContentCachingRequestWrapper.class);
      if (wrapper != null) {
        String requestPayloadStr =
            new String(wrapper.getContentAsByteArray(), StandardCharsets.UTF_8);
        requestPayload = JsonParser.parseString(requestPayloadStr);
      }
    } catch (Exception e) {
      // A payload that could not be parsed is dropped from the audit event.
      log.error("Exception during parsing request payload:\n", e);
      METRICS_REPORTER.count(MetricsConstant.FAILED_PARSING_REQUEST_PAYLOAD);
    }
    // Get request uri and query string
    String uriAndQueryString =
        request.getQueryString() == null
            ? request.getRequestURI()
            : request.getRequestURI() + "?" + request.getQueryString();
    // Build ServiceAuditEvent
    return ServiceAuditEvent.builder()
        .startTimestamp(startTime)
        .endTimestamp(endTime)
        .sessionId(request.getHeader(SESSION_ID))
        .clusterName(clusterProperties.getClusterName())
        .serviceName(getServiceNameFromRequestURI(request.getRequestURI()))
        .user(extractAuthenticatedUserPrincipal())
        .actionType(request.getHeader(ACTION_TYPE))
        .method(HttpMethod.valueOf(request.getMethod()))
        .uri(uriAndQueryString)
        .requestPayload(requestPayload)
        .statusCode(statusCode)
        .responseErrorMessage(responseErrorMessage)
        .stacktrace(stacktrace)
        .cause(cause)
        .build();
  }

  private ServiceName getServiceNameFromRequestURI(String uri) {
    if (uri.startsWith("/jobs")) {
      return ServiceName.JOBS_SERVICE;
    } else if (uri.startsWith("/hts")) {
      return ServiceName.HOUSETABLES_SERVICE;
    } else if (uri.startsWith("/databases")
        || uri.matches("/v[0-9]+/databases.*")
        || uri.startsWith(ICEBERG_REST_BASE_PATH + "/")) {
      return ServiceName.TABLES_SERVICE;
    }
    return ServiceName.UNRECOGNIZED;
  }

  /**
   * Build updated response for the client by excluding stacktrace
   *
   * @param response
   * @return ResponseEntity
   */
  private ResponseEntity<ErrorResponseBody> buildUpdatedResponseEntity(
      ResponseEntity<ErrorResponseBody> response) {
    ErrorResponseBody errorResponseBody = response.getBody();
    if (errorResponseBody != null) {
      ErrorResponseBody updatedErrorResponseBody =
          ErrorResponseBody.builder()
              .status(errorResponseBody.getStatus())
              .error(errorResponseBody.getError())
              .message(errorResponseBody.getMessage())
              .cause(errorResponseBody.getCause())
              .build();
      return new ResponseEntity<>(updatedErrorResponseBody, updatedErrorResponseBody.getStatus());
    }
    return response;
  }
}
