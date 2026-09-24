package com.daf360.payroll.modules.employeeconfig.controller;

import com.daf360.payroll.modules.employeeconfig.dto.CreateEmployeePayrollBonusRequest;
import com.daf360.payroll.modules.employeeconfig.dto.EmployeePayrollBonusDto;
import com.daf360.payroll.modules.employeeconfig.service.EmployeePayrollBonusService;
import com.daf360.payroll.modules.ref.service.UserContextService;
import com.daf360.payroll.security.PermissionCatalog;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/payroll/employee-configs/{profileUserId}/bonuses")
public class EmployeePayrollBonusController {

    private final EmployeePayrollBonusService service;
    private final UserContextService userContext;

    public EmployeePayrollBonusController(EmployeePayrollBonusService service, UserContextService userContext) {
        this.service = service;
        this.userContext = userContext;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('" + PermissionCatalog.VIEW_EMPLOYEE_CONFIG + "','" + PermissionCatalog.MANAGE_EMPLOYEE_CONFIG + "')")
    public List<EmployeePayrollBonusDto> list(@PathVariable Long profileUserId) {
        return service.list(profileUserId);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + PermissionCatalog.MANAGE_EMPLOYEE_CONFIG + "')")
    public EmployeePayrollBonusDto create(@PathVariable Long profileUserId,
                                          @Valid @RequestBody CreateEmployeePayrollBonusRequest req) {
        return service.create(profileUserId, req, userContext.currentUserId());
    }

    @DeleteMapping("/{bonusId}")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.MANAGE_EMPLOYEE_CONFIG + "')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long profileUserId, @PathVariable Long bonusId) {
        service.delete(profileUserId, bonusId);
    }
}
