package com.daf360.payroll.modules.payroll.calculator;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * Calculator for mode_calcul = "BAREME_PROGRESSIF".
 *
 * Applies a standard progressive tax bracket schedule to the assiette.
 * Each bracket map must contain: {min, max (optional), rate} where rate is a percent
 * (e.g. 26 means 26 %).  A missing or null "max" means the bracket is open-ended.
 *
 * Parameters read from ExecutionContext:
 *   - paramKeyBareme → List&lt;Map&lt;String, Object&gt;&gt; loaded from the JSON parameter set
 */
@Component
public class BaremeProgressifCalculator implements PayrollCalculator {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    @Override
    public String modeCalcul() {
        return "BAREME_PROGRESSIF";
    }

    @Override
    public BigDecimal calculate(BigDecimal assiette, RubriqueSpec rubrique, ExecutionContext context) {
        String baremeKey = rubrique.getParamKeyBareme();
        if (baremeKey == null) {
            return BigDecimal.ZERO;
        }
        Object raw = context.getParameters().get(baremeKey);
        if (!(raw instanceof List)) {
            return BigDecimal.ZERO;
        }
        return applyBracketsProgressif(assiette, (List<?>) raw);
    }

    /**
     * Core progressive bracket calculation, exposed as protected so
     * AnnualiseBaremeCalculator can reuse it on the annualised base.
     */
    protected BigDecimal applyBracketsProgressif(BigDecimal base, List<?> brackets) {
        BigDecimal total = BigDecimal.ZERO;
        for (Object entry : brackets) {
            if (!(entry instanceof Map)) continue;
            Map<?, ?> bracket = (Map<?, ?>) entry;
            BigDecimal min = toDecimal(bracket.get("min"));
            BigDecimal max = bracket.get("max") != null ? toDecimal(bracket.get("max")) : null;
            BigDecimal rate = toDecimal(bracket.get("rate"));
            if (min == null || rate == null) continue;
            // No contribution from this bracket if base does not exceed its lower bound
            if (base.compareTo(min) <= 0) continue;
            BigDecimal cap = (max != null) ? base.min(max) : base;
            BigDecimal slice = cap.subtract(min).max(BigDecimal.ZERO);
            total = total.add(
                    slice.multiply(rate).divide(HUNDRED, 10, RoundingMode.HALF_UP)
            );
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Converts a raw Jackson-parsed object to BigDecimal, handling the common
     * Double / Integer / Long / String types that appear in deserialized JSON maps.
     */
    protected BigDecimal toDecimal(Object raw) {
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
