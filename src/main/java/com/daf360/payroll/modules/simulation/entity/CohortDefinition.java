package com.daf360.payroll.modules.simulation.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "cohort_definitions")
@Getter @Setter
public class CohortDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pays_id", nullable = false)
    private Long paysId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "fiscal_year", nullable = false)
    private Integer fiscalYear;

    @Column(name = "parameter_set_id", nullable = false)
    private Long parameterSetId;

    @Column(name = "status", nullable = false)
    private String status = "DRAFT";  // DRAFT|VALIDATED|ARCHIVED

    @Column(name = "total_loaded_cost")
    private BigDecimal totalLoadedCost;

    @Column(name = "total_headcount")
    private Integer totalHeadcount;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
