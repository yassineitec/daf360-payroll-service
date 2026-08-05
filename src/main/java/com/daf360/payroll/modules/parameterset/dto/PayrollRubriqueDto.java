package com.daf360.payroll.modules.parameterset.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record PayrollRubriqueDto(
        Long id,
        String code,
        String labelFr,
        String labelEn,
        String nature,
        String calcMode,
        BigDecimal amount,
        BigDecimal rate,
        BigDecimal capAmount,
        BigDecimal employerSharePct,
        BigDecimal employeeSharePct,
        Boolean isSubjectToSocialCharges,
        Boolean isSubjectToIrpp,
        String direction,
        String contractTypes,
        Boolean isActive,
        OffsetDateTime createdAt
) {}
