package com.daf360.payroll.modules.payroll.calculator;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for a real bug found while building the Tunisia/Egypt strate-4
 * "assiette" rubriques (see 2026-11-06-engine-run-irpp-css-net-fix plan, Task 7):
 * a whole-number BigDecimal substituted via toPlainString() (e.g. "2000") parses in
 * SpEL as an int literal, and Spring's reflective resolver for T(Math).max/min refuses
 * to pick an overload when the two arguments parse as different literal types
 * (int vs double), throwing EL1031E. FormuleCalculator swallows that silently and
 * returns ZERO — so any formula mixing T(Math).max/min with a whole-number operand
 * always evaluated to 0, with no error anywhere.
 */
class FormuleCalculatorTest {

    private final FormuleCalculator calculator = new FormuleCalculator();

    @Test
    void mathMinMaxWorkWhenOneOperandIsAWholeNumberVariable() {
        ExecutionContext ctx = new ExecutionContext(
                1L, 7L, 179L, 2026, 1, "CDI", BigDecimal.valueOf(22), Map.of());
        // A whole-number value, exactly the shape that broke before the fix:
        // toPlainString() on this BigDecimal is "2000", with no decimal point.
        ctx.putVariable("ABATTEMENT_CAP", new BigDecimal("2000"));
        ctx.putVariable("ANNUAL_BASE", new BigDecimal("5007.36"));

        RubriqueSpec rubrique = RubriqueSpec.builder()
                .code("TEST_MIN")
                .formulaExpression("T(Math).min(ANNUAL_BASE, ABATTEMENT_CAP)")
                .build();

        BigDecimal result = calculator.calculate(null, rubrique, ctx);

        assertThat(result).isEqualByComparingTo("2000.00");
    }

    @Test
    void mathMaxWorksWhenTheFloorOperandIsAWholeNumberVariable() {
        ExecutionContext ctx = new ExecutionContext(
                1L, 7L, 179L, 2026, 1, "CDI", BigDecimal.valueOf(22), Map.of());
        // DEFICIT is negative, so T(Math).max(DEFICIT, FLOOR) must actually pick FLOOR —
        // and FLOOR, like ABATTEMENT_CAP above, is a whole number: toPlainString() on it
        // is "0", with no decimal point, the same shape that broke before the fix.
        ctx.putVariable("DEFICIT", new BigDecimal("-100.00"));
        ctx.putVariable("FLOOR", BigDecimal.ZERO);

        RubriqueSpec rubrique = RubriqueSpec.builder()
                .code("TEST_MAX")
                .formulaExpression("T(Math).max(DEFICIT, FLOOR)")
                .build();

        BigDecimal result = calculator.calculate(null, rubrique, ctx);

        assertThat(result).isEqualByComparingTo("0.00");
    }
}
