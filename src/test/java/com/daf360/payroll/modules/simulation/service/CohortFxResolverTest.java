package com.daf360.payroll.modules.simulation.service;

import com.daf360.payroll.config.AppProperties;
import com.daf360.payroll.engine.FxSnapshotService;
import com.daf360.payroll.modules.payroll.entity.PayrollCountry;
import com.daf360.payroll.modules.payroll.repository.PayrollCountryRepository;
import com.daf360.payroll.modules.ref.repository.PayrollFxRateRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A6 / DEC7 — never a silent 1.0 between two different currencies. */
class CohortFxResolverTest {

    private CohortFxResolver resolver(String currency, PayrollCountry country) {
        FxSnapshotService snap = mock(FxSnapshotService.class);
        when(snap.localCurrency(1L)).thenReturn(currency);
        PayrollFxRateRepository rates = mock(PayrollFxRateRepository.class);
        when(rates.findByCurrencyCode(anyString())).thenReturn(Optional.empty());   // nothing synced
        PayrollCountryRepository countries = mock(PayrollCountryRepository.class);
        when(countries.findByPaysIdAndActiveTrue(1L)).thenReturn(Optional.ofNullable(country));
        return new CohortFxResolver(snap, rates, countries, new AppProperties());
    }

    @Test
    void staticRateOfOne_betweenDifferentCurrencies_isRejected_withMessage() {
        PayrollCountry c = new PayrollCountry();
        c.setEurRate(BigDecimal.ONE);
        c.setChfRate(BigDecimal.ONE);
        CohortFxResolver.Resolution r = resolver("TND", c).resolve(1L);
        assertThat(r.fx().eurRate()).isNull();
        assertThat(r.fx().chfRate()).isNull();
        assertThat(r.toEur(new BigDecimal("1000"))).isNull();
        assertThat(r.messages()).extracting(m -> m.code()).contains("FX_MISSING_EUR", "FX_MISSING_CHF");
    }

    @Test
    void staticRate_isUsedWhenNothingSynced() {
        PayrollCountry c = new PayrollCountry();
        c.setEurRate(new BigDecimal("0.917"));
        CohortFxResolver.Resolution r = resolver("USD", c).resolve(1L);
        assertThat(r.fx().eurRate()).isEqualByComparingTo("0.917");
        assertThat(r.fx().eurSource()).isEqualTo(CohortFxResolver.SRC_STATIC);
        assertThat(r.fx().chfRate()).isNull();
    }

    @Test
    void sameCurrency_isIdentity() {
        CohortFxResolver.Resolution r = resolver("EUR", null).resolve(1L);
        assertThat(r.fx().eurRate()).isEqualByComparingTo("1");
        assertThat(r.fx().eurSource()).isEqualTo(CohortFxResolver.SRC_IDENTITY);
    }
}
