package com.daf360.payroll.modules.employeeconfig.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
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
}
