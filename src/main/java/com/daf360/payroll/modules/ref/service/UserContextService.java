package com.daf360.payroll.modules.ref.service;

import com.daf360.payroll.modules.ref.entity.UsersRef;
import com.daf360.payroll.modules.ref.repository.UsersRefRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;

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
