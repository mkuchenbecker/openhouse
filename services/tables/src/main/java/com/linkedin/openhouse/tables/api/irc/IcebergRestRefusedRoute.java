package com.linkedin.openhouse.tables.api.irc;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a spec route that is mapped only to refuse it with the spec's 406 and a specific message
 * (view rename, which the native API lacks). It is not served, so the config response does not list
 * it ({@link IcebergRestServedRoutes}).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface IcebergRestRefusedRoute {}
