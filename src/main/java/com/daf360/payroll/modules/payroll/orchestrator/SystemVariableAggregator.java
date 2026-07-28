package com.daf360.payroll.modules.payroll.orchestrator;

import com.daf360.payroll.modules.payroll.calculator.ExecutionContext;
import com.daf360.payroll.modules.payroll.dto.RubriqueResultItem;
import com.daf360.payroll.modules.payroll.entity.PayrollResult;
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

    /**
     * Writes the final strate totals and aggregate KPIs into the PayrollResult entity.
     * The entity must already be managed (i.e. saved within the current transaction)
     * so that changes are tracked by JPA dirty-checking.
     */
    public void populateResult(PayrollResult result,
                                List<RubriqueResultItem> items,
                                ExecutionContext context) {
        BigDecimal strate1 = context.getVariable("STRATE_1");
        BigDecimal strate2 = context.getVariable("STRATE_2");
        BigDecimal strate3 = context.getVariable("STRATE_3");
        BigDecimal strate4 = context.getVariable("STRATE_4");
        BigDecimal strate5 = context.getVariable("STRATE_5");

        result.setStrate1(strate1);
        result.setStrate2(strate2);
        result.setStrate3(strate3);
        result.setStrate4(strate4);
        result.setStrate5(strate5);

        result.setAggregateGross(strate1.add(strate2));
        result.setAggregateNet(strate5);
        // Strate 3 represents employee charges; used as employer charges proxy
        result.setAggregateEmployerCharges(strate3);
        // IRPP is set by the IRPP rubrique and stored in context under "IRPP"
        result.setAggregateIrpp(context.getVariable("IRPP"));
        result.setLoadedCost(strate1.add(strate2).add(strate3));
    }
}
