package com.daf360.payroll.modules.simulation.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

public record CohortSimulationRequest(
        @NotNull Long paysId,
        @NotNull Integer fiscalYear,
        String cohortName,
        List<EmployeeSimEntry> employees
) {
    public record EmployeeSimEntry(
            Long profileUserId,
            BigDecimal inputNet,
            String contractType
    ) {}
}
