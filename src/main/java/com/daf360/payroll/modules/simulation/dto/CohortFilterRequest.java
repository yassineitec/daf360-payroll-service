package com.daf360.payroll.modules.simulation.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Request for cohort aggregate simulation (C2 redesign).
 * Filters a group of active employees then applies a salary modifier to project cost impact.
 * No individual salary data is returned — only aggregate KPIs.
 */
public record CohortFilterRequest(

        @NotNull Long paysId,

        /** Optional employee filters — null means "all". */
        String grade,
        String discipline,
        String contractType,
        String entite,

        /**
         * Modifier type: "PCT" for percentage change (e.g. 5.0 = +5%), "ABSOLU" for fixed amount.
         * If null, defaults to "PCT" with value 0 (no change).
         */
        String modifierType,

        /**
         * Modifier value. For PCT: percentage points (can be negative). For ABSOLU: amount to add/subtract.
         */
        BigDecimal modifierValue
) {}
