package com.daf360.payroll.modules.employeeconfig.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public record EmployeePayrollConfigDto(
        Long profileUserId,
        Long paysId,
        String contractType,
        List<String> selectedBenefitCodes,
        BigDecimal currentGrossSalary,
        /** Computed by the payroll engine from currentGrossSalary on every save (read-only). */
        BigDecimal currentNetSalary,
        /** Family situation for the IRPP deduction — read from the RH profile (source of
         *  truth), see familyFromHr. Not editable through this API. */
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
        String workingCurrency,
        /** True = isMarried/childrenCount were just read from the RH profile; false = RH was
         *  unreachable (or has no profile) and the last cached value is shown. */
        Boolean familyFromHr,
        /** Date the current salary applies from (V37); null when saved before V37 or never saved. */
        LocalDate effectiveDate
) {}
