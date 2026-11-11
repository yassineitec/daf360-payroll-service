package com.daf360.payroll.modules.payroll.orchestrator;

import com.daf360.payroll.modules.payroll.calculator.ExecutionContext;
import com.daf360.payroll.modules.payroll.dto.RubriqueResultItem;
import com.daf360.payroll.modules.payroll.entity.PayrollResult;
import com.daf360.payroll.modules.payroll.repository.PayrollResultRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Assembles the complete PayrollResult entity from context + rubrique breakdown,
 * then persists it in a single INSERT.
 *
 * All fields are set before save() to avoid any UPDATE on the immutable record.
 */
@Service
public class ResultPersister {

    private final PayrollResultRepository resultRepo;
    private final ObjectMapper objectMapper;

    public ResultPersister(PayrollResultRepository resultRepo, ObjectMapper objectMapper) {
        this.resultRepo = resultRepo;
        this.objectMapper = objectMapper;
    }

    /**
     * Builds and persists a complete, immutable PayrollResult in one INSERT.
     *
     * @param ctx          execution context after all rubriques have been computed
     * @param items        per-rubrique breakdown (already computed)
     * @param paramSetId   active parameter set id
     * @param triggeredBy  optional username/system that triggered the run
     * @param convergenceOk  whether Newton-Raphson iterations converged
     * @param iterationsUsed total N-R iterations used (null if not applicable)
     */
    public PayrollResult persist(ExecutionContext ctx,
                                  List<RubriqueResultItem> items,
                                  Long paramSetId,
                                  String triggeredBy,
                                  boolean convergenceOk,
                                  Integer iterationsUsed) {
        return resultRepo.save(build(ctx, items, paramSetId, triggeredBy, convergenceOk, iterationsUsed));
    }

    /**
     * Same assembly as {@link #persist}, without the INSERT — used by
     * {@code PayrollOrchestrator#preview} ("Calculer le net" on the employee's payroll
     * configuration), so the previewed net is computed by exactly the same aggregation as a
     * real payslip.
     */
    public PayrollResult build(ExecutionContext ctx,
                               List<RubriqueResultItem> items,
                               Long paramSetId,
                               String triggeredBy,
                               boolean convergenceOk,
                               Integer iterationsUsed) {
        PayrollResult result = new PayrollResult();
        result.setEmployeeId(ctx.getEmployeeId());
        result.setCountryId(ctx.getCountryId());
        result.setPaysId(ctx.getPaysId());
        result.setPeriodYear(ctx.getPeriodYear());
        result.setPeriodMonth(ctx.getPeriodMonth());
        result.setParameterSetId(paramSetId);
        result.setTriggeredBy(triggeredBy);
        result.setCalculatedAt(OffsetDateTime.now());
        result.setConvergenceOk(convergenceOk);
        result.setIterationsUsed(iterationsUsed);

        // Strate aggregates — read from context after all rubriques are computed
        result.setStrate1(ctx.getVariable("STRATE_1"));
        result.setStrate2(ctx.getVariable("STRATE_2"));
        result.setStrate3(ctx.getVariable("STRATE_3"));
        result.setStrate4(ctx.getVariable("STRATE_4"));
        result.setStrate5(ctx.getVariable("STRATE_5"));

        BigDecimal strate1 = nvl(ctx.getVariable("STRATE_1"));
        BigDecimal strate2 = nvl(ctx.getVariable("STRATE_2"));
        BigDecimal gross = strate1.add(strate2);

        // Employee/employer COTISATION totals come from the rubriques actually computed
        // this run, filtered by `payer` — NOT from CHARGES_ER (the old-model charge
        // bridge's injected variable). The bridge still runs (PayrollOrchestrator#
        // injectChargeVariables, best-effort) but nothing here reads its output anymore:
        // every country's own CNSS/TFP/FOPROLOS/AT/SI/SHIF rubriques are now summed
        // directly, so a country-specific charge (TFP, FOPROLOS — no old-model
        // equivalent at all) is no longer silently dropped from the total.
        BigDecimal employeeCharges = sumByNatureAndPayer(items, "COTISATION", "EMPLOYEE");
        BigDecimal employerCharges = sumByNatureAndPayer(items, "COTISATION", "EMPLOYER");

        // Taxes (IRPP/CSS/INCOME_TAX) are strate 5 by construction — STRATE_4 is the new
        // intermediate assiette they read, never itself a payable amount.
        BigDecimal taxes = nvl(ctx.getVariable("STRATE_5"));

        result.setAggregateGross(gross);
        result.setAggregateNet(gross.subtract(employeeCharges).subtract(taxes));
        result.setAggregateEmployerCharges(employerCharges);
        result.setAggregateIrpp(ctx.getVariable("IRPP"));
        result.setLoadedCost(gross.add(employerCharges));

        try {
            result.setRubriqueDetails(objectMapper.writeValueAsString(items));
        } catch (Exception e) {
            result.setRubriqueDetails("[]");
        }

        return result;
    }

    private BigDecimal sumByNatureAndPayer(List<RubriqueResultItem> items, String nature, String payer) {
        return items.stream()
            .filter(i -> nature.equals(i.getNature()) && payer.equals(i.getPayer()))
            .map(RubriqueResultItem::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal nvl(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
