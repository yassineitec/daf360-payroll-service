package com.daf360.payroll.modules.parameterset.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "parameter_sets")
@Getter @Setter
public class ParameterSet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pays_id", nullable = false)
    private Long paysId;

    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "fiscal_year", nullable = false)
    private Integer fiscalYear;

    @Column(name = "status", nullable = false)
    private String status = "DRAFT";  // DRAFT|PENDING_FINANCE|ACTIVE|ARCHIVED

    @Column(name = "irpp_brackets", columnDefinition = "NVARCHAR(MAX)", nullable = false)
    private String irppBrackets;  // JSON: [{min, max, rate}]

    @Column(name = "convergence_tolerance", nullable = false)
    private BigDecimal convergenceTolerance = new BigDecimal("0.01");

    @Column(name = "max_convergence_iterations", nullable = false)
    private Integer maxConvergenceIterations = 50;

    @Column(name = "calibration_threshold_pct", nullable = false)
    private BigDecimal calibrationThresholdPct = new BigDecimal("1.00");

    /** Professional-expense deduction applied to the annual taxable base before IRPP brackets
     *  (e.g. 0.10 for 10%). 0 = no abattement, matching every parameter set created before
     *  this field existed. */
    @Column(name = "abattement_rate_pct", nullable = false)
    private BigDecimal abattementRatePct = BigDecimal.ZERO;

    /** Annual cap on the abattement amount; null = uncapped. */
    @Column(name = "abattement_annual_cap")
    private BigDecimal abattementAnnualCap;

    /** Annual IRPP-base deduction when the employee is married. 0 = none. */
    @Column(name = "family_deduction_married_amount", nullable = false)
    private BigDecimal familyDeductionMarriedAmount = BigDecimal.ZERO;

    /** Annual IRPP-base deduction per dependent child, up to {@link #familyDeductionMaxChildren}. */
    @Column(name = "family_deduction_per_child_amount", nullable = false)
    private BigDecimal familyDeductionPerChildAmount = BigDecimal.ZERO;

    /** Maximum number of children counted toward the per-child deduction. 0 = none counted. */
    @Column(name = "family_deduction_max_children", nullable = false)
    private Integer familyDeductionMaxChildren = 0;

    @Column(name = "approved_by_hr")
    private Long approvedByHr;

    @Column(name = "approved_by_finance")
    private Long approvedByFinance;

    @Column(name = "approved_at")
    private OffsetDateTime approvedAt;

    @Column(name = "activated_at")
    private OffsetDateTime activatedAt;

    @Column(name = "change_rationale")
    private String changeRationale;

    @Column(name = "previous_version_id")
    private Long previousVersionId;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
