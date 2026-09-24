package com.daf360.payroll.modules.employeeconfig.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

public record UpsertEmployeePayrollConfigRequest(
        @NotNull Long paysId,
        @NotNull String contractType,
        List<String> selectedBenefitCodes,
        BigDecimal currentGrossSalary,
        BigDecimal currentNetSalary,
        @NotBlank String reason
) {}
