package com.daf360.payroll.modules.employeeconfig.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * @param paysId       country currently shown on the screen (may be unsaved); null = saved/default one
 * @param contractType contract currently shown on the screen (may be unsaved); null = saved/default one
 * @param form         valeurs du formulaire, optionnelles — voir {@link CalculationFormValues}.
 *                     Son pays / contrat priment sur {@code paysId} / {@code contractType}.
 */
public record CalculateNetRequest(
        @NotNull @Positive BigDecimal grossSalary,
        String inputCurrency,
        Long paysId,
        String contractType,
        CalculationFormValues form
) {
    /** Sans valeurs de formulaire : calcul sur la configuration enregistrée. */
    public CalculateNetRequest(BigDecimal grossSalary, String inputCurrency) {
        this(grossSalary, inputCurrency, null, null, null);
    }

    /** Pays / contrat affichés à l'écran, sans autres valeurs de formulaire. */
    public CalculateNetRequest(BigDecimal grossSalary, String inputCurrency, Long paysId, String contractType) {
        this(grossSalary, inputCurrency, paysId, contractType, null);
    }

    /** Valeurs du formulaire seules. */
    public CalculateNetRequest(BigDecimal grossSalary, String inputCurrency, CalculationFormValues form) {
        this(grossSalary, inputCurrency, null, null, form);
    }
}
