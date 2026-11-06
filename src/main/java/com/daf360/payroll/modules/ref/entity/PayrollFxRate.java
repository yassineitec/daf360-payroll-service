package com.daf360.payroll.modules.ref.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Column;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Local, fully-rebuilt-on-sync cache of finance's RATE_EUR_<CODE> rows (see
 *  ProfileSyncService.syncFxRates). Never edited by hand -- finance's parameter_sets
 *  stays the one place anyone maintains a rate. */
@Entity
@Table(name = "payroll_fx_rates")
@Getter @Setter
public class PayrollFxRate {

    @Id
    @Column(name = "currency_code", length = 3)
    private String currencyCode;

    @Column(name = "rate_to_eur", nullable = false)
    private BigDecimal rateToEur;

    @Column(name = "synced_at", nullable = false)
    private LocalDateTime syncedAt = LocalDateTime.now();
}
