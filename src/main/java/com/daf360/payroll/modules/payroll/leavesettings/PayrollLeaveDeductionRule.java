package com.daf360.payroll.modules.payroll.leavesettings;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * Maps [dbo].[payroll_leave_deduction_rules] (V42) — whether an approved day of one RH leave
 * type ({@code AbsenceTypes.code}) is withheld from the salary in one country (HR pays id).
 * No row = paid.
 */
@Entity
@Table(name = "payroll_leave_deduction_rules")
@Getter @Setter
public class PayrollLeaveDeductionRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pays_id", nullable = false)
    private Long paysId;

    @Column(name = "absence_type_code", nullable = false, length = 64)
    private String absenceTypeCode;

    @Column(name = "deducts_salary", nullable = false)
    private Boolean deductsSalary = false;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist @PreUpdate
    void touch() {
        updatedAt = OffsetDateTime.now();
    }
}
