package com.daf360.payroll.modules.admin.controller;

import com.daf360.payroll.modules.admin.dto.AuditEntryDto;
import com.daf360.payroll.modules.admin.dto.PayrollCountryDto;
import com.daf360.payroll.modules.admin.dto.SavePayrollCountryRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import com.daf360.payroll.modules.admin.service.PayrollAdminService;
import com.daf360.payroll.security.PermissionCatalog;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Read-only endpoints behind the payroll administration home (/payroll/admin):
 * « Pays de paie » and « Journal des modifications ».
 */
@RestController
@RequestMapping("/api/payroll/admin")
@RequiredArgsConstructor
public class PayrollAdminController {

    private final PayrollAdminService service;

    /** The engine's per-country set-up, in the caller's country scope. */
    @GetMapping("/countries")
    @PreAuthorize("hasAnyAuthority('"
        + PermissionCatalog.VIEW_PARAMSET + "','"
        + PermissionCatalog.MANAGE_COUNTRIES + "','"
        + PermissionCatalog.SUPER_ADMIN + "')")
    public List<PayrollCountryDto> countries() {
        return service.listCountries();
    }

    /** Configure un nouveau pays dans le moteur de paie. */
    @PostMapping("/countries")
    @PreAuthorize("hasAnyAuthority('" + PermissionCatalog.MANAGE_COUNTRIES + "','" + PermissionCatalog.SUPER_ADMIN + "')")
    public ResponseEntity<PayrollCountryDto> createCountry(@RequestBody SavePayrollCountryRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createCountry(req));
    }

    /** Modifie la configuration d'un pays de paie (devise, exercice, sources de change, état). */
    @PutMapping("/countries/{id}")
    @PreAuthorize("hasAnyAuthority('" + PermissionCatalog.MANAGE_COUNTRIES + "','" + PermissionCatalog.SUPER_ADMIN + "')")
    public PayrollCountryDto updateCountry(@PathVariable Long id, @RequestBody SavePayrollCountryRequest req) {
        return service.updateCountry(id, req);
    }

    /**
     * One country's change log. Each history is only read by those who may already read it
     * elsewhere: employee configuration changes need an employee-config code, advance status
     * changes need the payroll advances code — a caller holding one sees only that source.
     * {@code paysId} is mandatory, so {@code PaysIsolationInterceptor} applies the country scope.
     */
    @GetMapping("/audit")
    @PreAuthorize("hasAnyAuthority('"
        + PermissionCatalog.VIEW_EMPLOYEE_CONFIG + "','"
        + PermissionCatalog.MANAGE_EMPLOYEE_CONFIG + "','"
        + PermissionCatalog.MANAGE_SALARY_ADVANCES + "','"
        + PermissionCatalog.SUPER_ADMIN + "')")
    public List<AuditEntryDto> audit(@RequestParam Long paysId,
                                     @RequestParam(defaultValue = "200") int limit,
                                     Authentication auth) {
        Set<String> codes = auth == null ? Set.of()
                : auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
        boolean superAdmin = codes.contains(PermissionCatalog.SUPER_ADMIN);
        boolean withConfig = superAdmin
                || codes.contains(PermissionCatalog.VIEW_EMPLOYEE_CONFIG)
                || codes.contains(PermissionCatalog.MANAGE_EMPLOYEE_CONFIG);
        boolean withAdvances = superAdmin || codes.contains(PermissionCatalog.MANAGE_SALARY_ADVANCES);
        return service.audit(paysId, limit, withConfig, withAdvances);
    }
}
