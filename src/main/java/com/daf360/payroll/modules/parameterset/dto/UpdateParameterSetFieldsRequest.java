package com.daf360.payroll.modules.parameterset.dto;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

/**
 * Updates a DRAFT parameter set's top-level fields (barème IRPP, convergence settings,
 * abattement, family deductions) in place — mirrors {@code updateSocialChargeRates}/
 * {@code updateBenefits}/{@code updateRubriques}, which already allow editing a DRAFT's
 * sub-resources without creating a whole new version.
 */
public record UpdateParameterSetFieldsRequest(
        @NotBlank String irppBrackets,
        BigDecimal convergenceTolerance,
        Integer maxConvergenceIterations,
        BigDecimal calibrationThresholdPct,
        BigDecimal abattementRatePct,
        BigDecimal abattementAnnualCap,
        BigDecimal familyDeductionMarriedAmount,
        BigDecimal familyDeductionPerChildAmount,
        Integer familyDeductionMaxChildren
) {}
