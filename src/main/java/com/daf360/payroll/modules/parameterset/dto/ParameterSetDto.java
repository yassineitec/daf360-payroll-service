package com.daf360.payroll.modules.parameterset.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record ParameterSetDto(
        Long id,
        Long paysId,
        Integer version,
        Integer fiscalYear,
        String status,
        String irppBrackets,
        BigDecimal convergenceTolerance,
        Integer maxConvergenceIterations,
        BigDecimal calibrationThresholdPct,
        Long approvedByHr,
        Long approvedByFinance,
        OffsetDateTime approvedAt,
        OffsetDateTime activatedAt,
        String changeRationale,
        Long previousVersionId,
        OffsetDateTime createdAt,
        List<SocialChargeRateDto> socialChargeRates,
        List<BenefitCatalogueDto> benefits,
        List<PayrollRubriqueDto> rubriques
) {}
