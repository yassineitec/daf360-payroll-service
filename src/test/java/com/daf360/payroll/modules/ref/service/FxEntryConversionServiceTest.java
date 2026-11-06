package com.daf360.payroll.modules.ref.service;

import com.daf360.payroll.modules.ref.entity.PayrollFxRate;
import com.daf360.payroll.modules.ref.repository.PayrollFxRateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FxEntryConversionServiceTest {

    @Mock PayrollFxRateRepository fxRateRepo;

    private FxEntryConversionService service;

    @Test
    void convert_sameCurrency_returnsAmountUnchanged() {
        service = new FxEntryConversionService(fxRateRepo);

        BigDecimal result = service.convert(new BigDecimal("198.00"), "EUR", "EUR");

        assertEquals(new BigDecimal("198.00"), result);
    }

    @Test
    void convert_tndToEur_appliesRateDirectly() {
        service = new FxEntryConversionService(fxRateRepo);
        PayrollFxRate tnd = new PayrollFxRate();
        tnd.setCurrencyCode("TND");
        tnd.setRateToEur(new BigDecimal("0.300000"));
        when(fxRateRepo.findByCurrencyCode("TND")).thenReturn(Optional.of(tnd));

        // 198 TND x 0.300000 EUR/TND / 1 (EUR working currency) = 59.4000
        BigDecimal result = service.convert(new BigDecimal("198"), "TND", "EUR");

        assertEquals(0, new BigDecimal("59.4000").compareTo(result));
    }

    @Test
    void convert_tndToUsd_pivotsThroughEur() {
        service = new FxEntryConversionService(fxRateRepo);
        PayrollFxRate tnd = new PayrollFxRate();
        tnd.setCurrencyCode("TND");
        tnd.setRateToEur(new BigDecimal("0.300000"));
        PayrollFxRate usd = new PayrollFxRate();
        usd.setCurrencyCode("USD");
        usd.setRateToEur(new BigDecimal("0.920000"));
        when(fxRateRepo.findByCurrencyCode("TND")).thenReturn(Optional.of(tnd));
        when(fxRateRepo.findByCurrencyCode("USD")).thenReturn(Optional.of(usd));

        // 100 TND -> 30 EUR -> 30 / 0.92 = 32.6087 USD
        BigDecimal result = service.convert(new BigDecimal("100"), "TND", "USD");

        assertEquals(0, new BigDecimal("32.6087").compareTo(result));
    }

    @Test
    void convert_unknownCurrency_throwsFxEntryConversionException() {
        service = new FxEntryConversionService(fxRateRepo);
        when(fxRateRepo.findByCurrencyCode("XYZ")).thenReturn(Optional.empty());

        assertThrows(FxEntryConversionException.class,
                () -> service.convert(new BigDecimal("100"), "XYZ", "EUR"));
    }

    @Test
    void convert_nullFromCurrency_returnsAmountUnchanged() {
        service = new FxEntryConversionService(fxRateRepo);

        BigDecimal result = service.convert(new BigDecimal("415.40"), null, "EUR");

        assertEquals(new BigDecimal("415.40"), result);
    }
}
