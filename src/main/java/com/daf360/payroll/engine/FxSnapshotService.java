package com.daf360.payroll.engine;

import com.daf360.payroll.config.AppProperties;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

@Service
public class FxSnapshotService {

    private final AppProperties appProperties;

    public FxSnapshotService(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    /** ISO code of the local currency (e.g. "TND", "EGP"). Null if not configured. */
    public String localCurrency(Long paysId) {
        return Optional.ofNullable(appProperties.getFxRates())
                .map(m -> m.get(String.valueOf(paysId)))
                .map(AppProperties.FxRateEntry::getCurrency)
                .filter(s -> !s.isBlank())
                .orElse(null);
    }

    /** Rate: how many EUR one local unit is worth (e.g. 1 TND = 0.300 EUR). */
    public BigDecimal eurRate(Long paysId) {
        return Optional.ofNullable(appProperties.getFxRates())
                .map(m -> m.get(String.valueOf(paysId)))
                .map(AppProperties.FxRateEntry::getEur)
                .orElse(null);
    }

    /** Rate: how many USD one local unit is worth (e.g. 1 TND = 0.325 USD). */
    public BigDecimal usdRate(Long paysId) {
        return Optional.ofNullable(appProperties.getFxRates())
                .map(m -> m.get(String.valueOf(paysId)))
                .map(AppProperties.FxRateEntry::getUsd)
                .orElse(null);
    }

    /** Rate: how many CHF one local unit is worth (e.g. 1 TND = 0.316 CHF). */
    public BigDecimal chfRate(Long paysId) {
        return Optional.ofNullable(appProperties.getFxRates())
                .map(m -> m.get(String.valueOf(paysId)))
                .map(AppProperties.FxRateEntry::getChf)
                .orElse(null);
    }

    public BigDecimal convertToEur(BigDecimal localAmount, Long paysId) {
        BigDecimal rate = eurRate(paysId);
        if (rate == null || rate.compareTo(BigDecimal.ZERO) == 0) return null;
        return localAmount.multiply(rate).setScale(4, RoundingMode.HALF_UP);
    }

    public BigDecimal convertToUsd(BigDecimal localAmount, Long paysId) {
        BigDecimal rate = usdRate(paysId);
        if (rate == null || rate.compareTo(BigDecimal.ZERO) == 0) return null;
        return localAmount.multiply(rate).setScale(4, RoundingMode.HALF_UP);
    }

    public BigDecimal convertToChf(BigDecimal localAmount, Long paysId) {
        BigDecimal rate = chfRate(paysId);
        if (rate == null || rate.compareTo(BigDecimal.ZERO) == 0) return null;
        return localAmount.multiply(rate).setScale(4, RoundingMode.HALF_UP);
    }
}
