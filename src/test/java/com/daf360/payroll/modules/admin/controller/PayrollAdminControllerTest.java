package com.daf360.payroll.modules.admin.controller;

import com.daf360.payroll.modules.admin.service.PayrollAdminService;
import com.daf360.payroll.security.PermissionCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Arrays;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PayrollAdminControllerTest {

    @Mock PayrollAdminService service;

    private static UsernamePasswordAuthenticationToken caller(String... codes) {
        return new UsernamePasswordAuthenticationToken("1", null,
                Arrays.stream(codes).map(SimpleGrantedAuthority::new).toList());
    }

    @Test
    void audit_employeeConfigReader_getsOnlyConfigChanges() {
        new PayrollAdminController(service).audit(179L, 100, caller(PermissionCatalog.VIEW_EMPLOYEE_CONFIG));
        verify(service).audit(179L, 100, true, false);
    }

    @Test
    void audit_advancesManager_getsOnlyAdvanceChanges() {
        new PayrollAdminController(service).audit(179L, 100, caller(PermissionCatalog.MANAGE_SALARY_ADVANCES));
        verify(service).audit(179L, 100, false, true);
    }

    @Test
    void audit_superAdmin_getsBoth() {
        new PayrollAdminController(service).audit(179L, 100, caller(PermissionCatalog.SUPER_ADMIN));
        verify(service).audit(179L, 100, true, true);
    }
}
