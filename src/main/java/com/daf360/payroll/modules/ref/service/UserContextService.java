package com.daf360.payroll.modules.ref.service;

import com.daf360.payroll.modules.ref.entity.UsersRef;
import com.daf360.payroll.modules.ref.repository.UsersRefRepository;
import com.daf360.payroll.security.PaysScopeContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;
import java.util.Set;

@Service
public class UserContextService {

    private final UsersRefRepository usersRefRepository;

    public UserContextService(UsersRefRepository usersRefRepository) {
        this.usersRefRepository = usersRefRepository;
    }

    public Optional<UsersRef> currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return Optional.empty();

        String email = extractEmail(auth);
        if (email == null) return Optional.empty();

        return usersRefRepository.findByEmail(email);
    }

    /**
     * The countries the current caller may see, honouring the role's country scope (V74).
     *
     *   1. The JWT's scope claims when present — where a LIST-mode role spanning several
     *      countries comes from. Set by JwtAuthFilter into PaysScopeContext.
     *   2. Otherwise UsersRef.pays_id, i.e. the pre-V74 single-country rule, so a session
     *      whose token predates the claims keeps working instead of being denied.
     *   3. Null when neither is known, which leaves the interceptor permissive as before.
     *
     * The scope comes from the ROLE, not from the user's own country: a Tunisian holder of a
     * LIST role covering TN + EG legitimately sees both.
     */
    public PaysScopeContext.PaysScope getCurrentUserPaysScope() {
        PaysScopeContext.PaysScope fromToken = PaysScopeContext.get();
        if (fromToken != null) return fromToken;

        Long own = currentUser().map(UsersRef::getPaysId).orElse(null);
        return (own == null || own <= 0L) ? null : PaysScopeContext.PaysScope.of(Set.of(own));
    }

    public Long currentUserId() {
        Optional<UsersRef> user = currentUser();
        if (user.isPresent()) return user.get().getId();
        // jwt-disabled dev mode: JwtAuthFilter sets principal to the userId string
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null) {
            Object principal = auth.getPrincipal();
            if (principal instanceof String s) {
                try { return Long.parseLong(s); } catch (NumberFormatException ignored) {}
            }
        }
        return null;
    }

    private String extractEmail(Authentication auth) {
        Object principal = auth.getPrincipal();
        if (principal instanceof UserDetails ud) return ud.getUsername();
        if (principal instanceof String s) return s;
        return null;
    }
}
