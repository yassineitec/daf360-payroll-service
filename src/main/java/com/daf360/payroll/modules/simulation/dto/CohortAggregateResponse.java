package com.daf360.payroll.modules.simulation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Result of a cohort simulation (aggregate + salary what-if).
 *
 * <p><b>Compatibility (DEC15).</b> The first thirteen fields are the original contract and keep
 * their names and meaning; everything after them is new and optional. One deliberate change of
 * value, not of meaning: an amount the caller may not see (group below the minimum size for a
 * non-individual role) or that cannot be computed honestly (no converged employee, no FX rate)
 * is now {@code null} instead of {@code 0}.
 *
 * <p><b>Visibility (DEC1).</b> {@code visibility = AGGREGATE} responses never carry
 * {@code employees}; when {@code suppressed} is true they carry no amount at all — only counts.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record CohortAggregateResponse(

        /** Employees whose figures enter the totals (matched − excluded − failed − non-converged). */
        int headcount,

        /** Sum of the included employees' current loaded cost (S5) per month, local currency. */
        BigDecimal currentMonthlyCost,

        /** Sum of their projected loaded cost after the change, per month, local currency. */
        BigDecimal projectedMonthlyCost,

        /** projectedMonthlyCost − currentMonthlyCost. */
        BigDecimal deltaMonthly,

        /** deltaMonthly × 12. */
        BigDecimal deltaAnnual,

        /** currentMonthlyCost in EUR — null when no trustworthy rate exists (DEC7). */
        BigDecimal currentMonthlyCostEur,

        /** projectedMonthlyCost in EUR. */
        BigDecimal projectedMonthlyCostEur,

        /** currentMonthlyCost in CHF. */
        BigDecimal currentMonthlyCostChf,

        /** projectedMonthlyCost in CHF. */
        BigDecimal projectedMonthlyCostChf,

        /** ISO code of the country's payroll currency. */
        String localCurrency,

        /** Modifier type as sent (upper-cased): PCT / ABSOLU (legacy) or PERCENT / AMOUNT. */
        String modifierType,

        /** Modifier value as sent. */
        BigDecimal modifierValue,

        /** The request as received. */
        CohortFilterRequest appliedFilters,

        // ── new, optional ─────────────────────────────────────────────────────────────────

        /** INDIVIDUAL (exact figures, per-employee rows allowed) or AGGREGATE (k-threshold). */
        String visibility,

        /** True when the group is below {@code minGroupSize} for an AGGREGATE caller: no amounts. */
        boolean suppressed,

        /** k — the minimum group size for AGGREGATE callers (configurable, default 5). */
        int minGroupSize,

        /** Employees the filters selected in HR (active workforce of the country). */
        int matched,

        /** Matched employees without a usable HR net salary — not simulated, counted here. */
        int excludedNoSalary,

        /** Matched employees whose computation threw — not summed. */
        int failed,

        /** Matched employees whose engine run did not converge — never summed (DEC4). */
        int nonConverged,

        /** Simulated employees without a payroll configuration: all benefits of the set assumed (DEC5). */
        int benefitsAssumed,

        /** Simulated employees whose current gross came from their payroll configuration (DEC6). */
        int grossFromConfig,

        /**
         * Simulated employees WITH a payroll configuration whose benefit list is NULL or empty:
         * computed without any benefit (DEC5 reading, unchanged), which raises the gross needed
         * for a given net. Message {@code BENEFITS_NONE_SELECTED}.
         */
        int benefitsNoneSelected,

        /**
         * {@code headcount ÷ matched}, 0–1 at scale 4; {@code null} when nothing matched. A ratio
         * of counts, so returned even when the amounts are suppressed.
         */
        BigDecimal coverage,

        /** {@code app.cohort.min-coverage}: below it, message {@code LOW_COVERAGE}. */
        BigDecimal minCoverage,

        Long parameterSetId,
        Integer parameterSetVersion,
        Integer fiscalYear,

        /** When the figures were computed (server clock). */
        OffsetDateTime asOf,

        /** One-line summary of where the EUR / CHF rates came from (DEC7). */
        String fxSource,

        /** Rates and their sources. */
        CohortFx fx,

        /** PERCENT or AMOUNT (canonical form of modifierType). */
        String changeType,

        /** NET (default) or GROSS: what the change is applied to (DEC2). */
        String salaryBase,

        /** Working-day base passed to the engine (DEC9). */
        int workingDays,

        BigDecimal avgNetBefore,
        BigDecimal avgNetAfter,
        BigDecimal avgGrossBefore,
        BigDecimal avgGrossAfter,

        /** deltaMonthly / currentMonthlyCost × 100. */
        BigDecimal deltaPct,

        /** Warnings and explanations for the screen (codes are stable, texts are French). */
        List<CohortMessage> messages,

        /** Per-employee rows — only with includeEmployees=true and PAYROLL_VIEW_INDIVIDUAL. */
        List<EmployeeWhatIfRow> employees
) {

    /** A warning or explanation; {@code code} is what the UI translates. */
    public record CohortMessage(String code, String level, String text) {
        public static CohortMessage warn(String code, String text) { return new CohortMessage(code, "WARNING", text); }
        public static CohortMessage info(String code, String text) { return new CohortMessage(code, "INFO", text); }
        public static CohortMessage error(String code, String text) { return new CohortMessage(code, "ERROR", text); }
    }

    /** Exchange rates used for the EUR / CHF columns: 1 local unit = {@code eurRate} EUR. */
    public record CohortFx(String currency, BigDecimal eurRate, String eurSource,
                           BigDecimal chfRate, String chfSource, OffsetDateTime syncedAt) {}
}
