package com.daf360.payroll.modules.parameterset.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

public record CreateParameterSetRequest(
        @NotNull Long paysId,
        @NotNull Integer fiscalYear,
        @NotBlank String irppBrackets,
        BigDecimal convergenceTolerance,
        Integer maxConvergenceIterations,
        BigDecimal calibrationThresholdPct,
        /** Professional-expense deduction applied to the annual IRPP base; null/0 = none. */
        BigDecimal abattementRatePct,
        /** Annual cap on the abattement amount; null = uncapped. */
        BigDecimal abattementAnnualCap,
        /** Annual IRPP-base deduction when the employee is married; null/0 = none. */
        BigDecimal familyDeductionMarriedAmount,
        /** Annual IRPP-base deduction per dependent child; null/0 = none. */
        BigDecimal familyDeductionPerChildAmount,
        /** Maximum children counted toward the per-child deduction; null/0 = none counted. */
        Integer familyDeductionMaxChildren,
        String changeRationale,
        List<SocialChargeRateDto> socialChargeRates,
        List<BenefitCatalogueDto> benefits,
        List<SavePayrollRubriqueRequest> rubriques
) {}
