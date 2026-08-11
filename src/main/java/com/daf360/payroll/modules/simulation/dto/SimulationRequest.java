package com.daf360.payroll.modules.simulation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.List;

public record SimulationRequest(
        @NotNull Long paysId,
        // NET_TO_BRUT (default): required. BRUT_TO_NET: ignored. Validated in service.
        @Positive BigDecimal inputNet,
        Long profileUserId,
        String contractType,              // CDI|CDD|STAGE|CIVP  (defaults to CDI)
        Integer joursTravailes,           // working days for FIXE_JOURNALIER rubriques (defaults to 22)
        List<String> selectedBenefitCodes,// null = apply all active benefits; empty = no benefits
        String candidateLabel,            // free-text label saved in result (for PDF report)
        String poste,                     // job title saved in result
        String grade,                     // grade saved in result
        String discipline,                // discipline saved in result
        // ── simulation direction ──────────────────────────────────────────────
        SimulationMode mode,              // null → NET_TO_BRUT (backward-compatible default)
        @Positive BigDecimal inputGross   // BRUT_TO_NET: required. NET_TO_BRUT: ignored.
) {}
