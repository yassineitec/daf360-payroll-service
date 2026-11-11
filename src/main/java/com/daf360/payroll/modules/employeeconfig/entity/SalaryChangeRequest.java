package com.daf360.payroll.modules.employeeconfig.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** A salary change waiting for (or past) the country director's decision — V38. */
@Entity
@Table(name = "salary_change_requests")
@Getter @Setter
public class SalaryChangeRequest {

    public static final String PENDING   = "PENDING";
    public static final String APPROVED  = "APPROVED";
    public static final String REJECTED  = "REJECTED";
    public static final String CANCELLED = "CANCELLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "profile_user_id", nullable = false)
    private Long profileUserId;

    @Column(name = "pays_id", nullable = false)
    private Long paysId;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "contract_type", nullable = false, length = 50)
    private String contractType;

    @Column(name = "selected_benefit_codes", columnDefinition = "NVARCHAR(MAX)")
    private String selectedBenefitCodes;

    /** As typed, in {@link #inputCurrency} (null = working currency). */
    @Column(name = "gross_salary", nullable = false)
    private BigDecimal grossSalary;

    @Column(name = "input_currency", length = 3)
    private String inputCurrency;

    @Column(name = "effective_date")
    private LocalDate effectiveDate;

    @Column(name = "reason", nullable = false, length = 1000)
    private String reason;

    @Column(name = "working_currency", length = 3)
    private String workingCurrency;

    @Column(name = "current_gross_salary")
    private BigDecimal currentGrossSalary;

    @Column(name = "current_net_salary")
    private BigDecimal currentNetSalary;

    @Column(name = "new_gross_salary")
    private BigDecimal newGrossSalary;

    @Column(name = "new_net_salary")
    private BigDecimal newNetSalary;

    @Column(name = "monthly_cost_before")
    private BigDecimal monthlyCostBefore;

    @Column(name = "monthly_cost_after")
    private BigDecimal monthlyCostAfter;

    @Column(name = "simulation_snapshot", columnDefinition = "NVARCHAR(MAX)")
    private String simulationSnapshot;

    @Column(name = "submitted_by", nullable = false)
    private Long submittedBy;

    @Column(name = "submitted_at", nullable = false)
    private OffsetDateTime submittedAt;

    @Column(name = "decided_by")
    private Long decidedBy;

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;

    @Column(name = "decision_notes", length = 1000)
    private String decisionNotes;
}
