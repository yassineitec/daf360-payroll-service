package com.daf360.payroll.modules.simulation.dto;

import java.math.BigDecimal;

/**
 * Aggregate result of a cohort simulation — no individual salary figures exposed.
 */
public record CohortAggregateResponse(

        /** Number of employees matched by the filter. */
        int headcount,

        /** Sum of all matched employees' current loadedCost (S5) per month, local currency. */
        BigDecimal currentMonthlyCost,

        /** Sum of projected loadedCost after applying the modifier, per month, local currency. */
        BigDecimal projectedMonthlyCost,

        /** projectedMonthlyCost − currentMonthlyCost. */
        BigDecimal deltaMonthly,

        /** deltaMonthly × 12. */
        BigDecimal deltaAnnual,

        /** currentMonthlyCost in EUR. */
        BigDecimal currentMonthlyCostEur,

        /** projectedMonthlyCost in EUR. */
        BigDecimal projectedMonthlyCostEur,

        /** currentMonthlyCost in CHF. */
        BigDecimal currentMonthlyCostChf,

        /** projectedMonthlyCost in CHF. */
        BigDecimal projectedMonthlyCostChf,

        /** ISO currency code of the local currency for this paysId. */
        String localCurrency,

        /** Applied modifier type: PCT or ABSOLU. */
        String modifierType,

        /** Applied modifier value. */
        BigDecimal modifierValue,

        /** Filters that were applied: grade/discipline/contractType/entite (null = all). */
        CohortFilterRequest appliedFilters
) {}
