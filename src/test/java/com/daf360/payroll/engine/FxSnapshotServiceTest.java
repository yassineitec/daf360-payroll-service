package com.daf360.payroll.engine;

import com.daf360.payroll.config.AppProperties;
import com.daf360.payroll.modules.payroll.entity.PayrollCountry;
import com.daf360.payroll.modules.payroll.repository.PayrollCountryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * payroll_countries is now the authoritative source for a pays's currency and conversion
 * rates — app.fx-rates (YAML) only fills in whatever a DB row doesn't (yet) carry, per field,
 * not per row: a row with a currency but no rate still falls back to YAML for that one rate.
 */
@ExtendWith(MockitoExtension.class)
class FxSnapshotServiceTest {

    private static final Long PAYS_ID = 179L;

    @Mock AppProperties appProperties;
    @Mock PayrollCountryRepository countryRepo;

    FxSnapshotService service;

    @BeforeEach
    void setUp() {
        service = new FxSnapshotService(appProperties, countryRepo);
        AppProperties.FxRateEntry yaml = new AppProperties.FxRateEntry();
        yaml.setCurrency("TND");
        yaml.setEur(new BigDecimal("0.300"));
        yaml.setUsd(new BigDecimal("0.325"));
        yaml.setChf(new BigDecimal("0.316"));
        lenient().when(appProperties.getFxRates()).thenReturn(Map.of(String.valueOf(PAYS_ID), yaml));
    }

    private static PayrollCountry countryWithAllFields() {
        PayrollCountry c = new PayrollCountry();
        c.setPaysId(PAYS_ID);
        c.setActive(true);
        c.setCurrencyCode("USD");
        c.setEurRate(new BigDecimal("0.900"));
        c.setUsdRate(new BigDecimal("1.000"));
        c.setChfRate(new BigDecimal("0.850"));
        return c;
    }

    private static PayrollCountry countryWithNoRates() {
        PayrollCountry c = new PayrollCountry();
        c.setPaysId(PAYS_ID);
        c.setActive(true);
        c.setCurrencyCode(null);
        c.setEurRate(null);
        c.setUsdRate(null);
        c.setChfRate(null);
        return c;
    }

    // ── localCurrency ────────────────────────────────────────────────────────

    @Test
    void localCurrency_activeRowWithCurrencySet_returnsDbValue() {
        when(countryRepo.findByPaysIdAndActiveTrue(PAYS_ID)).thenReturn(Optional.of(countryWithAllFields()));

        assertEquals("USD", service.localCurrency(PAYS_ID));
    }

    @Test
    void localCurrency_activeRowWithoutCurrency_fallsBackToYaml() {
        when(countryRepo.findByPaysIdAndActiveTrue(PAYS_ID)).thenReturn(Optional.of(countryWithNoRates()));

        assertEquals("TND", service.localCurrency(PAYS_ID));
    }

    @Test
    void localCurrency_noRowAtAll_fallsBackToYaml() {
        when(countryRepo.findByPaysIdAndActiveTrue(PAYS_ID)).thenReturn(Optional.empty());

        assertEquals("TND", service.localCurrency(PAYS_ID));
    }

    @Test
    void inactiveRow_isIgnoredEvenThoughFindByPaysIdWouldFindIt_allFourFieldsFallBackToYaml() {
        // The table has a row for this pays with every value set, but it's inactive —
        // findByPaysIdAndActiveTrue (correctly) excludes it at the query level, so the service
        // must fall through to YAML for every field, and must never consult the unfiltered
        // findByPaysId as a shortcut.
        PayrollCountry inactiveWithValues = countryWithAllFields();
        inactiveWithValues.setActive(false);
        lenient().when(countryRepo.findByPaysId(PAYS_ID)).thenReturn(Optional.of(inactiveWithValues));
        when(countryRepo.findByPaysIdAndActiveTrue(PAYS_ID)).thenReturn(Optional.empty());

        assertEquals("TND", service.localCurrency(PAYS_ID));
        assertEquals(new BigDecimal("0.300"), service.eurRate(PAYS_ID));
        assertEquals(new BigDecimal("0.325"), service.usdRate(PAYS_ID));
        assertEquals(new BigDecimal("0.316"), service.chfRate(PAYS_ID));
        verify(countryRepo, never()).findByPaysId(any());
    }

    // ── eurRate ──────────────────────────────────────────────────────────────

    @Test
    void eurRate_activeRowWithRateSet_returnsDbValue() {
        when(countryRepo.findByPaysIdAndActiveTrue(PAYS_ID)).thenReturn(Optional.of(countryWithAllFields()));

        assertEquals(new BigDecimal("0.900"), service.eurRate(PAYS_ID));
    }

    @Test
    void eurRate_activeRowWithNullRate_fallsBackToYaml() {
        when(countryRepo.findByPaysIdAndActiveTrue(PAYS_ID)).thenReturn(Optional.of(countryWithNoRates()));

        assertEquals(new BigDecimal("0.300"), service.eurRate(PAYS_ID));
    }

    @Test
    void eurRate_noRowAtAll_fallsBackToYaml() {
        when(countryRepo.findByPaysIdAndActiveTrue(PAYS_ID)).thenReturn(Optional.empty());

        assertEquals(new BigDecimal("0.300"), service.eurRate(PAYS_ID));
    }

    // ── usdRate ──────────────────────────────────────────────────────────────

    @Test
    void usdRate_activeRowWithRateSet_returnsDbValue() {
        when(countryRepo.findByPaysIdAndActiveTrue(PAYS_ID)).thenReturn(Optional.of(countryWithAllFields()));

        assertEquals(new BigDecimal("1.000"), service.usdRate(PAYS_ID));
    }

    @Test
    void usdRate_activeRowWithNullRate_fallsBackToYaml() {
        when(countryRepo.findByPaysIdAndActiveTrue(PAYS_ID)).thenReturn(Optional.of(countryWithNoRates()));

        assertEquals(new BigDecimal("0.325"), service.usdRate(PAYS_ID));
    }

    @Test
    void usdRate_noRowAtAll_fallsBackToYaml() {
        when(countryRepo.findByPaysIdAndActiveTrue(PAYS_ID)).thenReturn(Optional.empty());

        assertEquals(new BigDecimal("0.325"), service.usdRate(PAYS_ID));
    }

    // ── chfRate ──────────────────────────────────────────────────────────────

    @Test
    void chfRate_activeRowWithRateSet_returnsDbValue() {
        when(countryRepo.findByPaysIdAndActiveTrue(PAYS_ID)).thenReturn(Optional.of(countryWithAllFields()));

        assertEquals(new BigDecimal("0.850"), service.chfRate(PAYS_ID));
    }

    @Test
    void chfRate_activeRowWithNullRate_fallsBackToYaml() {
        when(countryRepo.findByPaysIdAndActiveTrue(PAYS_ID)).thenReturn(Optional.of(countryWithNoRates()));

        assertEquals(new BigDecimal("0.316"), service.chfRate(PAYS_ID));
    }

    @Test
    void chfRate_noRowAtAll_fallsBackToYaml() {
        when(countryRepo.findByPaysIdAndActiveTrue(PAYS_ID)).thenReturn(Optional.empty());

        assertEquals(new BigDecimal("0.316"), service.chfRate(PAYS_ID));
    }

    // ── no YAML entry either: everything is null, nothing blows up ─────────────

    @Test
    void whenNeitherDbNorYamlHasAnything_everythingIsNull() {
        when(countryRepo.findByPaysIdAndActiveTrue(eq(999L))).thenReturn(Optional.empty());
        when(appProperties.getFxRates()).thenReturn(Map.of());

        assertNull(service.localCurrency(999L));
        assertNull(service.eurRate(999L));
        assertNull(service.usdRate(999L));
        assertNull(service.chfRate(999L));
    }

    // ── conversions still work through the new resolution path ─────────────────

    @Test
    void convertToEur_usesDbRate_whenPresent() {
        when(countryRepo.findByPaysIdAndActiveTrue(PAYS_ID)).thenReturn(Optional.of(countryWithAllFields()));

        BigDecimal result = service.convertToEur(new BigDecimal("1000"), PAYS_ID);

        assertEquals(0, new BigDecimal("900.0000").compareTo(result));
    }

    // ── resolve() + FxSnapshot-based conversions ────────────────────────────────

    @Test
    void resolve_activeRowWithAllFields_returnsSnapshotMatchingDbValues() {
        when(countryRepo.findByPaysIdAndActiveTrue(PAYS_ID)).thenReturn(Optional.of(countryWithAllFields()));

        FxSnapshotService.FxSnapshot fx = service.resolve(PAYS_ID);

        assertEquals("USD", fx.currency());
        assertEquals(new BigDecimal("0.900"), fx.eur());
        assertEquals(new BigDecimal("1.000"), fx.usd());
        assertEquals(new BigDecimal("0.850"), fx.chf());
    }

    @Test
    void resolve_noRowAtAll_fallsBackToYamlForEveryField() {
        when(countryRepo.findByPaysIdAndActiveTrue(PAYS_ID)).thenReturn(Optional.empty());

        FxSnapshotService.FxSnapshot fx = service.resolve(PAYS_ID);

        assertEquals("TND", fx.currency());
        assertEquals(new BigDecimal("0.300"), fx.eur());
        assertEquals(new BigDecimal("0.325"), fx.usd());
        assertEquals(new BigDecimal("0.316"), fx.chf());
    }

    @Test
    void convertToEur_withSnapshot_usesSnapshotRate_whenPresent() {
        FxSnapshotService.FxSnapshot fx = new FxSnapshotService.FxSnapshot(
                "USD", new BigDecimal("0.900"), new BigDecimal("1.000"), new BigDecimal("0.850"));

        BigDecimal result = service.convertToEur(new BigDecimal("1000"), fx);

        assertEquals(0, new BigDecimal("900.0000").compareTo(result));
    }

    @Test
    void convertToUsd_withSnapshot_usesSnapshotRate_whenPresent() {
        FxSnapshotService.FxSnapshot fx = new FxSnapshotService.FxSnapshot(
                "USD", new BigDecimal("0.900"), new BigDecimal("1.000"), new BigDecimal("0.850"));

        BigDecimal result = service.convertToUsd(new BigDecimal("1000"), fx);

        assertEquals(0, new BigDecimal("1000.0000").compareTo(result));
    }

    @Test
    void convertToChf_withSnapshot_usesSnapshotRate_whenPresent() {
        FxSnapshotService.FxSnapshot fx = new FxSnapshotService.FxSnapshot(
                "USD", new BigDecimal("0.900"), new BigDecimal("1.000"), new BigDecimal("0.850"));

        BigDecimal result = service.convertToChf(new BigDecimal("1000"), fx);

        assertEquals(0, new BigDecimal("850.0000").compareTo(result));
    }

    @Test
    void convertToEur_withSnapshot_nullRate_returnsNull() {
        FxSnapshotService.FxSnapshot fx = new FxSnapshotService.FxSnapshot("TND", null, null, null);

        assertNull(service.convertToEur(new BigDecimal("1000"), fx));
    }

    @Test
    void convertToUsd_withSnapshot_nullRate_returnsNull() {
        FxSnapshotService.FxSnapshot fx = new FxSnapshotService.FxSnapshot("TND", null, null, null);

        assertNull(service.convertToUsd(new BigDecimal("1000"), fx));
    }

    @Test
    void convertToChf_withSnapshot_nullRate_returnsNull() {
        FxSnapshotService.FxSnapshot fx = new FxSnapshotService.FxSnapshot("TND", null, null, null);

        assertNull(service.convertToChf(new BigDecimal("1000"), fx));
    }

    @Test
    void convertToEur_withSnapshot_zeroRate_returnsNull() {
        FxSnapshotService.FxSnapshot fx = new FxSnapshotService.FxSnapshot("TND", BigDecimal.ZERO, null, null);

        assertNull(service.convertToEur(new BigDecimal("1000"), fx));
    }
}
