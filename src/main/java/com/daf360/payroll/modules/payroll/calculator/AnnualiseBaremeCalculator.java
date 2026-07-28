package com.daf360.payroll.modules.payroll.calculator;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Calculator for mode_calcul = "ANNUALISE_BAREME".
 *
 * Same progressive bracket logic as BaremeProgressifCalculator, but the monthly
 * assiette is first annualised (* 12), the tax is computed on the annual base,
 * then the result is divided by 12 to get the monthly charge.
 *
 * Typical use: IRPP monthly withholding when the country uses an annual scale.
 */
@Component
public class AnnualiseBaremeCalculator extends BaremeProgressifCalculator {

    private static final BigDecimal TWELVE = new BigDecimal("12");

    @Override
    public String modeCalcul() {
        return "ANNUALISE_BAREME";
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
        BigDecimal annualAssiette = assiette.multiply(TWELVE);
        BigDecimal annualResult = applyBracketsProgressif(annualAssiette, (List<?>) raw);
        return annualResult.divide(TWELVE, 2, RoundingMode.HALF_UP);
    }
}
