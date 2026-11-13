package com.daf360.payroll.modules.salaryadvance.entity;

import java.util.Set;

/**
 * Life of a salary advance (V25, {@code CK_sa_status}; PENDING_DIRECTOR since V39).
 *
 * <pre>
 * PENDING_DIRECTOR ─► PENDING_FINANCE ─► APPROVED ─► REPAYING ─► REPAID
 *   (above the           │                (terms set,   (payout
 *    country threshold)  │                 schedule,     confirmed)
 *        │               │                 contract)
 *        ├─► REJECTED ◄──┤
 *        └─► CANCELLED ◄─┘  (the employee withdraws before any decision)
 * </pre>
 */
public enum SalaryAdvanceStatus {
    /** Above the country threshold: waiting for a holder of the threshold code. */
    PENDING_DIRECTOR,
    /** « En attente de traitement »: waiting for the DAF. */
    PENDING_FINANCE,
    REJECTED,
    /** Granted with its final terms; schedule built, contract generated; not paid out yet. */
    APPROVED,
    /** Paid out; the schedule is running. */
    REPAYING,
    REPAID,
    CANCELLED;

    /**
     * Statuses that block a second request: anything still moving, or money still owed. One
     * advance at a time keeps the monthly deduction to a single line per employee.
     */
    public static final Set<SalaryAdvanceStatus> OPEN = Set.of(PENDING_DIRECTOR, PENDING_FINANCE, APPROVED, REPAYING);

    /** Not decided yet — the employee may still withdraw. */
    public static final Set<SalaryAdvanceStatus> UNDECIDED = Set.of(PENDING_DIRECTOR, PENDING_FINANCE);
}
