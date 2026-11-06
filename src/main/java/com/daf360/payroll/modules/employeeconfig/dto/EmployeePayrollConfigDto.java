package com.daf360.payroll.modules.employeeconfig.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record EmployeePayrollConfigDto(
        Long profileUserId,
        Long paysId,
        String contractType,
        List<String> selectedBenefitCodes,
        BigDecimal currentGrossSalary,
        BigDecimal currentNetSalary,
        Boolean isMarried,
        Integer childrenCount,
        Long updatedBy,
        OffsetDateTime updatedAt,
        /** ISO code the stored gross salary was originally typed in; null = it was
         *  already in the working currency. */
        String inputCurrency,
        /** The originally-typed gross salary; null together with inputCurrency. */
        BigDecimal inputAmountOriginal,
        /** The country's working currency (what currentGrossSalary/currentNetSalary are
         *  actually denominated in) — resolved server-side so a caller with no other
         *  source for it (daf360-rh-frontend) doesn't need one. Null only when paysId
         *  itself is null (never-configured employee, country not yet resolved). */
        String workingCurrency
) {}
