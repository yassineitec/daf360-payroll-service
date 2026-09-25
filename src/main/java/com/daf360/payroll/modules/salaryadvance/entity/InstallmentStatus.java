package com.daf360.payroll.modules.salaryadvance.entity;

/**
 * One month of a repayment schedule (V25, {@code CK_sai_status}).
 *
 *  - PLANNED  — expected on that month's payslip.
 *  - DEDUCTED — the payslip carried it; lowers the outstanding balance.
 *  - SKIPPED  — not taken that month. Re-planned one month after the last line, so the total
 *               owed never changes.
 *  - WAIVED   — settled another way (final settlement, cash) or written off.
 */
public enum InstallmentStatus {
    PLANNED,
    DEDUCTED,
    SKIPPED,
    WAIVED
}
