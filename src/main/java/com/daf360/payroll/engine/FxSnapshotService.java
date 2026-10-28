package com.daf360.payroll.engine;

import com.daf360.payroll.config.AppProperties;
import com.daf360.payroll.modules.payroll.entity.PayrollCountry;
import com.daf360.payroll.modules.payroll.repository.PayrollCountryRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.Optional;

@Service
public class FxSnapshotService {

    private final AppProperties appProperties;
    private final PayrollCountryRepository countryRepo;

    public FxSnapshotService(AppProperties appProperties, PayrollCountryRepository countryRepo) {
        this.appProperties = appProperties;
        this.countryRepo = countryRepo;
    }

    private Optional<PayrollCountry> activeCountry(Long paysId) {
        return countryRepo.findByPaysIdAndActiveTrue(paysId);
    }

    /** ISO code of the local currency (e.g. "TND", "EGP"). DB-configured value wins; falls back
     *  to the static YAML config only when no active payroll_countries row exists for this pays,
     *  or it doesn't have a currency code set. */
    public String localCurrency(Long paysId) {
        return activeCountry(paysId).map(PayrollCountry::getCurrencyCode)
                .filter(s -> s != null && !s.isBlank())
                .orElseGet(() -> Optional.ofNullable(appProperties.getFxRates())
                        .map(m -> m.get(String.valueOf(paysId)))
                        .map(AppProperties.FxRateEntry::getCurrency)
                        .filter(s -> s != null && !s.isBlank())
                        .orElse(null));
    }

    /** Rate: how many EUR one local unit is worth (e.g. 1 TND = 0.300 EUR). DB value wins
     *  per-field — a country row with a currency set but no rate yet still gets a correct rate
     *  from YAML. */
    public BigDecimal eurRate(Long paysId) {
        return activeCountry(paysId).map(PayrollCountry::getEurRate).filter(Objects::nonNull)
                .orElseGet(() -> Optional.ofNullable(appProperties.getFxRates())
                        .map(m -> m.get(String.valueOf(paysId)))
                        .map(AppProperties.FxRateEntry::getEur)
                        .orElse(null));
    }

    /** Rate: how many USD one local unit is worth (e.g. 1 TND = 0.325 USD). DB value wins
     *  per-field — a country row with a currency set but no rate yet still gets a correct rate
     *  from YAML. */
    public BigDecimal usdRate(Long paysId) {
        return activeCountry(paysId).map(PayrollCountry::getUsdRate).filter(Objects::nonNull)
                .orElseGet(() -> Optional.ofNullable(appProperties.getFxRates())
                        .map(m -> m.get(String.valueOf(paysId)))
                        .map(AppProperties.FxRateEntry::getUsd)
                        .orElse(null));
    }

    /** Rate: how many CHF one local unit is worth (e.g. 1 TND = 0.316 CHF). DB value wins
     *  per-field — a country row with a currency set but no rate yet still gets a correct rate
     *  from YAML. */
    public BigDecimal chfRate(Long paysId) {
        return activeCountry(paysId).map(PayrollCountry::getChfRate).filter(Objects::nonNull)
                .orElseGet(() -> Optional.ofNullable(appProperties.getFxRates())
                        .map(m -> m.get(String.valueOf(paysId)))
                        .map(AppProperties.FxRateEntry::getChf)
                        .orElse(null));
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

    /** Immutable snapshot of a pays's currency/rates, resolved once per logical unit of work
     *  (e.g. once per cohort batch, not once per employee) so that every entity produced by
     *  that run is consistent even if an admin edits payroll_countries mid-run. */
    public record FxSnapshot(String currency, BigDecimal eur, BigDecimal usd, BigDecimal chf) {}

    /** Resolves the currency/rates for a pays ONCE. Callers that process many entities for the
     *  same paysId in a single request/loop should call this once and reuse the result via the
     *  {@code FxSnapshot}-based overloads below, instead of calling {@link #localCurrency},
     *  {@link #eurRate}, {@link #usdRate} and {@link #chfRate} per-entity. */
    public FxSnapshot resolve(Long paysId) {
        return new FxSnapshot(localCurrency(paysId), eurRate(paysId), usdRate(paysId), chfRate(paysId));
    }

    public BigDecimal convertToEur(BigDecimal localAmount, FxSnapshot fx) {
        if (fx.eur() == null || fx.eur().compareTo(BigDecimal.ZERO) == 0) return null;
        return localAmount.multiply(fx.eur()).setScale(4, RoundingMode.HALF_UP);
    }

    public BigDecimal convertToUsd(BigDecimal localAmount, FxSnapshot fx) {
        if (fx.usd() == null || fx.usd().compareTo(BigDecimal.ZERO) == 0) return null;
        return localAmount.multiply(fx.usd()).setScale(4, RoundingMode.HALF_UP);
    }

    public BigDecimal convertToChf(BigDecimal localAmount, FxSnapshot fx) {
        if (fx.chf() == null || fx.chf().compareTo(BigDecimal.ZERO) == 0) return null;
        return localAmount.multiply(fx.chf()).setScale(4, RoundingMode.HALF_UP);
    }
}
