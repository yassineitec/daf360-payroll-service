package com.daf360.payroll.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a request-body record whose unknown JSON fields must be rejected with 400 instead of
 * silently ignored (the application-wide Jackson setting ignores them). Enforced by
 * {@link StrictJsonBodyAdvice} for that type only, so no other endpoint changes behaviour.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface StrictJson {}
