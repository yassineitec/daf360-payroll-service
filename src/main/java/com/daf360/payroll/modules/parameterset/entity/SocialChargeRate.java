package com.daf360.payroll.modules.parameterset.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "social_charge_rates")
@Getter @Setter
public class SocialChargeRate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "parameter_set_id", nullable = false)
    private Long parameterSetId;

    @Column(name = "contract_type", nullable = false)
    private String contractType;  // CDI|CDD|STAGE|CIVP

    @Column(name = "charge_code", nullable = false)
    private String chargeCode;

    @Column(name = "charge_label", nullable = false)
    private String chargeLabel;

    @Column(name = "employee_rate", nullable = false)
    private BigDecimal employeeRate = BigDecimal.ZERO;

    @Column(name = "employer_rate", nullable = false)
    private BigDecimal employerRate = BigDecimal.ZERO;

    @Column(name = "base_calculation", nullable = false)
    private String baseCalculation = "GROSS";  // GROSS|CAPPED_GROSS|FIXED

    @Column(name = "cap_amount")
    private BigDecimal capAmount;
}
