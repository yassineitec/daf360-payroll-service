package com.daf360.payroll.modules.payroll.leavesettings;

import com.daf360.payroll.modules.ref.service.UserContextService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

/**
 * Weekend-overtime settings (V43):
 * <ul>
 *   <li>per country — majoration per weekend day and daily cap per RH role, in Paie ›
 *       Administration › « Absences, HS et période de paie »;</li>
 *   <li>per employee — their own daily cap, in « Salaires des collaborateurs ».</li>
 * </ul>
 * A null value in a PUT removes the setting (back to the next level).
 */
@RestController
@RequestMapping("/api/payroll")
public class OvertimeSettingsController {

    private static final String ADMIN_READ  = "hasAnyAuthority('PAYROLL_VIEW_PARAMSET','PAYROLL_MANAGE_RUBRIQUES',"
            + "'PAYROLL_APPROVE_PARAMSET','PAYROLL_SUPER_ADMIN')";
    private static final String ADMIN_WRITE = "hasAnyAuthority('PAYROLL_MANAGE_RUBRIQUES','PAYROLL_APPROVE_PARAMSET',"
            + "'PAYROLL_SUPER_ADMIN')";
    private static final String EMPLOYEE_READ  = "hasAnyAuthority('PAYROLL_VIEW_EMPLOYEE_CONFIG','PAYROLL_MANAGE_EMPLOYEE_CONFIG',"
            + "'PAYROLL_SUPER_ADMIN')";
    private static final String EMPLOYEE_WRITE = "hasAnyAuthority('PAYROLL_MANAGE_EMPLOYEE_CONFIG','PAYROLL_SUPER_ADMIN')";

    private final OvertimeSettingsService service;
    private final UserContextService userContext;

    public OvertimeSettingsController(OvertimeSettingsService service, UserContextService userContext) {
        this.service = service;
        this.userContext = userContext;
    }

    @GetMapping("/admin/overtime-settings/{paysId}")
    @PreAuthorize(ADMIN_READ)
    public OvertimeSettingsService.AdminView view(@PathVariable Long paysId) {
        return service.adminView(paysId);
    }

    @PutMapping("/admin/overtime-settings/{paysId}/days/{day}")
    @PreAuthorize(ADMIN_WRITE)
    public OvertimeSettingsService.AdminView saveDay(@PathVariable Long paysId, @PathVariable String day,
                                                     @RequestBody DayRequest body) {
        return service.saveDayRate(paysId, day, body.majorationPct(), userContext.currentUserId());
    }

    @PutMapping("/admin/overtime-settings/{paysId}/roles/{roleId}")
    @PreAuthorize(ADMIN_WRITE)
    public OvertimeSettingsService.AdminView saveRole(@PathVariable Long paysId, @PathVariable Long roleId,
                                                      @RequestBody CapRequest body) {
        return service.saveRoleCap(paysId, roleId, body.capHours(), userContext.currentUserId());
    }

    @GetMapping("/employee-configs/{userId}/overtime-cap")
    @PreAuthorize(EMPLOYEE_READ)
    public OvertimeSettingsService.EmployeeView employee(@PathVariable Long userId) {
        return service.employeeView(userId);
    }

    @PutMapping("/employee-configs/{userId}/overtime-cap")
    @PreAuthorize(EMPLOYEE_WRITE)
    public OvertimeSettingsService.EmployeeView saveEmployee(@PathVariable Long userId, @RequestBody CapRequest body) {
        return service.saveEmployeeCap(userId, body.capHours(), userContext.currentUserId());
    }

    /** null = back to the default (TAUX_MAJORATION_HS_WEEKEND). */
    public record DayRequest(BigDecimal majorationPct) {}

    /** null = back to the next level (role → country → 8 h). */
    public record CapRequest(BigDecimal capHours) {}
}
