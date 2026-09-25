package com.daf360.payroll.modules.salaryadvance.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;

/**
 * Maps [dbo].[salary_advance_installment] (V25) — one month of a repayment schedule.
 * Generated when payroll records the payout, never before. The month is split into
 * {@code period_month} / {@code period_year}, like {@code employee_payroll_bonus}.
 */
@Entity
@Table(name = "salary_advance_installment")
@Getter @Setter
public class SalaryAdvanceInstallment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "salary_advance_id", nullable = false)
    private Long salaryAdvanceId;

    /** 1-based position. A skipped month's replacement takes the next seq. */
    @Column(name = "seq", nullable = false)
    private Integer seq;

    @Column(name = "period_month", nullable = false)
    private Integer periodMonth;

    @Column(name = "period_year", nullable = false)
    private Integer periodYear;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private InstallmentStatus status = InstallmentStatus.PLANNED;

    @Column(name = "processed_by")
    private Long processedBy;

    @Column(name = "processed_at")
    private OffsetDateTime processedAt;

    @Column(name = "notes")
    private String notes;

    public YearMonth period() {
        return YearMonth.of(periodYear, periodMonth);
    }

    public void setPeriod(YearMonth month) {
        this.periodYear = month.getYear();
        this.periodMonth = month.getMonthValue();
    }
}
