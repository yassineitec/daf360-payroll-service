package com.daf360.payroll.modules.calibration.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "payroll_precision_kpi_history")
@Getter @Setter
public class PrecisionKpiHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "country_id", nullable = false)
    private Long countryId;

    @Column(name = "period", nullable = false, length = 7)
    private String period;

    @Column(name = "precision_pct", nullable = false, precision = 10, scale = 4)
    private BigDecimal precisionPct;

    @Column(name = "threshold_pct", nullable = false, precision = 10, scale = 4)
    private BigDecimal thresholdPct;

    @Column(name = "below_threshold", nullable = false)
    private boolean belowThreshold = false;

    @Column(name = "consecutive_months_below", nullable = false)
    private int consecutiveMonthsBelow = 0;

    @Column(name = "alert_sent", nullable = false)
    private boolean alertSent = false;

    @Column(name = "import_id", nullable = false)
    private Long importId;

    @Column(name = "calculated_at", nullable = false)
    private OffsetDateTime calculatedAt = OffsetDateTime.now();
}
