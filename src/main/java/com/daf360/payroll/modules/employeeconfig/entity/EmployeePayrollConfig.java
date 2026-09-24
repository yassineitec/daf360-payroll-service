package com.daf360.payroll.modules.employeeconfig.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "employee_payroll_config")
@Getter @Setter
public class EmployeePayrollConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "profile_user_id", nullable = false, unique = true)
    private Long profileUserId;

    @Column(name = "pays_id", nullable = false)
    private Long paysId;

    @Column(name = "contract_type", nullable = false)
    private String contractType;  // CDI|CDD|CIVP|STAGE|FREELANCE|DETACHEMENT (daf360-rh-service's real domain)

    @Column(name = "selected_benefit_codes", columnDefinition = "NVARCHAR(MAX)")
    private String selectedBenefitCodes;  // JSON array, null = none selected

    @Column(name = "current_gross_salary")
    private BigDecimal currentGrossSalary;

    @Column(name = "current_net_salary")
    private BigDecimal currentNetSalary;

    @Column(name = "updated_by", nullable = false)
    private Long updatedBy;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
