package com.daf360.payroll.modules.salaryadvance.entity;

import java.util.Set;

/**
 * Life of a salary advance (V25, {@code CK_sa_status}).
 *
 * <pre>
 * PENDING_FINANCE ─► APPROVED ─► REPAYING ─► REPAID
 *        │
 *        ├─► REJECTED   (finance declines)
 *        └─► CANCELLED  (the employee withdraws)
 * </pre>
 */
public enum SalaryAdvanceStatus {
    /** Asked by the employee, waiting for finance. */
    PENDING_FINANCE,
    REJECTED,
    /** Finance said yes; payroll has not paid it out yet. */
    APPROVED,
    /** Paid out; the schedule is running. */
    REPAYING,
    REPAID,
    CANCELLED;

    /**
     * Statuses that block a second request: anything still moving, or money still owed. One
     * advance at a time keeps the monthly deduction to a single line per employee.
     */
    public static final Set<SalaryAdvanceStatus> OPEN = Set.of(PENDING_FINANCE, APPROVED, REPAYING);
}
