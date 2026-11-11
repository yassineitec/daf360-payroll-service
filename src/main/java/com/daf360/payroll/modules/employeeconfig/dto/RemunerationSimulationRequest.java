package com.daf360.payroll.modules.employeeconfig.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * What the « Nouvelle rémunération » form is showing — nothing saved.
 *
 * @param grossSalary          monthly base salary as typed, in {@code inputCurrency}
 * @param inputCurrency        null = the country's working currency
 * @param paysId               null = the saved/default one
 * @param contractType         null = the saved/default one
 * @param selectedBenefitCodes benefits ticked in the form (empty = none)
 * @param effectiveDate        the month whose exceptional bonuses are added; null = this month
 */
public record RemunerationSimulationRequest(
        @NotNull @Positive BigDecimal grossSalary,
        String inputCurrency,
        Long paysId,
        String contractType,
        List<String> selectedBenefitCodes,
        LocalDate effectiveDate
) {}
