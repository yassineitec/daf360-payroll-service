package com.daf360.payroll.modules.payroll.calculator;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Calculator for mode_calcul = "TAUX_PCT".
 *
 * Formula: amount = min(assiette, plafond) * taux / 100
 *
 * Parameters read from ExecutionContext:
 *   - paramKeyTaux    → the rate in percent (e.g. 9.18 means 9.18 %)
 *   - paramKeyPlafond → optional ceiling applied to assiette before multiplication
 */
@Component
public class TauxPctCalculator implements PayrollCalculator {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    @Override
    public String modeCalcul() {
        return "TAUX_PCT";
    }

    @Override
    public BigDecimal calculate(BigDecimal assiette, RubriqueSpec rubrique, ExecutionContext context) {
        String tauxKey = rubrique.getParamKeyTaux();
        if (tauxKey == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal taux = getParam(context, tauxKey);
        if (taux == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal base = assiette;
        if (rubrique.getParamKeyPlafond() != null) {
            BigDecimal plafond = getParam(context, rubrique.getParamKeyPlafond());
            if (plafond != null) {
                base = base.min(plafond);
            }
        }
        return base.multiply(taux)
                .divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }

    /**
     * Safely extracts a BigDecimal from the parameters map, handling Double, Integer,
     * Long, BigDecimal, and String types that Jackson may produce when parsing JSON.
     */
    private BigDecimal getParam(ExecutionContext ctx, String key) {
        Object raw = ctx.getParameters().get(key);
        if (raw == null) return null;
        if (raw instanceof BigDecimal) return (BigDecimal) raw;
        if (raw instanceof Double) return BigDecimal.valueOf((Double) raw);
        if (raw instanceof Integer) return BigDecimal.valueOf(((Integer) raw).longValue());
        if (raw instanceof Long) return BigDecimal.valueOf((Long) raw);
        if (raw instanceof String) {
            try {
                return new BigDecimal((String) raw);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
