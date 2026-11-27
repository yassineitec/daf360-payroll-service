package com.daf360.payroll.modules.payroll.leavesettings;

import com.daf360.payroll.modules.ref.service.UserContextService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Paie › Administration › « Absences et période de paie » (V42): per country, the pay period
 * and which RH leave types are withheld from the salary.
 */
@RestController
@RequestMapping("/api/payroll/admin/leave-settings")
public class PayrollLeaveSettingsController {

    private static final String READ  = "hasAnyAuthority('PAYROLL_VIEW_PARAMSET','PAYROLL_MANAGE_RUBRIQUES',"
            + "'PAYROLL_APPROVE_PARAMSET','PAYROLL_SUPER_ADMIN')";
    private static final String WRITE = "hasAnyAuthority('PAYROLL_MANAGE_RUBRIQUES','PAYROLL_APPROVE_PARAMSET',"
            + "'PAYROLL_SUPER_ADMIN')";

    private final PayrollLeaveSettingsService service;
    private final UserContextService userContext;

    public PayrollLeaveSettingsController(PayrollLeaveSettingsService service, UserContextService userContext) {
        this.service = service;
        this.userContext = userContext;
    }

    @GetMapping("/{paysId}")
    @PreAuthorize(READ)
    public PayrollLeaveSettingsService.SettingsView view(@PathVariable Long paysId) {
        return service.view(paysId);
    }

    @PutMapping("/{paysId}/period")
    @PreAuthorize(WRITE)
    public PayrollLeaveSettingsService.SettingsView savePeriod(@PathVariable Long paysId, @RequestBody PeriodRequest body) {
        return service.savePeriod(paysId, body.periodStartDay(), body.periodEndDay(), userContext.currentUserId());
    }

    @PutMapping("/{paysId}/leave-types/{code}")
    @PreAuthorize(WRITE)
    public PayrollLeaveSettingsService.SettingsView saveRule(@PathVariable Long paysId, @PathVariable String code,
                                                             @RequestBody RuleRequest body) {
        return service.saveRule(paysId, code, Boolean.TRUE.equals(body.deductsSalary()), userContext.currentUserId());
    }

    public record PeriodRequest(Integer periodStartDay, Integer periodEndDay) {}

    public record RuleRequest(Boolean deductsSalary) {}
}
