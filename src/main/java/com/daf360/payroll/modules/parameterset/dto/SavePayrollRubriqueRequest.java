package com.daf360.payroll.modules.parameterset.dto;

import java.math.BigDecimal;

public record SavePayrollRubriqueRequest(
        String code,
        String labelFr,
        String labelEn,
        String nature,
        String calcMode,
        BigDecimal amount,
        BigDecimal rate,
        BigDecimal employerSharePct,
        BigDecimal employeeSharePct,
        Boolean isSubjectToSocialCharges,
        Boolean isSubjectToIrpp,
        String direction,
        String contractTypes,
        Boolean isActive
) {}
