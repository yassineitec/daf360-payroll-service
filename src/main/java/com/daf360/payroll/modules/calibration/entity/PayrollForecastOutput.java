package com.daf360.payroll.modules.calibration.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** F.07 — one of the three forecast outputs auto-created when a ParameterSet is activated. */
@Getter
@Setter
@Entity
@Table(name = "payroll_forecast_outputs")
public class PayrollForecastOutput {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "parameter_set_id", nullable = false)
    private Long parameterSetId;

    @Column(name = "pays_id", nullable = false)
    private Long paysId;

    @Column(name = "period", nullable = false, length = 7)
    private String period;

    /** MONTHLY | QUARTERLY | ANNUAL */
    @Column(name = "forecast_type", nullable = false, length = 20)
    private String forecastType;

    @Column(name = "forecast_amount", nullable = false, precision = 18, scale = 4)
    private BigDecimal forecastAmount;

    @Column(name = "forecast_eur", precision = 18, scale = 4)
    private BigDecimal forecastEur;

    @Column(name = "forecast_chf", precision = 18, scale = 4)
    private BigDecimal forecastChf;

    @Column(name = "local_currency", length = 10)
    private String localCurrency;

    @Column(name = "headcount")
    private Integer headcount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
