package com.daf360.payroll.modules.employeeconfig.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record CreateEmployeePayrollBonusRequest(
        @NotNull @Positive BigDecimal amount,
        @NotBlank String currency,
        @NotNull @Min(1) @Max(12) Integer periodMonth,
        @NotNull Integer periodYear,
        @NotBlank String label,
        String comment
) {}
