package com.daf360.payroll.modules.payroll.controller;

import com.daf360.payroll.modules.calibration.entity.CalibrationImport;
import com.daf360.payroll.modules.calibration.entity.CalibrationImportLine;
import com.daf360.payroll.modules.calibration.entity.PrecisionKpiHistory;
import com.daf360.payroll.modules.calibration.service.CalibrationImportService;
import com.daf360.payroll.modules.calibration.service.KpiHistoryService;
import com.daf360.payroll.modules.calibration.service.ParameterSetWorkflowService;
import com.daf360.payroll.modules.payroll.dto.RunPayrollRequest;
import com.daf360.payroll.modules.payroll.dto.RunPayrollResponse;
import com.daf360.payroll.modules.payroll.entity.PayrollParamSet;
import com.daf360.payroll.modules.payroll.entity.PayrollResult;
import com.daf360.payroll.modules.payroll.entity.PayrollRubriqueDef;
import com.daf360.payroll.modules.payroll.orchestrator.PayrollOrchestrator;
import com.daf360.payroll.modules.payroll.repository.PayrollCountryRepository;
import com.daf360.payroll.modules.payroll.repository.PayrollResultRepository;
import com.daf360.payroll.modules.payroll.repository.PayrollRubriqueDefRepository;
import com.daf360.payroll.security.PermissionCatalog;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/payroll/engine")
public class PayrollEngineController {

    private final PayrollOrchestrator orchestrator;
    private final ParameterSetWorkflowService workflowService;
    private final CalibrationImportService calibrationService;
    private final KpiHistoryService kpiService;
    private final PayrollCountryRepository countryRepo;
    private final PayrollRubriqueDefRepository rubriqueRepo;
    private final PayrollResultRepository resultRepo;

    public PayrollEngineController(PayrollOrchestrator orchestrator,
                                    ParameterSetWorkflowService workflowService,
                                    CalibrationImportService calibrationService,
                                    KpiHistoryService kpiService,
                                    PayrollCountryRepository countryRepo,
                                    PayrollRubriqueDefRepository rubriqueRepo,
                                    PayrollResultRepository resultRepo) {
        this.orchestrator = orchestrator;
        this.workflowService = workflowService;
        this.calibrationService = calibrationService;
        this.kpiService = kpiService;
        this.countryRepo = countryRepo;
        this.rubriqueRepo = rubriqueRepo;
        this.resultRepo = resultRepo;
    }

    // ── Engine run ────────────────────────────────────────────────────────────

    @PostMapping("/run")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.RUN_ENGINE + "')")
    public ResponseEntity<RunPayrollResponse> run(
            @RequestBody @Valid RunPayrollRequest request) {
        return ResponseEntity.status(201).body(orchestrator.run(request));
    }

    @GetMapping("/results/{employeeId}")
    @PreAuthorize("hasAnyAuthority('"
        + PermissionCatalog.VIEW_RESULTS + "','"
        + PermissionCatalog.RUN_ENGINE + "')")
    public List<PayrollResult> getResults(@PathVariable Long employeeId) {
        return resultRepo.findByEmployeeIdOrderByPeriodYearDescPeriodMonthDesc(employeeId);
    }

    // ── Rubrique definitions ──────────────────────────────────────────────────

    @GetMapping("/rubriques")
    @PreAuthorize("hasAnyAuthority('"
        + PermissionCatalog.MANAGE_RUBRIQUES + "','"
        + PermissionCatalog.VIEW_PARAMSET + "')")
    public List<PayrollRubriqueDef> listRubriques(@RequestParam Long paysId) {
        return countryRepo.findByPaysIdAndActiveTrue(paysId)
            .map(c -> rubriqueRepo.findByCountryIdAndActiveTrue(c.getId()))
            .orElse(List.of());
    }

    // ── Parameter set workflow ────────────────────────────────────────────────

    @PostMapping("/param-sets/{id}/submit")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.APPROVE_PARAMSET + "')")
    public PayrollParamSet submit(
            @PathVariable Long id,
            @RequestParam(required = false) String submittedBy) {
        return workflowService.submit(id, submittedBy);
    }

    @PostMapping("/param-sets/{id}/approve-hr")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.APPROVE_PARAMSET + "')")
    public PayrollParamSet approveHr(
            @PathVariable Long id,
            @RequestParam(required = false) String approvedBy) {
        return workflowService.approveHr(id, approvedBy);
    }

    @PostMapping("/param-sets/{id}/approve-finance")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.APPROVE_PARAMSET_FAST_TRACK + "')")
    public PayrollParamSet approveFinance(
            @PathVariable Long id,
            @RequestParam(required = false) String approvedBy) {
        return workflowService.approveFinance(id, approvedBy);
    }

    @PostMapping("/param-sets/{id}/activate")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.APPROVE_PARAMSET_FAST_TRACK + "')")
    public PayrollParamSet activate(
            @PathVariable Long id,
            @RequestParam(required = false) String activatedBy) {
        return workflowService.activate(id, activatedBy);
    }

    @GetMapping("/param-sets")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.VIEW_PARAMSET + "')")
    public List<PayrollParamSet> listParamSets(@RequestParam Long paysId) {
        return countryRepo.findByPaysIdAndActiveTrue(paysId)
            .map(c -> workflowService.list(c.getId()))
            .orElse(List.of());
    }

    // ── Calibration ───────────────────────────────────────────────────────────

    @PostMapping("/calibration/open")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.IMPORT_CALIBRATION + "')")
    public CalibrationImport openCalibration(
            @RequestParam Long paysId,
            @RequestParam String period,
            @RequestParam(required = false) Long paramSetId) {
        return calibrationService.openImport(paysId, period, paramSetId);
    }

    @GetMapping("/calibration")
    @PreAuthorize("hasAnyAuthority('"
        + PermissionCatalog.IMPORT_CALIBRATION + "','"
        + PermissionCatalog.VIEW_PARAMSET + "')")
    public List<CalibrationImport> listCalibrations(@RequestParam Long paysId) {
        return calibrationService.listByCountry(paysId);
    }

    @GetMapping("/calibration/{importId}/lines")
    @PreAuthorize("hasAnyAuthority('"
        + PermissionCatalog.IMPORT_CALIBRATION + "','"
        + PermissionCatalog.VIEW_PARAMSET + "')")
    public List<CalibrationImportLine> getLines(@PathVariable Long importId) {
        return calibrationService.getLines(importId);
    }

    @GetMapping("/calibration/kpi")
    @PreAuthorize("hasAnyAuthority('"
        + PermissionCatalog.IMPORT_CALIBRATION + "','"
        + PermissionCatalog.VIEW_PARAMSET + "')")
    public List<PrecisionKpiHistory> kpiHistory(@RequestParam Long paysId) {
        return kpiService.history(paysId);
    }
}
