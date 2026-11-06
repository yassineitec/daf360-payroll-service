package com.daf360.payroll.modules.ref.service;

import com.daf360.payroll.modules.ref.repository.PayrollFxRateRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Converts a simulator-entered amount from any currency payroll_fx_rates has a cached rate for
 * into the country's working currency, composing through EUR (every cached rate is relative to
 * EUR, mirroring finance's own RATE_EUR_<CODE> convention). Used ONLY at simulation-request
 * entry, before PayrollSimulatorService is called -- the engine itself never sees a currency
 * other than the country's working one.
 */
@Service
public class FxEntryConversionService {

    private final PayrollFxRateRepository fxRateRepo;

    public FxEntryConversionService(PayrollFxRateRepository fxRateRepo) {
        this.fxRateRepo = fxRateRepo;
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

    private BigDecimal rateToEur(String currency) {
        if ("EUR".equalsIgnoreCase(currency)) return BigDecimal.ONE;
        return fxRateRepo.findByCurrencyCode(currency.toUpperCase())
                .map(r -> r.getRateToEur())
                .orElseThrow(() -> new FxEntryConversionException(
                        "Aucun taux de change disponible pour '" + currency
                                + "'. Le synchronisateur de taux n'a peut-être pas encore tourné."));
    }
}
