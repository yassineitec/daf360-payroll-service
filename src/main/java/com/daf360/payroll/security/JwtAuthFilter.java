package com.daf360.payroll.security;

import com.daf360.payroll.config.AppProperties;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Tries token sources in order: Bearer header → daf360_rh HMAC cookie → daf360_access RSA cookie.
 * First valid token wins. Sets principal = userId (String of sub claim).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final AppProperties appProperties;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        if (appProperties.isJwtDisabled()) {
            List<SimpleGrantedAuthority> allAuthorities = PermissionCatalog.ALL_CODES.stream()
                    .map(SimpleGrantedAuthority::new)
                    .collect(Collectors.toList());
            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken("1", null, allAuthorities);
            auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(auth);
            filterChain.doFilter(request, response);
            return;
        }

        String token = resolveToken(request);

        if (token != null) {
            try {
                Claims claims = jwtService.parseToken(token);
                @SuppressWarnings("unchecked")
                List<String> permissions = claims.get("permissions", List.class);
                List<SimpleGrantedAuthority> authorities = permissions == null
                        ? List.of()
                        : permissions.stream()
                                .map(SimpleGrantedAuthority::new)
                                .collect(Collectors.toList());

                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(
                                claims.getSubject(), null, authorities);
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (Exception e) {
                log.warn("JWT filter error on {}: {}", request.getRequestURI(), e.getMessage());
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * The first token that actually VALIDATES, trying every source the browser may send.
     *
     * It used to return the first token merely *present* — in practice the `Authorization`
     * header — and give up if it didn't verify. The portal signs the HMAC `rhToken` and the
     * RS256 `daf360_access` cookie with different keys, so a service whose `app.jwt-secret`
     * doesn't match the portal's rejected the Bearer token and never looked at the cookie
     * it could have verified: no principal, and every `@PreAuthorize` answered 403 while
     * rh-service — which has always looped over its candidates — accepted the same request.
     * That is exactly how a locally-run payroll-service (no `JWT_SECRET`, no
     * `JWT_PUBLIC_KEY_PATH`) turned a working session into "Access Denied" on every call.
     *
     * Same order and same semantics as `daf360-rh-service`'s filter — keep the two aligned.
     */
    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        String bearer = (header != null && header.startsWith("Bearer ")) ? header.substring(7) : null;

        String[][] candidates = {
            { bearer,                                   "Bearer header" },
            { cookieValue(request, "daf360_rh"),        "daf360_rh (HMAC cookie)" },
            { cookieValue(request, "daf360_access"),    "daf360_access (RSA cookie)" },
        };

        for (String[] candidate : candidates) {
            if (candidate[0] == null) continue;
            if (jwtService.isTokenValid(candidate[0])) {
                log.debug("JWT authenticated via {} for {}", candidate[1], request.getRequestURI());
                return candidate[0];
            }
            log.debug("JWT: {} invalid for {}", candidate[1], request.getRequestURI());
        }

        return null;
    }

    private String cookieValue(HttpServletRequest request, String name) {
        if (request.getCookies() == null) return null;
        for (Cookie c : request.getCookies()) {
            if (name.equals(c.getName())) return c.getValue();
        }
        return null;
    }
}
