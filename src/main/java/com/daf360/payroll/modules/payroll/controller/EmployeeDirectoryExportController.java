package com.daf360.payroll.modules.payroll.controller;

import com.daf360.payroll.common.export.ExportLang;
import com.daf360.payroll.modules.payroll.service.EmployeeDirectoryExportService;
import com.daf360.payroll.security.PermissionCatalog;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Export Excel de la liste des collaborateurs de {@code /payroll/engine-results} (filtres
 * appliqués), voir {@link EmployeeDirectoryExportService}. Même permission que la lecture des
 * résultats.
 */
@RestController
@RequestMapping("/api/payroll/engine/results/employees")
@RequiredArgsConstructor
public class EmployeeDirectoryExportController {

    private static final String CAN_VIEW = "hasAnyAuthority('"
            + PermissionCatalog.VIEW_RESULTS + "','" + PermissionCatalog.RUN_ENGINE + "')";

    private final EmployeeDirectoryExportService exportService;

    @PostMapping("/export.xlsx")
    @PreAuthorize(CAN_VIEW)
    public ResponseEntity<byte[]> exportXlsx(@RequestBody(required = false) EmployeeDirectoryExportService.Request req,
                                             @RequestParam(required = false) String lang) {
        return exportService.exportXlsx(req, ExportLang.of(lang)).toResponse();
    }
}
