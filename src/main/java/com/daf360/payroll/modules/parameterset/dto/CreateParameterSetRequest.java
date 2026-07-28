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
        String changeRationale,
        List<SocialChargeRateDto> socialChargeRates,
        List<BenefitCatalogueDto> benefits,
        List<SavePayrollRubriqueRequest> rubriques
) {}
