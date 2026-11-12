package com.daf360.payroll.modules.employeeconfig.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/** Net saisi dans la configuration de paie → brut correspondant (calcul inverse).
 *  {@code inputCurrency} : devise de saisie, comme {@link CalculateNetRequest}. */
public record CalculateGrossRequest(
        @NotNull @Positive BigDecimal netSalary,
        String inputCurrency
) {}
