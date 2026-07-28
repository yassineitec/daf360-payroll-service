package com.daf360.payroll.config;

import com.daf360.payroll.modules.ref.entity.UsersRef;
import com.daf360.payroll.modules.ref.service.UserContextService;
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

        Long userPaysId = userContextService.currentUser()
                .map(UsersRef::getPaysId)
                .orElse(null);
        if (userPaysId == null || !userPaysId.equals(requestedPays)) {
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
