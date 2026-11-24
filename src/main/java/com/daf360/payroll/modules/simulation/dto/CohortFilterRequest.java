package com.daf360.payroll.modules.simulation.dto;

import com.daf360.payroll.web.StrictJson;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Request for the cohort aggregate / salary what-if ({@code POST /cohort/aggregate}).
 * Selects active employees of a country, applies a change to their salary and reports the
 * cost impact. Fields of the original contract keep their names and meaning (DEC15).
 *
 * <p>{@link StrictJson}: an unknown field is a 400, not silently ignored (A4).
 */
@StrictJson
public record CohortFilterRequest(

        @NotNull Long paysId,

        /** Optional employee filters (HR codes) — null/blank means "all". */
        String grade,
        String discipline,
        String contractType,

        /**
         * Deprecated — never implemented (DEC10). Accepted only when null/blank so that an old
         * client sending {@code "entite": null} keeps working; any value is rejected with 400
         * instead of being silently ignored.
         */
        String entite,

        /**
         * PERCENT (alias PCT, the default) or AMOUNT (alias ABSOLU). Anything else → 400.
         */
        String modifierType,

        /**
         * PERCENT: percentage points, −50 to +200 (DEC3). AMOUNT: amount in the payroll currency
         * added to the chosen base; every resulting salary must stay above 0. Null = 0.
         */
        BigDecimal modifierValue,

        /** NET (default, DEC2) or GROSS: which salary the change is applied to. */
        String salaryBase,

        /** True = return the per-employee rows; requires PAYROLL_VIEW_INDIVIDUAL (else 403). */
        Boolean includeEmployees
) {
    /** The original seven-field shape, kept for callers and tests written against it. */
    public CohortFilterRequest(Long paysId, String grade, String discipline, String contractType,
                               String entite, String modifierType, BigDecimal modifierValue) {
        this(paysId, grade, discipline, contractType, entite, modifierType, modifierValue, null, null);
    }
}
