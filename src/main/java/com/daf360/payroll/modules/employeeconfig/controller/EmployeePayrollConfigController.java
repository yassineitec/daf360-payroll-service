package com.daf360.payroll.modules.employeeconfig.controller;

import com.daf360.payroll.modules.employeeconfig.dto.CalculateGrossRequest;
import com.daf360.payroll.modules.employeeconfig.dto.CalculateGrossResponse;
import com.daf360.payroll.modules.employeeconfig.dto.CalculateNetRequest;
import com.daf360.payroll.modules.employeeconfig.dto.CalculateNetResponse;
import com.daf360.payroll.modules.employeeconfig.dto.EmployeePayrollConfigDto;
import com.daf360.payroll.modules.employeeconfig.dto.EmployeePayrollConfigHistoryDto;
import com.daf360.payroll.modules.employeeconfig.dto.UpsertEmployeePayrollConfigRequest;
import com.daf360.payroll.modules.employeeconfig.dto.RemunerationSimulationDto;
import com.daf360.payroll.modules.employeeconfig.dto.RemunerationSimulationRequest;
import com.daf360.payroll.modules.employeeconfig.service.EmployeePayrollConfigService;
import com.daf360.payroll.modules.employeeconfig.service.RemunerationSimulationService;
import com.daf360.payroll.modules.ref.service.UserContextService;
import com.daf360.payroll.security.PermissionCatalog;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/payroll/employee-configs")
public class EmployeePayrollConfigController {

    private final EmployeePayrollConfigService service;
    private final UserContextService userContext;
    private final RemunerationSimulationService remunerationSimulation;

    public EmployeePayrollConfigController(EmployeePayrollConfigService service, UserContextService userContext,
                                           RemunerationSimulationService remunerationSimulation) {
        this.service = service;
        this.userContext = userContext;
        this.remunerationSimulation = remunerationSimulation;
    }

    /** userId → contrat de la config paie enregistrée (liste /payroll/employee-config).
     *  Déclaré avant `/{profileUserId}` pour la lisibilité ; le segment littéral l'emporte. */
    @GetMapping("/contract-types")
    @PreAuthorize("hasAnyAuthority('" + PermissionCatalog.VIEW_EMPLOYEE_CONFIG + "','" + PermissionCatalog.MANAGE_EMPLOYEE_CONFIG + "')")
    public java.util.Map<Long, String> contractTypes(@RequestParam List<Long> userIds) {
        return service.getSavedContractTypes(userIds);
    }

    @GetMapping("/{profileUserId}")
    @PreAuthorize("hasAnyAuthority('" + PermissionCatalog.VIEW_EMPLOYEE_CONFIG + "','" + PermissionCatalog.MANAGE_EMPLOYEE_CONFIG + "')")
    public EmployeePayrollConfigDto get(@PathVariable Long profileUserId) {
        return service.getOrDefault(profileUserId);
    }

    @GetMapping("/{profileUserId}/history")
    @PreAuthorize("hasAnyAuthority('" + PermissionCatalog.VIEW_EMPLOYEE_CONFIG + "','" + PermissionCatalog.MANAGE_EMPLOYEE_CONFIG + "')")
    public List<EmployeePayrollConfigHistoryDto> history(@PathVariable Long profileUserId) {
        return service.getHistory(profileUserId);
    }

    @PutMapping("/{profileUserId}")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.MANAGE_EMPLOYEE_CONFIG + "')")
    public EmployeePayrollConfigDto upsert(@PathVariable Long profileUserId,
                                            @Valid @RequestBody UpsertEmployeePayrollConfigRequest req) {
        return service.upsert(profileUserId, req, userContext.currentUserId());
    }

    /** « Nouvelle rémunération » form: the simulation engine's breakdown (benefits, the
     *  month's bonuses) for the salary being typed — nothing saved. */
    @PostMapping("/{profileUserId}/simulate")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.MANAGE_EMPLOYEE_CONFIG + "')")
    public RemunerationSimulationDto simulate(@PathVariable Long profileUserId,
                                              @Valid @RequestBody RemunerationSimulationRequest req) {
        return remunerationSimulation.simulate(profileUserId, req);
    }

    @PostMapping("/{profileUserId}/calculate-net")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.MANAGE_EMPLOYEE_CONFIG + "')")
    public CalculateNetResponse calculateNet(@PathVariable Long profileUserId,
                                              @Valid @RequestBody CalculateNetRequest req) {
        return service.calculateNet(profileUserId, req);
    }

    /** Calcul inverse : net saisi → brut (champ « Salaire net » de la configuration de paie). */
    @PostMapping("/{profileUserId}/calculate-gross")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.MANAGE_EMPLOYEE_CONFIG + "')")
    public CalculateGrossResponse calculateGross(@PathVariable Long profileUserId,
                                                  @Valid @RequestBody CalculateGrossRequest req) {
        return service.calculateGross(profileUserId, req.netSalary(), req.inputCurrency(), req.form());
    }
}
