package com.daf360.payroll.engine;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Thrown by {@link TopologicalEvaluator} when a dependency cycle is detected among
 * formula-based charge lines or rubrique lines.
 *
 * <p>Example: charge A's {@code formula_ee} references the result of charge B
 * ({@code CNSS_EE}), while charge B's {@code formula_ee} references the result of
 * charge A ({@code TFP_EE}) — creating a cycle A → B → A.
 *
 * <p>The simulation cannot proceed when a cycle exists.  The admin must break the
 * dependency loop by adjusting the formula expressions.
 */
public class CyclicDependencyException extends RuntimeException {

    private final List<String> cycleCodes;

    public CyclicDependencyException(List<String> cycleCodes) {
        super(buildMessage(cycleCodes));
        this.cycleCodes = cycleCodes;
    }

    /** The codes of the nodes that form the cycle (in no guaranteed order). */
    public List<String> getCycleCodes() {
        return cycleCodes;
    }

    private static String buildMessage(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return "Cycle détecté dans les formules de calcul (codes inconnus).";
        }
        String chain = codes.stream().collect(Collectors.joining(" → "));
        return "Cycle détecté dans les formules de calcul : " + chain
                + " → " + codes.get(0)
                + ". Veuillez corriger les dépendances dans l'éditeur de paramètres.";
    }
}
