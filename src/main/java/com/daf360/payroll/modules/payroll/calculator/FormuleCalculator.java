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
 * Calculator for mode_calcul = "FORMULE".
 *
 * Evaluates an arithmetic expression stored in formulaExpression.
 * Context variable names (e.g. STRATE_1, ABATTEMENT) are substituted with their
 * resolved BigDecimal values before the expression is handed to Spring SpEL.
 *
 * Example: "STRATE_1 * 0.092 - ABATTEMENT"
 *
 * Variable names are sorted longest-first before substitution to prevent partial
 * replacements (e.g. replacing "STRATE" before "STRATE_1" would corrupt the expression).
 */
@Component
public class FormuleCalculator implements PayrollCalculator {

    private static final Logger log = LoggerFactory.getLogger(FormuleCalculator.class);
    private static final ExpressionParser PARSER = new SpelExpressionParser();

    @Override
    public String modeCalcul() {
        return "FORMULE";
    }

    @Override
    public BigDecimal calculate(BigDecimal assiette, RubriqueSpec rubrique, ExecutionContext context) {
        String formula = rubrique.getFormulaExpression();
        if (formula == null || formula.isBlank()) {
            log.warn("FormuleCalculator: null or blank formula for rubrique '{}'", rubrique.getCode());
            return BigDecimal.ZERO;
        }
        return evaluateFormula(formula, context, rubrique.getCode());
    }

    /**
     * Exposed as protected so NewtonRaphsonCalculator can reuse the variable-substitution
     * + SpEL evaluation pipeline without code duplication.
     */
    protected BigDecimal evaluateFormula(String formula, ExecutionContext context, String rubriqueCode) {
        String expr = substituteVariables(formula, context.getVariables());
        try {
            Double result = PARSER.parseExpression(expr).getValue(Double.class);
            if (result == null) {
                log.warn("FormuleCalculator: SpEL returned null for rubrique '{}', expr='{}'", rubriqueCode, expr);
                return BigDecimal.ZERO;
            }
            return BigDecimal.valueOf(result).setScale(2, RoundingMode.HALF_UP);
        } catch (EvaluationException | ArithmeticException e) {
            log.warn("FormuleCalculator: evaluation failed for rubrique '{}', expr='{}': {}",
                    rubriqueCode, expr, e.getMessage());
            return BigDecimal.ZERO;
        }
    }

    /**
     * Replaces each variable name in the expression with its plain numeric string.
     * Names are processed longest-first to avoid partial token replacements.
     */
    protected String substituteVariables(String expr, Map<String, BigDecimal> vars) {
        List<String> sortedKeys = vars.keySet().stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
        for (String varName : sortedKeys) {
            BigDecimal value = vars.get(varName);
            expr = expr.replaceAll(
                    "\\b" + Pattern.quote(varName) + "\\b",
                    value.toPlainString()
            );
        }
        return expr;
    }
}
