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
        BigDecimal capAmount
) {}
