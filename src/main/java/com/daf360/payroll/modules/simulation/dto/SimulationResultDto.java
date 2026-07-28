package com.daf360.payroll.modules.simulation.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record SimulationResultDto(
        Long id,
        Long paysId,
        Long profileUserId,
        Long parameterSetId,
        String simulationType,
        String contractType,
        BigDecimal inputNet,
        BigDecimal netTaxable,
        BigDecimal taxableBase,
        BigDecimal gross,
        BigDecimal loadedCost,
        BigDecimal loadedCostEur,
        BigDecimal loadedCostUsd,
        BigDecimal fxRateEur,
        BigDecimal fxRateUsd,
        String localCurrency,
        BigDecimal irppAmount,
        BigDecimal employeeCharges,
        BigDecimal employerCharges,
        String benefitsApplied,
        String rubriquesApplied,
        Integer iterationsUsed,
        Boolean convergenceOk,
        Long cohortId,
        OffsetDateTime simulatedAt
) {}
