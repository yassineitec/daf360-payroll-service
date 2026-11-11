package com.daf360.payroll.modules.employeeconfig.service;

import java.util.Locale;
import java.util.Map;

/**
 * Normalises a contract-type code coming from RH (or an old client) into the code the payroll
 * rules are keyed on ({@code social_charge_rates.contract_type},
 * {@code payroll_rubriques.contract_type_filter}).
 *
 * <p>RH's old onboarding wizard stored English codes ({@code PERMANENT}, {@code FIXED_TERM},
 * {@code INTERN}) that no payroll rule knows: sent as-is they matched no rate, and the net came
 * out with zero social charges. payroll-frontend already mapped them on its side; the
 * Rémunération tab in rh-frontend did not. Doing it here covers every caller.
 */
public final class ContractTypeCodes {

    /** Default when RH has no contract at all — same default as the engine's own run. */
    public static final String DEFAULT = "CDI";

    private static final Map<String, String> LEGACY = Map.of(
            "PERMANENT", "CDI",
            "FIXED_TERM", "CDD",
            "INTERN", "STAGE"
    );

    private ContractTypeCodes() {}

    /** Trimmed, upper-cased, legacy code mapped; {@link #DEFAULT} when blank. */
    public static String normalize(String code) {
        if (code == null || code.isBlank()) return DEFAULT;
        String upper = code.trim().toUpperCase(Locale.ROOT);
        return LEGACY.getOrDefault(upper, upper);
    }
}
