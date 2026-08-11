package com.daf360.payroll.modules.parameterset.dto;

import java.math.BigDecimal;

public record SocialChargeRateDto(
        Long id,
        String contractType,
        String chargeCode,
        String chargeLabel,
        BigDecimal employeeRate,
        BigDecimal employerRate,
        String baseCalculation,
        BigDecimal capAmount,
        /** Formula override for the employee-side amount; null = use employeeRate × base. */
        String formulaEe,
        /** Formula override for the employer-side amount; null = use employerRate × base. */
        String formulaEr,
        /** Evaluation order; lower-order charges are evaluated first. */
        Integer evalOrder
) {}
