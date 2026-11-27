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

    /** Nature of a deduction line: calculators return a positive amount, the sign comes from here. */
    public static final String RETENUE = "RETENUE";
    /** Context variable: strate-5 deductions withheld from the net to pay (salary advance…). */
    public static final String RETENUES_NET = "RETENUES_NET";

    /**
     * Sums each strate's rubrique amounts and stores the totals in the context
     * so that subsequent rubriques may use them as assiette.
     *
     * <p>A {@code RETENUE} line is SUBTRACTED from its strate (an unpaid absence lowers the
     * gross, so the contributions and the tax computed on it). In strate 5 — the taxes —
     * a {@code RETENUE} is not a tax: it is kept out of {@code STRATE_5} and summed in
     * {@link #RETENUES_NET}, which {@code ResultPersister} withholds from the net to pay.
     * No engine rubrique was RETENUE before V41, so every existing payslip is unchanged.
     */
    public void aggregate(List<RubriqueResultItem> results, ExecutionContext context) {
        Map<Integer, BigDecimal> strateTotals = new HashMap<>();
        for (int i = 1; i <= 5; i++) {
            strateTotals.put(i, BigDecimal.ZERO);
        }
        BigDecimal retenuesNet = BigDecimal.ZERO;

        for (RubriqueResultItem item : results) {
            BigDecimal amount = item.getAmount() != null ? item.getAmount() : BigDecimal.ZERO;
            if (RETENUE.equals(item.getNature())) {
                if (item.getStrate() == 5) {
                    retenuesNet = retenuesNet.add(amount);
                    continue;
                }
                amount = amount.negate();
            }
            strateTotals.merge(item.getStrate(), amount, BigDecimal::add);
        }
        context.putVariable(RETENUES_NET, retenuesNet);

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
