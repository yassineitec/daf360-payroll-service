package com.daf360.payroll.modules.employeeconfig.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/** Net saisi dans la configuration de paie → brut correspondant (calcul inverse).
 *  {@code inputCurrency} : devise de saisie, comme {@link CalculateNetRequest} ;
 *  {@code form} : valeurs du formulaire, optionnelles — voir {@link CalculationFormValues}. */
public record CalculateGrossRequest(
        @NotNull @Positive BigDecimal netSalary,
        String inputCurrency,
        CalculationFormValues form
) {
    /** Sans valeurs de formulaire : calcul sur la configuration enregistrée. */
    public CalculateGrossRequest(BigDecimal netSalary, String inputCurrency) {
        this(netSalary, inputCurrency, null);
    }
}
