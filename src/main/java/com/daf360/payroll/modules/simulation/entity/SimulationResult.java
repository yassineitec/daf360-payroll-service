package com.daf360.payroll.modules.simulation.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "simulation_results")
@Getter @Setter
public class SimulationResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pays_id", nullable = false)
    private Long paysId;

    @Column(name = "profile_user_id")
    private Long profileUserId;

    @Column(name = "parameter_set_id", nullable = false)
    private Long parameterSetId;

    @Column(name = "simulation_type", nullable = false)
    private String simulationType;  // INDIVIDUAL|COHORT

    @Column(name = "contract_type", nullable = false)
    private String contractType = "CDI";

    @Column(name = "input_net", nullable = false)
    private BigDecimal inputNet;

    @Column(name = "net_taxable", nullable = false)
    private BigDecimal netTaxable;

    @Column(name = "taxable_base", nullable = false)
    private BigDecimal taxableBase;

    @Column(name = "gross", nullable = false)
    private BigDecimal gross;

    @Column(name = "loaded_cost", nullable = false)
    private BigDecimal loadedCost;

    @Column(name = "loaded_cost_eur")
    private BigDecimal loadedCostEur;

    @Column(name = "loaded_cost_usd")
    private BigDecimal loadedCostUsd;

    @Column(name = "loaded_cost_chf")
    private BigDecimal loadedCostChf;

    @Column(name = "fx_rate_eur")
    private BigDecimal fxRateEur;

    @Column(name = "fx_rate_usd")
    private BigDecimal fxRateUsd;

    @Column(name = "fx_rate_chf")
    private BigDecimal fxRateChf;

    @Column(name = "local_currency", length = 10)
    private String localCurrency;

    @Column(name = "gross_with_benefits")
    private BigDecimal grossWithBenefits;

    @Column(name = "cost_net_ratio")
    private BigDecimal costNetRatio;

    @Column(name = "candidate_label", length = 200)
    private String candidateLabel;

    @Column(name = "poste", length = 200)
    private String poste;

    @Column(name = "grade", length = 100)
    private String grade;

    @Column(name = "discipline", length = 100)
    private String discipline;

    @Column(name = "irpp_amount")
    private BigDecimal irppAmount;

    @Column(name = "employee_charges")
    private BigDecimal employeeCharges;

    @Column(name = "employer_charges")
    private BigDecimal employerCharges;

    @Column(name = "benefits_applied")
    private String benefitsApplied;  // JSON

    @Column(name = "rubriques_applied")
    private String rubriquesApplied;  // JSON

    @Column(name = "iterations_used", nullable = false)
    private Integer iterationsUsed = 0;

    @Column(name = "convergence_ok", nullable = false)
    private Boolean convergenceOk = true;

    @Column(name = "cohort_id")
    private Long cohortId;

    /** Direction used: NET_TO_BRUT (default, classic) or BRUT_TO_NET (single-pass). */
    @Column(name = "mode", nullable = false, length = 20)
    private String mode = "NET_TO_BRUT";

    @Column(name = "simulated_by", nullable = false)
    private Long simulatedBy;

    @Column(name = "simulated_at", nullable = false)
    private OffsetDateTime simulatedAt = OffsetDateTime.now();
}
