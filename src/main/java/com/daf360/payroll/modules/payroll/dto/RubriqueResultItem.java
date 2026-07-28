package com.daf360.payroll.modules.payroll.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Detailed per-rubrique result included in RunPayrollResponse.rubriqueDetails.
 */
@Getter
@Setter
@Builder
public class RubriqueResultItem {

    /** Rubrique code (e.g. "SALAIRE_BASE"). */
    private String rubriqueCode;

    /** French label for display in payslips. */
    private String labelFr;

    /** Nature of the rubrique: GAIN | RETENUE | COTISATION | TAXE | AVANTAGE */
    private String nature;

    /** Strate (calculation tier) to which this rubrique belongs (1–5). */
    private int strate;

    /** Resolved assiette (base) that was passed into the calculator. */
    private BigDecimal assiette;

    /** Final computed amount (always positive; sign applied by strate aggregation). */
    private BigDecimal amount;

    /** Mode of calculation used (e.g. "TAUX_PCT", "BAREME_PROGRESSIF"). */
    private String modeCalcul;

    /** True if a pro-rata reduction was applied to this rubrique. */
    private Boolean prorataApplied;

    /**
     * Number of Newton-Raphson iterations used, or null if the rubrique
     * did not require iterative convergence.
     */
    private Integer iterationsUsed;
}
