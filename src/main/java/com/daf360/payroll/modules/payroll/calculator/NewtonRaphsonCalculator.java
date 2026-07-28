package com.daf360.payroll.modules.payroll.calculator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.expression.EvaluationException;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Calculator for mode_calcul = "NEWTON_RAPHSON".
 *
 * Solves iteratively for rubriques whose own value appears inside their calculation
 * basis (circular dependency). A typical use case is deriving the gross salary from a
 * known net, where social charges are a function of gross.
 *
 * The formula stored in formulaExpression is evaluated with the token "SELF" substituted
 * by the current iteration guess x.  Newton-Raphson then finds x such that
 *   formula(x) == targetValue
 * where targetValue is read from the parameter key stored in paramKeyTaux.
 *
 * Convergence: max 100 iterations, absolute tolerance 0.01 (currency units).
 * The numerical derivative uses a central-difference step h = max(|x| * 0.001, 0.01).
 *
 * On failure (near-zero derivative, or max iterations reached) the last x is returned
 * and the orchestrator should flag convergenceOk = false.
 */
@Component
public class NewtonRaphsonCalculator implements PayrollCalculator {

    private static final Logger log = LoggerFactory.getLogger(NewtonRaphsonCalculator.class);
    private static final ExpressionParser PARSER = new SpelExpressionParser();
    private static final int MAX_ITERATIONS = 100;
    private static final double TOLERANCE = 0.01;

    @Override
    public String modeCalcul() {
        return "NEWTON_RAPHSON";
    }

    @Override
    public BigDecimal calculate(BigDecimal assiette, RubriqueSpec rubrique, ExecutionContext context) {
        String formula = rubrique.getFormulaExpression();
        if (formula == null || formula.isBlank()) {
            log.warn("NewtonRaphsonCalculator: null or blank formula for rubrique '{}'", rubrique.getCode());
            return BigDecimal.ZERO;
        }
        String tauxKey = rubrique.getParamKeyTaux();
        if (tauxKey == null) {
            log.warn("NewtonRaphsonCalculator: no paramKeyTaux (target value key) configured for rubrique '{}'",
                    rubrique.getCode());
            return BigDecimal.ZERO;
        }
        Object rawTarget = context.getParameters().get(tauxKey);
        if (rawTarget == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal targetValue = toDecimal(rawTarget);
        if (targetValue == null) {
            return BigDecimal.ZERO;
        }

        double target = targetValue.doubleValue();
        double x = assiette.doubleValue();

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            double fx = evaluateAt(formula, x, context, rubrique.getCode()) - target;
            if (Math.abs(fx) <= TOLERANCE) {
                return BigDecimal.valueOf(x).setScale(2, RoundingMode.HALF_UP);
            }
            double h = Math.abs(x) > 1e-6 ? Math.abs(x) * 0.001 : 0.01;
            double fxPlusH  = evaluateAt(formula, x + h, context, rubrique.getCode()) - target;
            double fxMinusH = evaluateAt(formula, x - h, context, rubrique.getCode()) - target;
            double derivative = (fxPlusH - fxMinusH) / (2.0 * h);
            if (Math.abs(derivative) < 1e-10) {
                log.warn("NewtonRaphsonCalculator: near-zero derivative at iteration {} for rubrique '{}' — stopping",
                        i, rubrique.getCode());
                break;
            }
            x = x - fx / derivative;
        }

        log.warn("NewtonRaphsonCalculator: did not converge within {} iterations for rubrique '{}', returning best estimate {}",
                MAX_ITERATIONS, rubrique.getCode(), x);
        return BigDecimal.valueOf(x).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Evaluates the formula at a given SELF value by substituting context variables
     * and then substituting the SELF token with the numeric value before SpEL evaluation.
     */
    private double evaluateAt(String formula, double selfValue, ExecutionContext context, String rubriqueCode) {
        Map<String, BigDecimal> vars = context.getVariables();
        List<String> sortedKeys = vars.keySet().stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
        String expr = formula;
        for (String varName : sortedKeys) {
            BigDecimal value = vars.get(varName);
            expr = expr.replaceAll(
                    "\\b" + Pattern.quote(varName) + "\\b",
                    value.toPlainString()
            );
        }
        // Substitute SELF with the current iteration guess
        String selfStr = BigDecimal.valueOf(selfValue).toPlainString();
        expr = expr.replaceAll("\\bSELF\\b", selfStr);
        try {
            Double result = PARSER.parseExpression(expr).getValue(Double.class);
            return result != null ? result : 0.0;
        } catch (EvaluationException | ArithmeticException e) {
            log.warn("NewtonRaphsonCalculator: SpEL evaluation failed for rubrique '{}', expr='{}': {}",
                    rubriqueCode, expr, e.getMessage());
            return 0.0;
        }
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
