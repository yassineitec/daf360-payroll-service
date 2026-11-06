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
        Boolean isMarried,
        Integer childrenCount,
        @NotBlank String reason,
        /** Null/omitted = currentGrossSalary is already in the working currency (today's
         *  behavior, zero change for existing callers). */
        String inputCurrency
) {}
