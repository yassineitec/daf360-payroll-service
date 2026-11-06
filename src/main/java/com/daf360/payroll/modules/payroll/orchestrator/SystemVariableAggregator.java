package com.daf360.payroll.modules.payroll.orchestrator;

import com.daf360.payroll.modules.payroll.calculator.ExecutionContext;
import com.daf360.payroll.modules.payroll.dto.RubriqueResultItem;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes strate_1 through strate_5 aggregates from per-rubrique results and
 * populates them back into the execution context and the final PayrollResult entity.
 */
@Service
public class SystemVariableAggregator {

    /**
     * Sums each strate's rubrique amounts and stores the totals in the context
     * so that subsequent rubriques may use them as assiette.
     */
    public void aggregate(List<RubriqueResultItem> results, ExecutionContext context) {
        Map<Integer, BigDecimal> strateTotals = new HashMap<>();
        for (int i = 1; i <= 5; i++) {
            strateTotals.put(i, BigDecimal.ZERO);
        }

        for (RubriqueResultItem item : results) {
            strateTotals.merge(item.getStrate(), item.getAmount(), BigDecimal::add);
        }

        // Populate strate variables in context for subsequent rubriques
        strateTotals.forEach((strate, total) ->
            context.putVariable("STRATE_" + strate, total)
        );

        // Also expose named aliases
        context.putVariable("BRUT",          strateTotals.get(1));
        context.putVariable("AVANTAGES",     strateTotals.get(2));
        context.putVariable("CHARGES_SAL",   strateTotals.get(3));
        context.putVariable("NET_IMPOSABLE", strateTotals.get(4));
        context.putVariable("NET_A_PAYER",   strateTotals.get(5));
    }
}
