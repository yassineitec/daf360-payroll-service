package com.daf360.payroll.modules.calibration.controller;

import com.daf360.payroll.modules.calibration.dto.CalibrationCycleDto;
import com.daf360.payroll.modules.calibration.dto.PartnerPayrollRow;
import com.daf360.payroll.modules.calibration.service.CalibrationCycleService;
import com.daf360.payroll.modules.calibration.service.PartnerPayrollParserService;
import com.daf360.payroll.modules.ref.service.UserContextService;
import com.daf360.payroll.security.PermissionCatalog;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/payroll/calibration")
public class CalibrationController {

    private final CalibrationCycleService cycleService;
    private final PartnerPayrollParserService parserService;
    private final UserContextService userContext;

    public CalibrationController(CalibrationCycleService cycleService,
                                  PartnerPayrollParserService parserService,
                                  UserContextService userContext) {
        this.cycleService = cycleService;
        this.parserService = parserService;
        this.userContext = userContext;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('" + PermissionCatalog.RUN_CALIBRATION + "','" + PermissionCatalog.VIEW_AGGREGATE + "')")
    public List<CalibrationCycleDto> list(@RequestParam Long paysId) {
        return cycleService.listByPays(paysId);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('" + PermissionCatalog.RUN_CALIBRATION + "','" + PermissionCatalog.VIEW_AGGREGATE + "')")
    public CalibrationCycleDto getById(@PathVariable Long id) {
        return cycleService.getById(id);
    }

    @PostMapping("/open")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.RUN_CALIBRATION + "')")
    public ResponseEntity<CalibrationCycleDto> open(
            @RequestParam Long paysId,
            @RequestParam String period) {
        Long userId = userContext.currentUserId();
        CalibrationCycleDto cycle = cycleService.openCycle(paysId, period, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(cycle);
    }

    @PostMapping("/{id}/upload-actuals")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.UPLOAD_ACTUAL + "')")
    public CalibrationCycleDto uploadActuals(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file) {
        Long userId = userContext.currentUserId();
        List<PartnerPayrollRow> rows = parserService.parse(file);
        return cycleService.uploadActuals(id, rows, userId);
    }
}
