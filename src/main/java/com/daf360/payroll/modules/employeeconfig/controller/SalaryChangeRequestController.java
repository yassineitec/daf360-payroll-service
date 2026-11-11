package com.daf360.payroll.modules.employeeconfig.controller;

import com.daf360.payroll.modules.employeeconfig.dto.SalaryChangeDtos.DecisionRequest;
import com.daf360.payroll.modules.employeeconfig.dto.SalaryChangeDtos.SalaryChangeDto;
import com.daf360.payroll.modules.employeeconfig.dto.SalaryChangeDtos.SubmitSalaryChangeRequest;
import com.daf360.payroll.modules.employeeconfig.service.SalaryChangeRequestService;
import com.daf360.payroll.modules.ref.service.UserContextService;
import com.daf360.payroll.security.PermissionCatalog;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Salary change requests (V38). Two audiences:
 *  - RH / payroll ({@code PAYROLL_MANAGE_EMPLOYEE_CONFIG}): submit, follow, withdraw;
 *  - the country director ({@code APPROVE_HIRING_COST}, the code already deciding candidate
 *    costs — finance's approval queue calls this service on its payroll API URL).
 */
@RestController
@RequestMapping("/api/payroll/salary-changes")
public class SalaryChangeRequestController {

    private static final String MANAGE  = "hasAuthority('" + PermissionCatalog.MANAGE_EMPLOYEE_CONFIG + "')";
    private static final String APPROVE = "hasAuthority('APPROVE_HIRING_COST')";
    private static final String READ    = "hasAnyAuthority('" + PermissionCatalog.VIEW_EMPLOYEE_CONFIG + "','"
            + PermissionCatalog.MANAGE_EMPLOYEE_CONFIG + "','APPROVE_HIRING_COST')";

    private final SalaryChangeRequestService service;
    private final UserContextService userContext;

    public SalaryChangeRequestController(SalaryChangeRequestService service, UserContextService userContext) {
        this.service = service;
        this.userContext = userContext;
    }

    @PostMapping("/employee/{profileUserId}")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(MANAGE)
    public SalaryChangeDto submit(@PathVariable Long profileUserId, @Valid @RequestBody SubmitSalaryChangeRequest req) {
        return service.submit(profileUserId, req, userContext.currentUserId());
    }

    @GetMapping("/employee/{profileUserId}")
    @PreAuthorize(READ)
    public List<SalaryChangeDto> forEmployee(@PathVariable Long profileUserId) {
        return service.listForEmployee(profileUserId);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize(MANAGE)
    public SalaryChangeDto cancel(@PathVariable Long id, @RequestBody(required = false) DecisionRequest req) {
        return service.cancel(id, req == null ? null : req.notes(), userContext.currentUserId());
    }

    @GetMapping("/pending")
    @PreAuthorize(APPROVE)
    public List<SalaryChangeDto> pending() {
        return service.listPending();
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize(APPROVE)
    public SalaryChangeDto approve(@PathVariable Long id, @RequestBody(required = false) DecisionRequest req) {
        return service.approve(id, req == null ? null : req.notes(), userContext.currentUserId());
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize(APPROVE)
    public SalaryChangeDto reject(@PathVariable Long id, @RequestBody DecisionRequest req) {
        return service.reject(id, req.notes(), userContext.currentUserId());
    }
}
