package com.daf360.payroll.modules.simulation.dto;

import com.daf360.payroll.modules.simulation.dto.CohortAggregateResponse.CohortFx;
import com.daf360.payroll.modules.simulation.dto.CohortAggregateResponse.CohortMessage;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * One employee's what-if: current vs projected figures (five strata + charges), unrounded,
 * with the parameter set, working days and FX used. Nothing is persisted.
 */
public record EmployeeWhatIfResponse(
        EmployeeWhatIfRow employee,
        String localCurrency,
        String changeType,
        BigDecimal modifierValue,
        String salaryBase,
        Long parameterSetId,
        Integer parameterSetVersion,
        Integer fiscalYear,
        int workingDays,
        OffsetDateTime asOf,
        String fxSource,
        CohortFx fx,
        BigDecimal currentLoadedCostEur,
        BigDecimal projectedLoadedCostEur,
        BigDecimal currentLoadedCostChf,
        BigDecimal projectedLoadedCostChf,
        /** Benefit codes the engine applied (the employee's own selection, or all when assumed). */
        List<String> benefitCodes,
        List<CohortMessage> messages
) {}
