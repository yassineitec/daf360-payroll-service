package com.daf360.payroll.modules.payroll.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "payroll_results")
@Getter @Setter
public class PayrollResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "country_id", nullable = false)
    private Long countryId;

    @Column(name = "pays_id", nullable = false)
    private Long paysId;

    @Column(name = "period_year", nullable = false)
    private int periodYear;

    @Column(name = "period_month", nullable = false)
    private int periodMonth;

    @Column(name = "parameter_set_id", nullable = false)
    private Long parameterSetId;

    @Column(name = "calculated_at", nullable = false)
    private OffsetDateTime calculatedAt = OffsetDateTime.now();

    @Column(name = "triggered_by", length = 100)
    private String triggeredBy;

    @Column(name = "rubrique_details", columnDefinition = "NVARCHAR(MAX)", nullable = false)
    private String rubriqueDetails;

    @Column(name = "strate_1", precision = 15, scale = 2)
    private BigDecimal strate1;

    @Column(name = "strate_2", precision = 15, scale = 2)
    private BigDecimal strate2;

    @Column(name = "strate_3", precision = 15, scale = 2)
    private BigDecimal strate3;

    @Column(name = "strate_4", precision = 15, scale = 2)
    private BigDecimal strate4;

    @Column(name = "strate_5", precision = 15, scale = 2)
    private BigDecimal strate5;

    @Column(name = "aggregate_gross", precision = 15, scale = 2)
    private BigDecimal aggregateGross;

    @Column(name = "aggregate_employer_charges", precision = 15, scale = 2)
    private BigDecimal aggregateEmployerCharges;

    @Column(name = "aggregate_net", precision = 15, scale = 2)
    private BigDecimal aggregateNet;

    @Column(name = "aggregate_irpp", precision = 15, scale = 2)
    private BigDecimal aggregateIrpp;

    @Column(name = "loaded_cost", precision = 15, scale = 2)
    private BigDecimal loadedCost;

    @Column(name = "forex_snapshot_json", columnDefinition = "NVARCHAR(MAX)")
    private String forexSnapshotJson;

    @Column(name = "convergence_ok", nullable = false)
    private boolean convergenceOk = true;

    @Column(name = "iterations_used")
    private Integer iterationsUsed;
}
