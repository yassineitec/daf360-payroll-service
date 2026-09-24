package com.daf360.payroll.modules.employeeconfig.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record CalculateNetRequest(
        @NotNull @Positive BigDecimal grossSalary
) {}
