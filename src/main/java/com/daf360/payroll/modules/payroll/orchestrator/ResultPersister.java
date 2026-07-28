package com.daf360.payroll.modules.payroll.orchestrator;

import com.daf360.payroll.modules.payroll.calculator.ExecutionContext;
import com.daf360.payroll.modules.payroll.dto.RubriqueResultItem;
import com.daf360.payroll.modules.payroll.entity.PayrollResult;
import com.daf360.payroll.modules.payroll.repository.PayrollResultRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
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
        BigDecimal strate3 = nvl(ctx.getVariable("STRATE_3"));
        result.setAggregateGross(strate1.add(strate2));
        result.setAggregateNet(nvl(ctx.getVariable("STRATE_5")));
        result.setAggregateEmployerCharges(strate3);
        result.setAggregateIrpp(ctx.getVariable("IRPP"));
        result.setLoadedCost(strate1.add(strate2).add(strate3));

        try {
            result.setRubriqueDetails(objectMapper.writeValueAsString(items));
        } catch (Exception e) {
            result.setRubriqueDetails("[]");
        }

        return resultRepo.save(result);
    }

    private BigDecimal nvl(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
