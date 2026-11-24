package com.daf360.payroll.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Country scope for a {@code paysId} carried in the JSON body (A1, cohort hardening).
 *
 * <p>{@code PaysIsolationInterceptor} only reads the query string, so every endpoint whose
 * country is in the body — {@code POST /simulations/individual}, {@code /cohort},
 * {@code /cohort/aggregate}, {@code /cohort/what-if/employee}, {@code /engine/run},
 * {@code POST /parameter-sets}, the employee-config upsert / simulate / calculate-net,
 * {@code POST|PUT /admin/countries} — accepted any country. This advice checks the body
 * once it is read, whatever the endpoint: any body object exposing a {@code paysId}
 * (record component or {@code getPaysId()}) must be inside the caller's scope.
 *
 * <p>Service-to-service calls under {@code /api/payroll/internal/**} carry no user and are
 * guarded by their shared key instead, exactly as the interceptor excludes them.
 */
@ControllerAdvice
public class PaysScopeBodyAdvice extends RequestBodyAdviceAdapter {

    private static final Optional<Method> NONE = Optional.empty();
    private final ConcurrentHashMap<Class<?>, Optional<Method>> accessors = new ConcurrentHashMap<>();
    private final PaysScopeGuard guard;

    public PaysScopeBodyAdvice(PaysScopeGuard guard) {
        this.guard = guard;
    }

    @Override
    public boolean supports(MethodParameter methodParameter, Type targetType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object afterBodyRead(Object body, HttpInputMessage inputMessage, MethodParameter parameter,
                                Type targetType, Class<? extends HttpMessageConverter<?>> converterType) {
        if (body == null || isInternalCall()) return body;
        Long paysId = paysIdOf(body);
        if (paysId != null) guard.requireInScope(paysId);
        return body;
    }

    /** Visible for tests. */
    Long paysIdOf(Object body) {
        Optional<Method> m = accessors.computeIfAbsent(body.getClass(), PaysScopeBodyAdvice::findAccessor);
        if (m.isEmpty()) return null;
        try {
            Object v = m.get().invoke(body);
            return v instanceof Number n ? n.longValue() : null;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static Optional<Method> findAccessor(Class<?> type) {
        if (type.isRecord()) {
            for (RecordComponent rc : type.getRecordComponents()) {
                if (rc.getName().equals("paysId")) return Optional.of(accessible(rc.getAccessor()));
            }
            return NONE;
        }
        try {
            Method getter = type.getMethod("getPaysId");
            return Number.class.isAssignableFrom(getter.getReturnType()) ? Optional.of(accessible(getter)) : NONE;
        } catch (NoSuchMethodException e) {
            return NONE;
        }
    }

    private static Method accessible(Method m) {
        m.trySetAccessible();
        return m;
    }

    private static boolean isInternalCall() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes sra)) return false;
        HttpServletRequest req = sra.getRequest();
        String uri = req.getRequestURI();
        return uri != null && uri.contains("/api/payroll/internal/");
    }
}
