package com.daf360.payroll.modules.simulation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.List;

public record CohortSimulationRequest(
        @NotNull Long paysId,
        @NotNull Integer fiscalYear,
        String cohortName,
        // Global mode toggle — all entries in this cohort use the same direction.
        // null → NET_TO_BRUT (backward-compatible default).
        SimulationMode mode,
        List<EmployeeSimEntry> employees
) {
    public record EmployeeSimEntry(
            Long profileUserId,
            // NET_TO_BRUT: required. BRUT_TO_NET: ignored.
            BigDecimal inputNet,
            String contractType,
            // BRUT_TO_NET: required. NET_TO_BRUT: ignored.
            @Positive BigDecimal inputGross
    ) {}
}
