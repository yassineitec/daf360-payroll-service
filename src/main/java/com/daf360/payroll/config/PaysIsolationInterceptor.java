package com.daf360.payroll.config;

import com.daf360.payroll.modules.ref.service.UserContextService;
import com.daf360.payroll.security.PaysScopeContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * Enforces per-entity pays (country) data isolation for all /api/payroll/** endpoints.
 * PAYROLL_SUPER_ADMIN bypasses the check.
 *
 * Everyone else may only pass a paysId inside their ROLE's country scope (V74): mode ALL =
 * any country, LIST = the countries listed on the role (same set for every holder), OWN =
 * their own UsersRef.pays_id, which is also what a token without scope claims resolves to.
 *
 * A guard on a request parameter, not an injector: an endpoint taking no paysId is not
 * country-filtered at all. Kept aligned with facturation-service's copy of this class.
 */
@Component
@RequiredArgsConstructor
public class PaysIsolationInterceptor implements HandlerInterceptor {

    private final UserContextService userContextService;

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {

        String rawPaysId = request.getParameter("paysId");
        if (rawPaysId == null) rawPaysId = request.getParameter("pays");
        if (rawPaysId == null) {
            return true;
        }

        long requestedPays;
        try {
            requestedPays = Long.parseLong(rawPaysId);
        } catch (NumberFormatException e) {
            return true;
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "PAYROLL_SUPER_ADMIN".equals(a.getAuthority()))) {
            return true;
        }

        PaysScopeContext.PaysScope scope = userContextService.getCurrentUserPaysScope();
        // Unresolved entity previously meant 403 here (userPaysId == null failed the check),
        // unlike facturation which allowed it. That asymmetry is kept: payroll data is more
        // sensitive, so an unknown scope stays denied rather than becoming permissive.
        if (scope == null || !scope.allows(requestedPays)) {
            forbidden(response);
            return false;
        }

        return true;
    }

    private void forbidden(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(
            "{\"error\":\"Accès refusé : vous ne pouvez accéder qu'aux données de votre entité.\"}"
        );
    }
}
