package com.daf360.payroll.modules.admin.dto;

/**
 * Création ou modification d'un pays de paie (`payroll_countries`). À la modification,
 * `paysId` est ignoré : un pays configuré ne change pas de pays, on en crée un autre.
 *
 * @param forexApiSources JSON des sources de taux de change (tableau ou objet), vide = aucune
 */
public record SavePayrollCountryRequest(
        Long paysId,
        String currencyCode,
        Integer fiscalYearStartMonth,
        String forexApiSources,
        Boolean active
) {}
