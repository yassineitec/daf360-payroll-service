package com.daf360.payroll.modules.simulation.controller;

import com.daf360.payroll.modules.ref.service.UserContextService;
import com.daf360.payroll.modules.simulation.dto.CohortSimulationRequest;
import com.daf360.payroll.modules.simulation.dto.SimulationRequest;
import com.daf360.payroll.modules.simulation.dto.SimulationResultDto;
import com.daf360.payroll.modules.simulation.service.CohortSimulationService;
import com.daf360.payroll.modules.simulation.service.IndividualSimulationService;
import com.daf360.payroll.security.PermissionCatalog;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/payroll/simulations")
public class SimulationController {

    private final IndividualSimulationService individualService;
    private final CohortSimulationService cohortService;
    private final UserContextService userContext;

    public SimulationController(IndividualSimulationService individualService,
                                 CohortSimulationService cohortService,
                                 UserContextService userContext) {
        this.individualService = individualService;
        this.cohortService = cohortService;
        this.userContext = userContext;
    }

    @PostMapping("/individual")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.RUN_SIMULATION + "')")
    public ResponseEntity<SimulationResultDto> runIndividual(@RequestBody @Valid SimulationRequest req) {
        Long userId = userContext.currentUserId();
        SimulationResultDto result = individualService.simulate(req, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @GetMapping("/individual/history")
    @PreAuthorize("hasAnyAuthority('" + PermissionCatalog.VIEW_INDIVIDUAL + "','" + PermissionCatalog.VIEW_AGGREGATE + "')")
    public List<SimulationResultDto> history(@RequestParam Long paysId) {
        return individualService.history(paysId);
    }

    @PostMapping("/cohort")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.RUN_SIMULATION + "')")
    public ResponseEntity<List<SimulationResultDto>> runCohort(@RequestBody @Valid CohortSimulationRequest req) {
        Long userId = userContext.currentUserId();
        List<SimulationResultDto> results = cohortService.simulate(req, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(results);
    }

    @GetMapping("/cohort/{cohortId}")
    @PreAuthorize("hasAnyAuthority('" + PermissionCatalog.VIEW_INDIVIDUAL + "','" + PermissionCatalog.VIEW_AGGREGATE + "')")
    public List<SimulationResultDto> getCohortResults(@PathVariable Long cohortId) {
        return cohortService.getByCohort(cohortId);
    }
}
