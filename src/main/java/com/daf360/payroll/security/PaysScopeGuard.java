package com.daf360.payroll.security;

import com.daf360.payroll.modules.ref.service.UserContextService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * The caller's country scope, checked wherever the country is NOT a query parameter: in a
 * request body ({@link PaysScopeBodyAdvice}) or on a stored object (a cohort, a simulation).
 * Same rule as {@link com.daf360.payroll.config.PaysIsolationInterceptor}, which only sees
 * {@code ?paysId=} / {@code ?pays=}: PAYROLL_SUPER_ADMIN passes, everyone else must have the
 * country in their role's scope, and an unknown scope is refused (payroll data is sensitive).
 *
 * <p>Throws a plain {@link AccessDeniedException} with a user-facing message, which
 * {@code GlobalExceptionHandler} turns into a 403 problem detail carrying that message.
 */
@Component
public class PaysScopeGuard {

    public static final String OUT_OF_SCOPE =
            "Accès refusé : ce pays est hors de votre périmètre.";

    private final UserContextService userContext;

    public PaysScopeGuard(UserContextService userContext) {
        this.userContext = userContext;
    }

    public void requireInScope(Long paysId) {
        if (paysId == null || isSuperAdmin()) return;
        PaysScopeContext.PaysScope scope = userContext.getCurrentUserPaysScope();
        if (scope == null || !scope.allows(paysId)) {
            throw new AccessDeniedException(OUT_OF_SCOPE);
        }
    }

    public static boolean isSuperAdmin() {
        return hasAuthority(PermissionCatalog.SUPER_ADMIN);
    }

    public static boolean hasAuthority(String code) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> code.equals(a.getAuthority()));
    }
}
