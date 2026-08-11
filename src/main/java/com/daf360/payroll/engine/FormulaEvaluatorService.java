package com.daf360.payroll.engine;

import net.objecthunter.exp4j.Expression;
import net.objecthunter.exp4j.ExpressionBuilder;
import net.objecthunter.exp4j.ValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * Evaluates arithmetic formula expressions for FORMULE-mode payroll rubriques.
 *
 * <p>Formulas are standard arithmetic using the operators {@code + - * / ( )}.
 * Variables are supplied as a {@code name → value} map; names must consist of
 * uppercase letters, digits and underscores (e.g. {@code BRUT}, {@code CNSS_EE},
 * {@code PRIME_ANCIENNETE}).
 *
 * <p>Built-in functions provided by exp4j are available: {@code abs}, {@code floor},
 * {@code ceil}, {@code sqrt}, {@code min} (binary), {@code max} (binary).
 *
 * <p>On any error — unknown variable, division by zero, syntax error — returns
 * {@link BigDecimal#ZERO} and logs a warning. This is intentional: a bad formula
 * should not crash the simulation; the admin can fix and retry.
 */
@Service
public class FormulaEvaluatorService {

    private static final Logger log = LoggerFactory.getLogger(FormulaEvaluatorService.class);
    private static final int SCALE = 4;

    /**
     * Evaluate a formula expression against the given variable context.
     *
     * @param expression formula, e.g. {@code "BRUT * 0.02 + CNSS_EE * 0.5"}
     * @param variables  variable name → BigDecimal value map (all known variables for this context)
     * @return rounded result (scale 4, HALF_UP), or ZERO on any error
     */
    public BigDecimal evaluate(String expression, Map<String, BigDecimal> variables) {
        if (expression == null || expression.isBlank()) return BigDecimal.ZERO;
        try {
            ExpressionBuilder builder = new ExpressionBuilder(expression)
                    .variables(variables.keySet());

            Expression expr = builder.build();

            for (Map.Entry<String, BigDecimal> entry : variables.entrySet()) {
                expr.setVariable(entry.getKey(), entry.getValue().doubleValue());
            }

            ValidationResult validation = expr.validate(false);
            if (!validation.isValid()) {
                log.warn("Invalid formula expression='{}': {}", expression, validation.getErrors());
                return BigDecimal.ZERO;
            }

            double result = expr.evaluate();

            if (Double.isNaN(result) || Double.isInfinite(result)) {
                log.warn("Formula '{}' produced non-finite result: {}", expression, result);
                return BigDecimal.ZERO;
            }

            return BigDecimal.valueOf(result).setScale(SCALE, RoundingMode.HALF_UP);

        } catch (Exception e) {
            log.warn("Formula evaluation failed for expression='{}': {}", expression, e.getMessage());
            return BigDecimal.ZERO;
        }
    }
}
