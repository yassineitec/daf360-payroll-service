package com.daf360.payroll.modules.salaryadvance.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Maps [dbo].[salary_advance] (V25) — one avance sur salaire.
 *
 * Asked by the employee from self-service, decided by finance (its cost approval queue), then
 * paid out and recovered by payroll. Every actor is a {@code Users.id} from RH — the same
 * {@code profile_user_id} key as {@code employee_payroll_config}. The trail is in
 * {@link SalaryAdvanceHistory}.
 */
@Entity
@Table(name = "salary_advance")
@Getter @Setter
public class SalaryAdvance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "profile_user_id", nullable = false)
    private Long profileUserId;

    /** The employee's entity, copied at submission so the queues filter without calling RH. */
    @Column(name = "pays_id", nullable = false)
    private Long paysId;

    @Column(name = "currency", nullable = false)
    private String currency;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Column(name = "installments", nullable = false)
    private Integer installments;

    /** First day of the payroll month of the first deduction. */
    @Column(name = "first_deduction_month", nullable = false)
    private LocalDate firstDeductionMonth;

    @Column(name = "reason")
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private SalaryAdvanceStatus status = SalaryAdvanceStatus.PENDING_FINANCE;

    @Column(name = "finance_decided_by")
    private Long financeDecidedBy;

    @Column(name = "finance_decided_at")
    private OffsetDateTime financeDecidedAt;

    @Column(name = "finance_notes")
    private String financeNotes;

    /** ESPECES | VIREMENT | CARTE | AUTRE */
    @Column(name = "disbursement_method")
    private String disbursementMethod;

    @Column(name = "disbursement_reference")
    private String disbursementReference;

    /** The day the money actually left — a business date, entered by payroll. */
    @Column(name = "disbursed_on")
    private LocalDate disbursedOn;

    @Column(name = "disbursed_by")
    private Long disbursedBy;

    @Column(name = "disbursed_at")
    private OffsetDateTime disbursedAt;

    /** Kept in step with the schedule by the service — never summed at read time. */
    @Column(name = "outstanding_amount")
    private BigDecimal outstandingAmount;

    @Column(name = "repaid_at")
    private OffsetDateTime repaidAt;

    @Column(name = "cancelled_at")
    private OffsetDateTime cancelledAt;

    @Column(name = "cancellation_reason")
    private String cancellationReason;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
        if (status == null)    status    = SalaryAdvanceStatus.PENDING_FINANCE;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
