package com.daf360.payroll.modules.employeeconfig.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "employee_payroll_config_history")
@Getter @Setter
public class EmployeePayrollConfigHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "profile_user_id", nullable = false)
    private Long profileUserId;

    @Column(name = "pays_id", nullable = false)
    private Long paysId;

    @Column(name = "contract_type", nullable = false)
    private String contractType;

    @Column(name = "selected_benefit_codes", columnDefinition = "NVARCHAR(MAX)")
    private String selectedBenefitCodes;

    @Column(name = "current_gross_salary")
    private BigDecimal currentGrossSalary;

    @Column(name = "current_net_salary")
    private BigDecimal currentNetSalary;

    @Column(name = "reason", nullable = false)
    private String reason;

    @Column(name = "changed_by", nullable = false)
    private Long changedBy;

    @Column(name = "changed_at", nullable = false)
    private OffsetDateTime changedAt;

    @Column(name = "input_currency", length = 3)
    private String inputCurrency;

    @Column(name = "input_amount_original")
    private BigDecimal inputAmountOriginal;

    /** Date this salary applies from (V37). Null on rows saved before V37. */
    @Column(name = "effective_date")
    private LocalDate effectiveDate;

    /** Selected benefits WITH their amounts at save time, JSON (V37). Null on older rows. */
    @Column(name = "benefits_snapshot", columnDefinition = "NVARCHAR(MAX)")
    private String benefitsSnapshot;

    /** Country director who approved the change (V38); null for a direct save (first salary). */
    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "approved_at")
    private OffsetDateTime approvedAt;

    @Column(name = "salary_change_request_id")
    private Long salaryChangeRequestId;
}
