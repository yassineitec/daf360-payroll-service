package com.daf360.payroll.modules.salaryadvance.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * Maps [dbo].[salary_advance_policy] (V25) — the rules one entity applies to salary advances.
 * No row, or an inactive one, means the entity offers none. There is deliberately no ceiling
 * on the amount: finance judges it.
 */
@Entity
@Table(name = "salary_advance_policy")
@Getter @Setter
public class SalaryAdvancePolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pays_id", nullable = false)
    private Long paysId;

    /** The entity's payroll currency, copied onto each advance at submission. */
    @Column(name = "currency", nullable = false)
    private String currency;

    /** The employee picks a repayment period from 1 to this many months. */
    @Column(name = "max_installments", nullable = false)
    private Integer maxInstallments;

    /** Months since the hire date. 0 = no condition. */
    @Column(name = "min_seniority_months", nullable = false)
    private Integer minSeniorityMonths = 0;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
