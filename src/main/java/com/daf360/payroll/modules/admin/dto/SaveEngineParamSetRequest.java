package com.daf360.payroll.modules.admin.dto;

import java.time.LocalDate;

/**
 * Modification d'un jeu de paramètres du moteur en brouillon.
 *
 * @param parameters    objet JSON clé → valeur : nombre (taux en %, montant, plafond), texte,
 *                      ou barème progressif = tableau de tranches {@code {min, max?, rate}}
 * @param effectiveDate date d'effet de la version (null = inchangée)
 */
public record SaveEngineParamSetRequest(
        String parameters,
        LocalDate effectiveDate
) {}
