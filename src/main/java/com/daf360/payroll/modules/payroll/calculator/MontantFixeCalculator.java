package com.daf360.payroll.modules.payroll.calculator;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Calculator for mode_calcul = "MONTANT_FIXE".
 *
 * Returns the fixed monetary amount stored in the parameters map.
 * The assiette is ignored entirely.
 *
 * Parameters read from ExecutionContext:
 *   - paramKeyTaux → the fixed amount (reuses paramKeyTaux field as the amount key)
 */
@Component
public class MontantFixeCalculator implements PayrollCalculator {

    @Override
    public String modeCalcul() {
        return "MONTANT_FIXE";
    }

    @Override
    public BigDecimal calculate(BigDecimal assiette, RubriqueSpec rubrique, ExecutionContext context) {
        String montantKey = rubrique.getParamKeyTaux();
        if (montantKey == null) {
            return BigDecimal.ZERO;
        }
        Object raw = context.getParameters().get(montantKey);
        if (raw == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal montant = toDecimal(raw);
        return montant == null ? BigDecimal.ZERO : montant.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal toDecimal(Object raw) {
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
