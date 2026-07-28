package com.daf360.payroll.modules.payroll.calculator;

import lombok.Builder;
import lombok.Getter;

/**
 * Lightweight projection of PayrollRubriqueDef fields used by calculators.
 * Built by the orchestrator from the entity before calling calculate().
 */
@Getter
@Builder
public class RubriqueSpec {

    /** Unique rubrique code, e.g. "SALAIRE_BASE". */
    private String code;

    /** Nature: GAIN | RETENUE | COTISATION | TAXE | AVANTAGE */
    private String nature;

    /** Mode of calculation, e.g. "TAUX_PCT". Matches PayrollCalculator.modeCalcul(). */
    private String modeCalcul;

    /** Variable name to look up in ExecutionContext as the calculation base. */
    private String assietteCode;

    /** Key in parameters map that holds the rate (taux) or montant for fixed amounts. */
    private String paramKeyTaux;

    /** Key in parameters map that holds the ceiling (plafond) value. Null = no ceiling. */
    private String paramKeyPlafond;

    /** Key in parameters map that holds the progressive bracket list. */
    private String paramKeyBareme;

    /** SpEL/arithmetic expression string for FORMULE and NEWTON_RAPHSON modes. */
    private String formulaExpression;

    /** Contract type filter (comma-separated codes). Null = applies to all contracts. */
    private String contractTypeFilter;

    /** Periodicity: MENSUEL | TRIMESTRIEL | ANNUEL */
    private String periodicite;

    /** Whether pro-rata calculation applies when joursOuvresMois < standard month. */
    private boolean prorataApplicable;
}
