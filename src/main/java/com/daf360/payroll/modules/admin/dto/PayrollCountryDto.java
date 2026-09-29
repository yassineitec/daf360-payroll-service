package com.daf360.payroll.modules.admin.dto;

import java.time.OffsetDateTime;

/**
 * One row of {@code payroll_countries} — the engine's per-country set-up — with the country's
 * label and ISO code from {@code pays_ref}, for the « Pays de paie » admin card.
 */
public record PayrollCountryDto(
        Long id,
        Long paysId,
        String paysLabel,
        String isoCode,
        String currencyCode,
        int fiscalYearStartMonth,
        String forexApiSources,
        boolean active,
        OffsetDateTime createdAt
) {}
