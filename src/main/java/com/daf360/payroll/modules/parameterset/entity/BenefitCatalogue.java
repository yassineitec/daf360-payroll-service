package com.daf360.payroll.modules.parameterset.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "benefits_catalogue")
@Getter @Setter
public class BenefitCatalogue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "parameter_set_id", nullable = false)
    private Long parameterSetId;

    @Column(name = "benefit_code", nullable = false)
    private String benefitCode;  // MEAL|TRANSPORT|HOUSING|SCHOOLING|OTHER

    @Column(name = "benefit_label_fr", nullable = false)
    private String benefitLabelFr;

    @Column(name = "benefit_label_en")
    private String benefitLabelEn;

    @Column(name = "valuation_method", nullable = false)
    private String valuationMethod = "TAX_AUTHORITY";  // TAX_AUTHORITY|ACTUAL_COST

    @Column(name = "monthly_value", nullable = false)
    private BigDecimal monthlyValue = BigDecimal.ZERO;

    @Column(name = "employee_share", nullable = false)
    private BigDecimal employeeShare = BigDecimal.ZERO;

    @Column(name = "employer_share", nullable = false)
    private BigDecimal employerShare = BigDecimal.ZERO;

    @Column(name = "is_taxable", nullable = false)
    private Boolean isTaxable = true;
}
