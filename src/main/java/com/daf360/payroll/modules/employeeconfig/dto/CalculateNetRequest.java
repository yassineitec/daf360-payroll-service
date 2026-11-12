package com.daf360.payroll.modules.employeeconfig.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/** {@code form} : valeurs du formulaire, optionnelles — voir {@link CalculationFormValues}. */
public record CalculateNetRequest(
        @NotNull @Positive BigDecimal grossSalary,
        String inputCurrency,
        CalculationFormValues form
) {
    /** Sans valeurs de formulaire : calcul sur la configuration enregistrée. */
    public CalculateNetRequest(BigDecimal grossSalary, String inputCurrency) {
        this(grossSalary, inputCurrency, null);
    }
}
