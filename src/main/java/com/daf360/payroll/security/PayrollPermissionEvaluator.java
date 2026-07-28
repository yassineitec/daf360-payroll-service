package com.daf360.payroll.security;

import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.io.Serializable;

@Component
public class PayrollPermissionEvaluator implements PermissionEvaluator {

    @Override
    public boolean hasPermission(Authentication auth, Object targetDomainObject, Object permission) {
        return check(auth, permission);
    }

    @Override
    public boolean hasPermission(Authentication auth, Serializable targetId,
                                  String targetType, Object permission) {
        return check(auth, permission);
    }

    private boolean check(Authentication auth, Object permission) {
        if (auth == null || permission == null) return false;
        String perm = permission.toString();
        return auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals(perm));
    }
}
