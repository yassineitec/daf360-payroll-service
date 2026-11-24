package com.daf360.payroll.modules.simulation.dto;

import com.daf360.payroll.web.StrictJson;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * {@code POST /simulations/cohort/what-if/employee} — the salary what-if for ONE employee
 * (B1/B2, DEC12). Same change semantics as {@link CohortFilterRequest}.
 */
@StrictJson
public record EmployeeWhatIfRequest(
        @NotNull Long paysId,
        @NotNull Long profileUserId,
        /** PERCENT (alias PCT, default) or AMOUNT (alias ABSOLU). */
        String modifierType,
        BigDecimal modifierValue,
        /** NET (default) or GROSS. */
        String salaryBase
) {}
