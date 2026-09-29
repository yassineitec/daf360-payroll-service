package com.daf360.payroll.modules.admin.dto;

/**
 * Création ou modification d'une rubrique du moteur de paie (`payroll_rubriques`). À la
 * modification, `code` est ignoré : les formules des autres rubriques y font référence.
 *
 * Clés de paramètres : noms de valeurs du jeu de paramètres du moteur — `paramKeyTaux`
 * (taux en %, montant fixe, ou valeur cible selon le mode), `paramKeyPlafond`,
 * `paramKeyBareme` (barème progressif).
 */
public record SaveEngineRubriqueRequest(
        String code,
        String labelFr,
        String labelEn,
        Integer strate,
        String nature,
        String modeCalcul,
        String assietteCode,
        String paramKeyTaux,
        String paramKeyPlafond,
        String paramKeyBareme,
        String formulaExpression,
        String contractTypeFilter,
        String periodicite,
        Boolean prorataApplicable,
        Integer displayOrder,
        Boolean active
) {}
