package com.daf360.payroll.modules.employeeconfig.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Family situation (isMarried / childrenCount) is no longer part of this request: it is read
 * from the RH profile. Older clients that still send it are harmless — unknown properties are
 * ignored (spring.jackson fail-on-unknown-properties: false).
 */
public record UpsertEmployeePayrollConfigRequest(
        @NotNull Long paysId,
        @NotBlank String contractType,
        List<String> selectedBenefitCodes,
        /** As typed, in {@code inputCurrency} (or in the working currency when that is null). */
        @PositiveOrZero BigDecimal currentGrossSalary,
        /** Ignored whenever the engine can compute the net from the gross (the normal case). */
        @PositiveOrZero BigDecimal currentNetSalary,
        @NotBlank String reason,
        /** Null/omitted = currentGrossSalary is already in the working currency. */
        String inputCurrency,
        /** Date the salary applies from. Null/omitted (older clients) = the day of the save.
         *  Informational: the engine keeps using the latest saved configuration. */
        LocalDate effectiveDate
) {}
