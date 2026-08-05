package com.daf360.payroll.modules.parameterset.dto;

import jakarta.validation.constraints.DecimalMin;
import java.math.BigDecimal;

public record SavePayrollRubriqueRequest(
        String code,
        String labelFr,
        String labelEn,
        String nature,
        String calcMode,
        BigDecimal amount,
        BigDecimal rate,
        @DecimalMin(value = "0.00", message = "capAmount doit être positif ou nul")
        BigDecimal capAmount,
        BigDecimal employerSharePct,
        BigDecimal employeeSharePct,
        Boolean isSubjectToSocialCharges,
        Boolean isSubjectToIrpp,
        String direction,
        String contractTypes,
        Boolean isActive
) {}
