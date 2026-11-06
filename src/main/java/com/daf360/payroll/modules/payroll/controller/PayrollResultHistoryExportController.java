package com.daf360.payroll.modules.payroll.controller;

import com.daf360.payroll.common.export.ExportKeysRequest;
import com.daf360.payroll.common.export.ExportLang;
import com.daf360.payroll.modules.payroll.service.PayrollResultExportService;
import com.daf360.payroll.security.PermissionCatalog;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static com.daf360.payroll.common.export.ExportKeysRequest.idsOf;

/**
 * Export Excel de l'historique de paie d'un collaborateur (liste des périodes de
 * {@code /payroll/engine-results/:employeeId}), généré côté serveur comme l'export du
 * bulletin ({@link PayrollResultExportController}). POST : le corps porte les numéros des
 * résultats affichés, dans l'ordre de l'écran ({@link ExportKeysRequest}). Même permission que
 * la lecture des résultats.
 */
@RestController
@RequestMapping("/api/payroll/engine/results/employee/{employeeId}")
@RequiredArgsConstructor
public class PayrollResultHistoryExportController {

    private static final String CAN_VIEW = "hasAnyAuthority('"
            + PermissionCatalog.VIEW_RESULTS + "','" + PermissionCatalog.RUN_ENGINE + "')";

    private final PayrollResultExportService exportService;

    @PostMapping("/export.xlsx")
    @PreAuthorize(CAN_VIEW)
    public ResponseEntity<byte[]> exportXlsx(@PathVariable Long employeeId,
                                             @RequestBody(required = false) ExportKeysRequest req,
                                             @RequestParam(required = false) String lang) {
        return exportService.exportHistoryXlsx(employeeId, idsOf(req), ExportLang.of(lang)).toResponse();
    }

    /** {@code employee} : nom affiché dans l'en-tête — le service paie ne lit pas l'annuaire RH. */
    @PostMapping("/export.pdf")
    @PreAuthorize(CAN_VIEW)
    public ResponseEntity<byte[]> exportPdf(@PathVariable Long employeeId,
                                            @RequestBody(required = false) ExportKeysRequest req,
                                            @RequestParam(required = false) String employee,
                                            @RequestParam(required = false) String lang) {
        return exportService.exportHistoryPdf(employeeId, idsOf(req), employee, ExportLang.of(lang)).toResponse();
    }
}
