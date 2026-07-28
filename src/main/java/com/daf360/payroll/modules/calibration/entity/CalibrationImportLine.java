package com.daf360.payroll.modules.calibration.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "payroll_calibration_lines")
@Getter @Setter
public class CalibrationImportLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "import_id", nullable = false)
    private Long importId;

    @Column(name = "rubrique_code", nullable = false, length = 20)
    private String rubriqueCode;

    @Column(name = "predicted_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal predictedAmount;

    @Column(name = "actual_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal actualAmount;

    @Column(name = "variance_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal varianceAmount;

    @Column(name = "variance_pct", nullable = false, precision = 10, scale = 4)
    private BigDecimal variancePct;

    @Column(name = "exceeds_threshold", nullable = false)
    private boolean exceedsThreshold = false;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
