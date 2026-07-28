package com.daf360.payroll.modules.payroll.calculator;

import java.math.BigDecimal;

/**
 * Strategy interface for rubrique amount calculators.
 *
 * Each implementation handles one mode_calcul code and is registered as a
 * Spring @Component. The orchestrator builds a Map&lt;String, PayrollCalculator&gt;
 * keyed by modeCalcul() at startup to dispatch calculations without branching.
 */
public interface PayrollCalculator {

    /**
     * Returns the mode_calcul code this calculator handles (e.g. "TAUX_PCT").
     */
    String modeCalcul();

    /**
     * Calculates the rubrique amount.
     *
     * @param assiette  resolved base value from context (BigDecimal.ZERO if assietteCode not resolved)
     * @param rubrique  the rubrique definition (code, paramKeys, formula, etc.)
     * @param context   current execution context (variables, parameters, employee info)
     * @return          computed amount — always positive; direction (GAIN vs RETENUE) applied by the orchestrator
     */
    BigDecimal calculate(BigDecimal assiette, RubriqueSpec rubrique, ExecutionContext context);
}
