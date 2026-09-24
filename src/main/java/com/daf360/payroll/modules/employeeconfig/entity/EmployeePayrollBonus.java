package com.daf360.payroll.modules.employeeconfig.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * "Prime exceptionnelle" — a one-off, per-employee bonus. Record only: not fed into
 * PayrollSimulatorService/TopologicalEvaluator (see
 * docs/superpowers/specs/2026-09-23-employee-config-enhancements-design.md, Non-Goals).
 * Added or deleted, never edited in place — no updated_at/updated_by.
 */
@Entity
@Table(name = "employee_payroll_bonus")
@Getter @Setter
public class EmployeePayrollBonus {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "profile_user_id", nullable = false)
    private Long profileUserId;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false)
    private String currency;

    @Column(name = "period_month", nullable = false)
    private Integer periodMonth;

    @Column(name = "period_year", nullable = false)
    private Integer periodYear;

    @Column(name = "label", nullable = false)
    private String label;

    @Column(name = "comment")
    private String comment;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
