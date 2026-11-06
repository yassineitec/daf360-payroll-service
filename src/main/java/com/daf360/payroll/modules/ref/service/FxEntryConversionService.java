package com.daf360.payroll.modules.ref.service;

import com.daf360.payroll.modules.ref.repository.PayrollFxRateRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * Converts a simulator-entered amount from any currency payroll_fx_rates has a cached rate for
 * into the country's working currency, composing through EUR (every cached rate is relative to
 * EUR, mirroring finance's own RATE_EUR_<CODE> convention). Used ONLY at simulation-request
 * entry, before PayrollSimulatorService is called -- the engine itself never sees a currency
 * other than the country's working one.
 */
@Service
public class FxEntryConversionService {

    /**
     * Countries whose platform "working currency" (pays_ref.devise) is a deliberate accounting
     * label, not the real currency its payroll amounts are expressed in: Tunisia's rates were
     * calibrated so a number entered with no conversion already IS the real TND figure, under
     * the label "EUR"; Egypt the same, real EGP under the label "USD". Picking the country's own
     * real currency in an entry-currency picker must be a no-op, not a real market-rate
     * conversion against a number that was never actually foreign.
     */
    private static final Map<Long, String> NATIVE_CURRENCY_BY_PAYS_ID = Map.of(
            179L, "TND", // Tunisie
            53L, "EGP"   // Egypte
    );

    private final PayrollFxRateRepository fxRateRepo;

    public FxEntryConversionService(PayrollFxRateRepository fxRateRepo) {
        this.fxRateRepo = fxRateRepo;
    }

    /** True when {@code currencyCode} is {@code paysId}'s own real currency per {@link
     *  #NATIVE_CURRENCY_BY_PAYS_ID}, not a currency genuinely foreign to it. */
    public boolean isNativeCurrency(Long paysId, String currencyCode) {
        return currencyCode != null
                && currencyCode.equalsIgnoreCase(NATIVE_CURRENCY_BY_PAYS_ID.get(paysId));
    }

    /**
     * @param amount        the amount as entered, in {@code fromCurrency}
     * @param fromCurrency  the currency the user actually typed the amount in
     * @param toCurrency    the country's working currency (what the engine must receive)
     * @throws FxEntryConversionException if either currency has no cached rate
     */
    public BigDecimal convert(BigDecimal amount, String fromCurrency, String toCurrency) {
        if (fromCurrency == null || fromCurrency.equalsIgnoreCase(toCurrency)) return amount;

        BigDecimal fromRate = rateToEur(fromCurrency);
        BigDecimal toRate = rateToEur(toCurrency);
        return amount.multiply(fromRate).divide(toRate, 4, RoundingMode.HALF_UP);
    }

    /**
     * Same as {@link #convert(BigDecimal, String, String)}, but first checks whether {@code
     * fromCurrency} is {@code paysId}'s own native currency -- if so, returns the amount
     * unchanged instead of running it through payroll_fx_rates.
     */
    public BigDecimal convert(BigDecimal amount, String fromCurrency, String toCurrency, Long paysId) {
        if (isNativeCurrency(paysId, fromCurrency)) return amount;
        return convert(amount, fromCurrency, toCurrency);
    }

    private BigDecimal rateToEur(String currency) {
        if ("EUR".equalsIgnoreCase(currency)) return BigDecimal.ONE;
        return fxRateRepo.findByCurrencyCode(currency.toUpperCase())
                .map(r -> r.getRateToEur())
                .orElseThrow(() -> new FxEntryConversionException(
                        "Aucun taux de change disponible pour '" + currency
                                + "'. Le synchronisateur de taux n'a peut-être pas encore tourné."));
    }
}
