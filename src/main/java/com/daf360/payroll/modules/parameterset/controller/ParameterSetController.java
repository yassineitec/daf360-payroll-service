package com.daf360.payroll.modules.parameterset.controller;

import com.daf360.payroll.modules.parameterset.dto.CreateParameterSetRequest;
import com.daf360.payroll.modules.parameterset.dto.ParameterSetDto;
import com.daf360.payroll.modules.parameterset.dto.SavePayrollRubriqueRequest;
import com.daf360.payroll.modules.parameterset.dto.SocialChargeRateDto;
import com.daf360.payroll.modules.parameterset.service.ParameterSetService;
import com.daf360.payroll.modules.ref.service.UserContextService;
import com.daf360.payroll.security.PermissionCatalog;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/payroll/parameter-sets")
public class ParameterSetController {

    private final ParameterSetService service;
    private final UserContextService userContext;

    public ParameterSetController(ParameterSetService service, UserContextService userContext) {
        this.service = service;
        this.userContext = userContext;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('" + PermissionCatalog.VIEW_PARAMSET + "','" + PermissionCatalog.APPROVE_PARAMSET + "')")
    public List<ParameterSetDto> list(@RequestParam Long paysId) {
        return service.listByPays(paysId);
    }

    @GetMapping("/active")
    @PreAuthorize("hasAnyAuthority('" + PermissionCatalog.VIEW_PARAMSET + "','" + PermissionCatalog.RUN_SIMULATION + "')")
    public ParameterSetDto getActive(@RequestParam Long paysId) {
        return service.getActiveByPays(paysId);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('" + PermissionCatalog.VIEW_PARAMSET + "','" + PermissionCatalog.APPROVE_PARAMSET + "')")
    public ParameterSetDto getById(@PathVariable Long id) {
        return service.getById(id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + PermissionCatalog.APPROVE_PARAMSET + "')")
    public ResponseEntity<ParameterSetDto> create(@RequestBody @Valid CreateParameterSetRequest req) {
        Long userId = userContext.currentUserId();
        ParameterSetDto created = service.create(req, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.APPROVE_PARAMSET + "')")
    public ParameterSetDto submit(@PathVariable Long id) {
        return service.submitForApproval(id);
    }

    @PostMapping("/{id}/approve/hr")
    @PreAuthorize("hasAnyAuthority('" + PermissionCatalog.APPROVE_PARAMSET + "','" + PermissionCatalog.APPROVE_PARAMSET_FAST_TRACK + "')")
    public ParameterSetDto approveHr(@PathVariable Long id) {
        Long userId = userContext.currentUserId();
        return service.approveHr(id, userId);
    }

    @PostMapping("/{id}/approve/finance")
    @PreAuthorize("hasAnyAuthority('" + PermissionCatalog.APPROVE_PARAMSET + "','" + PermissionCatalog.APPROVE_PARAMSET_FAST_TRACK + "')")
    public ParameterSetDto approveFinance(@PathVariable Long id) {
        Long userId = userContext.currentUserId();
        return service.approveFinance(id, userId);
    }

    @PutMapping("/{id}/social-charge-rates")
    @PreAuthorize("hasAnyAuthority('" + PermissionCatalog.APPROVE_PARAMSET + "','" + PermissionCatalog.APPROVE_PARAMSET_FAST_TRACK + "')")
    public ParameterSetDto updateSocialChargeRates(@PathVariable Long id,
            @RequestBody List<SocialChargeRateDto> rates) {
        return service.updateSocialChargeRates(id, rates);
    }

    @PutMapping("/{id}/rubriques")
    @PreAuthorize("hasAnyAuthority('" + PermissionCatalog.APPROVE_PARAMSET + "','" + PermissionCatalog.APPROVE_PARAMSET_FAST_TRACK + "')")
    public ParameterSetDto updateRubriques(@PathVariable Long id,
            @RequestBody List<SavePayrollRubriqueRequest> rubriques) {
        return service.updateRubriques(id, rubriques);
    }
}
