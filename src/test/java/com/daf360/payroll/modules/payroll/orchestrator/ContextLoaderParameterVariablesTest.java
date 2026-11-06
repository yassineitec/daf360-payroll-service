package com.daf360.payroll.modules.payroll.orchestrator;

import com.daf360.payroll.modules.payroll.calculator.ExecutionContext;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContextLoaderParameterVariablesTest {

    @Test
    void copiesScalarParametersIntoVariablesByTheirOwnKeyName() {
        Map<String, Object> params = Map.of(
                "abattement_rate_tn", 0.10,
                "abattement_cap_tn", 2000,
                "salaire_base_tn", 2500.0
        );

        ExecutionContext ctx = new ExecutionContext(
                1L, 7L, 179L, 2026, 1, "CDI", BigDecimal.valueOf(22), params);
        ContextLoader.copyScalarParametersAsVariables(ctx, params);

        assertThat(ctx.getVariable("abattement_rate_tn")).isEqualByComparingTo("0.10");
        assertThat(ctx.getVariable("abattement_cap_tn")).isEqualByComparingTo("2000");
        assertThat(ctx.getVariable("salaire_base_tn")).isEqualByComparingTo("2500.0");
    }

    @Test
    void skipsNonScalarParameters() {
        Map<String, Object> params = Map.of(
                "bareme_irpp_tn", List.of(Map.of("min", 0, "max", 5000, "rate", 0))
        );

        ExecutionContext ctx = new ExecutionContext(
                1L, 7L, 179L, 2026, 1, "CDI", BigDecimal.valueOf(22), params);
        ContextLoader.copyScalarParametersAsVariables(ctx, params);

        assertThat(ctx.hasVariable("bareme_irpp_tn")).isFalse();
    }
}
