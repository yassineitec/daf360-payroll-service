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
import static org.mockito.Mockito.verifyNoInteractions;
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

    @Test
    void isNativeCurrency_tndForTunisia_returnsTrue() {
        service = new FxEntryConversionService(fxRateRepo);

        assertTrue(service.isNativeCurrency(179L, "TND"));
        assertTrue(service.isNativeCurrency(179L, "tnd"));
    }

    @Test
    void isNativeCurrency_eurForTunisia_returnsFalse() {
        service = new FxEntryConversionService(fxRateRepo);

        assertFalse(service.isNativeCurrency(179L, "EUR"));
    }

    @Test
    void isNativeCurrency_egpForEgypt_returnsTrue() {
        service = new FxEntryConversionService(fxRateRepo);

        assertTrue(service.isNativeCurrency(53L, "EGP"));
    }

    @Test
    void isNativeCurrency_tndForEgypt_returnsFalse() {
        service = new FxEntryConversionService(fxRateRepo);

        assertFalse(service.isNativeCurrency(53L, "TND"));
    }

    @Test
    void isNativeCurrency_unknownPaysId_returnsFalse() {
        service = new FxEntryConversionService(fxRateRepo);

        assertFalse(service.isNativeCurrency(999L, "TND"));
    }

    @Test
    void isNativeCurrency_nullCurrency_returnsFalse() {
        service = new FxEntryConversionService(fxRateRepo);

        assertFalse(service.isNativeCurrency(179L, null));
    }

    @Test
    void convert_fourArg_nativeCurrencyForTunisia_returnsAmountUnchangedWithoutRateLookup() {
        service = new FxEntryConversionService(fxRateRepo);

        BigDecimal result = service.convert(new BigDecimal("2070.28"), "TND", "EUR", 179L);

        assertEquals(new BigDecimal("2070.28"), result);
        verifyNoInteractions(fxRateRepo);
    }

    @Test
    void convert_fourArg_nonNativeCurrency_appliesRealConversion() {
        service = new FxEntryConversionService(fxRateRepo);
        PayrollFxRate usd = new PayrollFxRate();
        usd.setCurrencyCode("USD");
        usd.setRateToEur(new BigDecimal("0.920000"));
        when(fxRateRepo.findByCurrencyCode("USD")).thenReturn(Optional.of(usd));

        // 100 USD entered for Tunisia (a genuinely foreign currency there) -> 92.0000 EUR
        BigDecimal result = service.convert(new BigDecimal("100"), "USD", "EUR", 179L);

        assertEquals(0, new BigDecimal("92.0000").compareTo(result));
    }
}
