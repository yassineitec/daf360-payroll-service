package com.daf360.payroll.modules.simulation.controller;

import com.daf360.payroll.modules.ref.service.UserContextService;
import com.daf360.payroll.modules.simulation.dto.CohortAggregateResponse;
import com.daf360.payroll.modules.simulation.dto.CohortFilterRequest;
import com.daf360.payroll.modules.simulation.dto.CohortSimulationRequest;
import com.daf360.payroll.modules.simulation.dto.SimulationPageDto;
import com.daf360.payroll.modules.simulation.dto.SimulationRequest;
import com.daf360.payroll.modules.simulation.dto.SimulationResultDto;
import com.daf360.payroll.modules.simulation.service.CohortAggregateService;
import com.daf360.payroll.modules.simulation.service.CohortSimulationService;
import com.daf360.payroll.modules.simulation.service.IndividualSimulationService;
import com.daf360.payroll.modules.simulation.service.SimulationExcelExportService;
import com.daf360.payroll.modules.simulation.service.SimulationHistoryService;
import com.daf360.payroll.modules.simulation.service.SimulationPdfService;
import com.daf360.payroll.modules.simulation.dto.EmployeeWhatIfRequest;
import com.daf360.payroll.modules.simulation.dto.EmployeeWhatIfResponse;
import com.daf360.payroll.security.PaysScopeGuard;
import com.daf360.payroll.security.PermissionCatalog;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.stream.Collectors;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/payroll/simulations")
public class SimulationController {

    private final IndividualSimulationService individualService;
    private final CohortSimulationService cohortService;
    private final CohortAggregateService cohortAggregateService;
    private final SimulationPdfService pdfService;
    private final SimulationExcelExportService excelService;
    private final SimulationHistoryService historyService;
    private final UserContextService userContext;

    public SimulationController(IndividualSimulationService individualService,
                                 CohortSimulationService cohortService,
                                 CohortAggregateService cohortAggregateService,
                                 SimulationPdfService pdfService,
                                 SimulationExcelExportService excelService,
                                 SimulationHistoryService historyService,
                                 UserContextService userContext) {
        this.individualService = individualService;
        this.cohortService = cohortService;
        this.cohortAggregateService = cohortAggregateService;
        this.pdfService = pdfService;
        this.excelService = excelService;
        this.historyService = historyService;
        this.userContext = userContext;
    }

    /**
     * Also open to the DAF deciding hiring costs (APPROVE_HIRING_COST): a counter-proposal is
     * costed in its decision modal before it goes to the candidate, and a country director need
     * not hold the payroll simulator permission for that.
     */
    @PostMapping("/individual")
    @PreAuthorize("hasAnyAuthority('" + PermissionCatalog.RUN_SIMULATION + "','APPROVE_HIRING_COST')")
    public ResponseEntity<SimulationResultDto> runIndividual(@RequestBody @Valid SimulationRequest req) {
        Long userId = userContext.currentUserId();
        SimulationResultDto result = individualService.simulate(req, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    /**
     * Liste `/payroll/simulator` : simulations individuelles d'un pays, paginées, filtrées et
     * triées côté serveur, avec les chiffres du bandeau KPI. `sort` = `colonne,asc|desc`.
     */
    @GetMapping("/individual")
    @PreAuthorize("hasAnyAuthority('" + PermissionCatalog.VIEW_INDIVIDUAL + "','" + PermissionCatalog.VIEW_AGGREGATE + "')")
    public SimulationPageDto listIndividual(@RequestParam Long paysId,
                                            @RequestParam(required = false) String contractType,
                                            @RequestParam(required = false) String mode,
                                            @RequestParam(required = false) String subject,
                                            @RequestParam(required = false) String search,
                                            @RequestParam(defaultValue = "false") boolean partialOnly,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size,
                                            @RequestParam(required = false) String sort) {
        return historyService.list(paysId, contractType, mode, subject, search, partialOnly, page, size, sort);
    }

    /** Fiche `/payroll/simulator/:id` — rechargeable et partageable par son URL. */
    @GetMapping("/{id:\\d+}")
    @PreAuthorize("hasAnyAuthority('" + PermissionCatalog.VIEW_INDIVIDUAL + "','" + PermissionCatalog.RUN_SIMULATION + "')")
    public SimulationResultDto getById(@PathVariable Long id) {
        return historyService.getById(id);
    }

    @GetMapping("/individual/history")
    @PreAuthorize("hasAnyAuthority('" + PermissionCatalog.VIEW_INDIVIDUAL + "','" + PermissionCatalog.VIEW_AGGREGATE + "')")
    public List<SimulationResultDto> history(@RequestParam Long paysId) {
        // A2: cohort rows are real employees' figures — individual view only.
        boolean individual = PaysScopeGuard.hasAuthority(PermissionCatalog.VIEW_INDIVIDUAL)
                || PaysScopeGuard.isSuperAdmin();
        return individualService.history(paysId).stream()
                .filter(r -> individual || !"COHORT".equals(r.simulationType()))
                .toList();
    }

    /** Returns per-employee figures (gross, net, IRPP, loaded cost, profileUserId): running it
     *  now also requires the individual view (A2) — RUN_SIMULATION alone no longer suffices. */
    @PostMapping("/cohort")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.RUN_SIMULATION + "') and hasAnyAuthority('"
            + PermissionCatalog.VIEW_INDIVIDUAL + "','" + PermissionCatalog.SUPER_ADMIN + "')")
    public ResponseEntity<List<SimulationResultDto>> runCohort(@RequestBody @Valid CohortSimulationRequest req) {
        Long userId = userContext.currentUserId();
        List<SimulationResultDto> results = cohortService.simulate(req, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(results);
    }

    /** Per-employee rows of a stored cohort: individual view only (A2, VIEW_AGGREGATE refused),
     *  and the cohort's own country must be in scope (A1, checked in the service). */
    @GetMapping("/cohort/{cohortId}")
    @PreAuthorize("hasAnyAuthority('" + PermissionCatalog.VIEW_INDIVIDUAL + "','" + PermissionCatalog.SUPER_ADMIN + "')")
    public List<SimulationResultDto> getCohortResults(@PathVariable Long cohortId) {
        return cohortService.getByCohort(cohortId);
    }

    /**
     * Cohort aggregate / salary what-if for a group (A2, B2). Open to whoever may run or read
     * simulations; what comes back depends on the caller: PAYROLL_VIEW_INDIVIDUAL holders get
     * exact figures for any group size and may ask for the per-employee rows
     * ({@code includeEmployees}); everyone else gets totals only, and counts only below the
     * minimum group size.
     */
    @PostMapping("/cohort/aggregate")
    @PreAuthorize("hasAnyAuthority('" + PermissionCatalog.RUN_SIMULATION + "','" + PermissionCatalog.VIEW_AGGREGATE
            + "','" + PermissionCatalog.VIEW_INDIVIDUAL + "','" + PermissionCatalog.SUPER_ADMIN + "')")
    public ResponseEntity<CohortAggregateResponse> runCohortAggregate(@RequestBody @Valid CohortFilterRequest req) {
        return ResponseEntity.ok(cohortAggregateService.aggregate(req, access()));
    }

    /** Salary what-if for ONE employee of the country (B2, DEC12): individual view only. */
    @PostMapping("/cohort/what-if/employee")
    @PreAuthorize("hasAnyAuthority('" + PermissionCatalog.VIEW_INDIVIDUAL + "','" + PermissionCatalog.SUPER_ADMIN + "')")
    public ResponseEntity<EmployeeWhatIfResponse> runEmployeeWhatIf(@RequestBody @Valid EmployeeWhatIfRequest req) {
        return ResponseEntity.ok(cohortAggregateService.whatIfEmployee(req, access()));
    }

    /** The caller as the cohort sees it: individual view or aggregate only, plus who for the
     *  access log (DEC13). Permission codes are rights, not personal data. */
    private CohortAggregateService.CohortAccess access() {
        boolean individual = PaysScopeGuard.hasAuthority(PermissionCatalog.VIEW_INDIVIDUAL)
                || PaysScopeGuard.isSuperAdmin();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String perms = auth == null ? "" : auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.equals(PermissionCatalog.VIEW_INDIVIDUAL) || a.equals(PermissionCatalog.VIEW_AGGREGATE)
                        || a.equals(PermissionCatalog.RUN_SIMULATION) || a.equals(PermissionCatalog.SUPER_ADMIN))
                .sorted().collect(Collectors.joining("+"));
        return new CohortAggregateService.CohortAccess(individual, userContext.currentUserId(), perms);
    }

    @GetMapping("/{id}/export-pdf")
    @PreAuthorize("hasAnyAuthority('" + PermissionCatalog.VIEW_INDIVIDUAL + "','" + PermissionCatalog.RUN_SIMULATION + "')")
    public ResponseEntity<byte[]> exportPdf(@PathVariable Long id) {
        historyService.findInScope(id);
        byte[] bytes = pdfService.export(id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"simulation-" + id + ".pdf\"")
                .body(bytes);
    }

    /** Fiche de simulation en Excel (Apache POI, sans pdf-service) — même contrat que les
     *  exports de la facturation : libellés selon `?lang=`. */
    @GetMapping("/{id}/export.xlsx")
    @PreAuthorize("hasAnyAuthority('" + PermissionCatalog.VIEW_INDIVIDUAL + "','" + PermissionCatalog.RUN_SIMULATION + "')")
    public ResponseEntity<byte[]> exportXlsx(@PathVariable Long id,
                                             @RequestParam(defaultValue = "fr") String lang) {
        historyService.findInScope(id);
        SimulationExcelExportService.ExcelExport export = excelService.export(id, lang);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + export.filename() + "\"")
                .body(export.bytes());
    }
}
