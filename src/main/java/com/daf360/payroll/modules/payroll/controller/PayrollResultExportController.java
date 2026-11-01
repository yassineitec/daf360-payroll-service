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
 * Exports du détail par rubrique d'un résultat de paie (Excel + PDF), générés côté serveur
 * comme ceux de la facturation ({@code CostExportController}, {@code AffaireExportController}).
 * <p>
 * POST bien que ce soient des lectures : le corps porte la sélection affichée à l'écran
 * ({@link ExportKeysRequest}, codes de rubrique dans l'ordre du tableau). Même permission que
 * la lecture des résultats ({@code GET /api/payroll/engine/results/{employeeId}}).
 */
@RestController
@RequestMapping("/api/payroll/engine/results/{resultId}/rubriques")
@RequiredArgsConstructor
public class PayrollResultExportController {

    private static final String CAN_VIEW = "hasAnyAuthority('"
            + PermissionCatalog.VIEW_RESULTS + "','" + PermissionCatalog.RUN_ENGINE + "')";

    private final PayrollResultExportService exportService;

    @PostMapping("/export.xlsx")
    @PreAuthorize(CAN_VIEW)
    public ResponseEntity<byte[]> exportXlsx(@PathVariable Long resultId,
                                             @RequestBody(required = false) ExportKeysRequest req,
                                             @RequestParam(required = false) String lang) {
        return exportService.exportXlsx(resultId, idsOf(req), ExportLang.of(lang)).toResponse();
    }

    /** {@code employee} : nom affiché dans l'en-tête — le service paie ne lit pas l'annuaire RH. */
    @PostMapping("/export.pdf")
    @PreAuthorize(CAN_VIEW)
    public ResponseEntity<byte[]> exportPdf(@PathVariable Long resultId,
                                            @RequestBody(required = false) ExportKeysRequest req,
                                            @RequestParam(required = false) String employee,
                                            @RequestParam(required = false) String lang) {
        return exportService.exportPdf(resultId, idsOf(req), employee, ExportLang.of(lang)).toResponse();
    }
}
