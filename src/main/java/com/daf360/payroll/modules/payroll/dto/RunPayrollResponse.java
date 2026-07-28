package com.daf360.payroll.modules.payroll.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Response returned by the payroll run endpoint.
 * Contains both the high-level strate aggregates and the full rubrique breakdown.
 */
@Getter
@Setter
@Builder
public class RunPayrollResponse {

    /** Persisted PayrollResult.id, null if run was a dry-run (not persisted). */
    private Long resultId;

    private Long employeeId;
    private int periodYear;
    private int periodMonth;

    /** ID of the PayrollParamSet that was active for this period. */
    private Long parameterSetId;

    // ── Strate aggregates ────────────────────────────────────────────────────

    /** Strate 1 — Salaire brut (total gross earnings). */
    private BigDecimal strate1;

    /** Strate 2 — Avantages en nature et indemnités. */
    private BigDecimal strate2;

    /** Strate 3 — Charges salariales (employee social contributions). */
    private BigDecimal strate3;

    /** Strate 4 — Net imposable (taxable base after employee contributions). */
    private BigDecimal strate4;

    /** Strate 5 — Net à payer (after IRPP and other deductions). */
    private BigDecimal strate5;

    // ── Cross-strate KPIs ────────────────────────────────────────────────────

    /** Total gross (strate1 + strate2). */
    private BigDecimal aggregateGross;

    /** Total employer charges (social + patronal contributions). */
    private BigDecimal aggregateEmployerCharges;

    /** Net salary paid to the employee. */
    private BigDecimal aggregateNet;

    /** IRPP (income tax) amount withheld. */
    private BigDecimal aggregateIrpp;

    /** Total loaded cost: gross + employer charges. */
    private BigDecimal loadedCost;

    // ── Convergence metadata ─────────────────────────────────────────────────

    /** True if all iterative (NEWTON_RAPHSON) rubriques converged within tolerance. */
    private boolean convergenceOk;

    /** Total Newton-Raphson iterations used across all rubriques, or null if none. */
    private Integer iterationsUsed;

    /** Timestamp when the calculation was performed. */
    private OffsetDateTime calculatedAt;

    // ── Per-rubrique breakdown ───────────────────────────────────────────────

    /** Full line-by-line rubrique details in strate + displayOrder sequence. */
    private List<RubriqueResultItem> rubriqueDetails;
}
