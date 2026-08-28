package com.linkedin.openhouse.common.audit;

/**
 * Marker for an exception-handler advice class whose rendered responses must be service-audited.
 *
 * <p>{@link ServiceAuditAspect} audits failed requests around the shared handler in {@code
 * common.exception.handler}. A service that owns part of its error surface with its own
 * {@code @ControllerAdvice} (e.g. the tables service's Iceberg REST views error rendering)
 * implements this interface so the aspect wraps that advice too; otherwise its failures would
 * silently stop producing audit events. The aspect handles both the OpenHouse {@code
 * ErrorResponseBody} and pre-serialized {@code String} envelope bodies.
 */
public interface AuditedResponseRenderer {}
