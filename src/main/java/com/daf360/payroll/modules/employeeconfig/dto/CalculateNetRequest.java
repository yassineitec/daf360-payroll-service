package com.daf360.payroll.modules.employeeconfig.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * @param paysId       country currently shown on the screen (may be unsaved); null = saved/default one
 * @param contractType contract currently shown on the screen (may be unsaved); null = saved/default one
 */
public record CalculateNetRequest(
        @NotNull @Positive BigDecimal grossSalary,
        String inputCurrency,
        Long paysId,
        String contractType
) {}
