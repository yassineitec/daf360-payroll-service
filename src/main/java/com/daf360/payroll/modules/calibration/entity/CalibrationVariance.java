package com.daf360.payroll.modules.calibration.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "calibration_variances")
@Getter @Setter
public class CalibrationVariance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cycle_id", nullable = false)
    private Long cycleId;

    @Column(name = "profile_user_id", nullable = false)
    private Long profileUserId;

    @Column(name = "predicted_loaded_cost", nullable = false)
    private BigDecimal predictedLoadedCost;

    @Column(name = "actual_loaded_cost", nullable = false)
    private BigDecimal actualLoadedCost;

    @Column(name = "variance_amount", nullable = false)
    private BigDecimal varianceAmount;

    @Column(name = "variance_pct", nullable = false)
    private BigDecimal variancePct;

    @Column(name = "contract_type")
    private String contractType;

    @Column(name = "source_line")
    private Integer sourceLine;  // row in partner CSV

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
