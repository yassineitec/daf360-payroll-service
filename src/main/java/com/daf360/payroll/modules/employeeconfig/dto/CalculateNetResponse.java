package com.daf360.payroll.modules.employeeconfig.dto;

import java.math.BigDecimal;

public record CalculateNetResponse(
        BigDecimal netInHand
) {}
