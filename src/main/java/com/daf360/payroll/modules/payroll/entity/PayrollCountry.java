package com.daf360.payroll.modules.payroll.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "payroll_countries")
@Getter @Setter
public class PayrollCountry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pays_id", nullable = false, unique = true)
    private Long paysId;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Column(name = "eur_rate", precision = 18, scale = 6)
    private java.math.BigDecimal eurRate;

    @Column(name = "usd_rate", precision = 18, scale = 6)
    private java.math.BigDecimal usdRate;

    @Column(name = "chf_rate", precision = 18, scale = 6)
    private java.math.BigDecimal chfRate;

    @Column(name = "fiscal_year_start_month", nullable = false)
    private int fiscalYearStartMonth = 1;

    @Column(name = "forex_api_sources", columnDefinition = "NVARCHAR(MAX)")
    private String forexApiSources;

    @Column(name = "active", nullable = false)
    private boolean active = false;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
