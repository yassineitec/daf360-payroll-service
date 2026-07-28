package com.daf360.payroll.modules.calibration.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "calibration_cycles",
       uniqueConstraints = @UniqueConstraint(columnNames = {"pays_id", "period"}))
@Getter @Setter
public class CalibrationCycle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pays_id", nullable = false)
    private Long paysId;

    @Column(name = "period", nullable = false, length = 7)  // YYYY-MM
    private String period;

    @Column(name = "parameter_set_id", nullable = false)
    private Long parameterSetId;

    @Column(name = "status", nullable = false)
    private String status = "OPEN";  // OPEN|CLOSED|REQUIRES_UPDATE

    @Column(name = "predicted_total_loaded_cost")
    private BigDecimal predictedTotalLoadedCost;

    @Column(name = "actual_total_loaded_cost")
    private BigDecimal actualTotalLoadedCost;

    @Column(name = "variance_pct")
    private BigDecimal variancePct;

    @Column(name = "headcount")
    private Integer headcount;

    @Column(name = "closed_at")
    private OffsetDateTime closedAt;

    @Column(name = "closed_by")
    private Long closedBy;

    @Column(name = "notes")
    private String notes;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
