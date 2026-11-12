package com.daf360.payroll.modules.employeeconfig.dto;

import java.util.List;

/**
 * Valeurs AFFICHÉES dans le formulaire de configuration de paie, pas encore forcément
 * enregistrées. Envoyées avec calculate-net / calculate-gross pour que le calcul se fasse
 * dès la première saisie et suive un pays / contrat / avantage modifié sans enregistrer.
 * Chaque champ null → valeur de la configuration enregistrée (ou de la fiche RH).
 */
public record CalculationFormValues(
        Long paysId,
        String contractType,
        List<String> selectedBenefitCodes,
        Boolean isMarried,
        Integer childrenCount
) {}
