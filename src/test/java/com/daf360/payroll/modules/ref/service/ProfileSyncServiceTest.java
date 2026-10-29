package com.daf360.payroll.modules.ref.service;

import com.daf360.payroll.config.AppProperties;
import com.daf360.payroll.modules.payroll.entity.PayrollCountry;
import com.daf360.payroll.modules.payroll.repository.PayrollCountryRepository;
import com.daf360.payroll.modules.ref.entity.PaysRef;
import com.daf360.payroll.modules.ref.repository.PaysRefRepository;
import com.daf360.payroll.modules.ref.repository.UsersRefRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * syncEntityCurrencies(): payroll_countries.currency_code and pays_ref.devise are now kept in
 * sync from facturation's entities-for-sync endpoint (the 13 real ARX entities) every 15
 * minutes, instead of being independently hand-maintained.
 *
 * The third test guards the one real correctness risk: the pays_ref row carries other columns
 * (frenchLabel, ...) that must survive the sync untouched — only `devise` may change. A naive
 * `new PaysRef()` with just id/isoCode/devise set, then saveAll(), would silently blank every
 * other column on that row because JPA's save() on a detached entity is a full column
 * overwrite, not a field-level merge.
 */
@ExtendWith(MockitoExtension.class)
class ProfileSyncServiceTest {

    private static final String FACT_URL = "http://fact-url";
    private static final String ENTITIES_FOR_SYNC_URL = FACT_URL + "/api/fact/ref/entities-for-sync";

    @Mock RestTemplate restTemplate;
    @Mock PaysRefRepository paysRefRepository;
    @Mock UsersRefRepository usersRefRepository;
    @Mock AppProperties appProperties;
    @Mock PayrollCountryRepository payrollCountryRepository;

    ProfileSyncService service;

    @BeforeEach
    void setUp() {
        service = new ProfileSyncService(restTemplate, paysRefRepository, usersRefRepository,
                appProperties, payrollCountryRepository);
        ReflectionTestUtils.setField(service, "facturationServiceUrl", FACT_URL);
    }

    @Test
    void syncEntityCurrencies_existingRow_updatesCurrencyCodeOnly() {
        List<Map<String, Object>> response = List.of(
                Map.of("id", 179, "isoCode", "TN", "devise", "EUR"));
        when(restTemplate.exchange(eq(ENTITIES_FOR_SYNC_URL),
                eq(HttpMethod.GET), isNull(), any(ParameterizedTypeReference.class)))
                .thenReturn(ResponseEntity.ok(response));
        PayrollCountry existing = new PayrollCountry();
        existing.setPaysId(179L);
        existing.setCurrencyCode("TND");
        existing.setActive(true);
        existing.setEurRate(new BigDecimal("1.000"));
        when(payrollCountryRepository.findByPaysId(179L)).thenReturn(Optional.of(existing));
        lenient().when(paysRefRepository.findById(179L)).thenReturn(Optional.empty());

        service.syncEntityCurrencies();

        ArgumentCaptor<PayrollCountry> captor = ArgumentCaptor.forClass(PayrollCountry.class);
        verify(payrollCountryRepository).save(captor.capture());
        assertThat(captor.getValue().getCurrencyCode()).isEqualTo("EUR");
        assertThat(captor.getValue().isActive()).isTrue(); // untouched
        assertThat(captor.getValue().getEurRate()).isEqualByComparingTo("1.000"); // untouched
    }

    @Test
    void syncEntityCurrencies_noExistingRow_insertsInactiveRow() {
        List<Map<String, Object>> response = List.of(
                Map.of("id", 152, "isoCode", "SA", "devise", "SAR"));
        when(restTemplate.exchange(eq(ENTITIES_FOR_SYNC_URL),
                eq(HttpMethod.GET), isNull(), any(ParameterizedTypeReference.class)))
                .thenReturn(ResponseEntity.ok(response));
        when(payrollCountryRepository.findByPaysId(152L)).thenReturn(Optional.empty());
        lenient().when(paysRefRepository.findById(152L)).thenReturn(Optional.empty());

        service.syncEntityCurrencies();

        ArgumentCaptor<PayrollCountry> captor = ArgumentCaptor.forClass(PayrollCountry.class);
        verify(payrollCountryRepository).save(captor.capture());
        assertThat(captor.getValue().getPaysId()).isEqualTo(152L);
        assertThat(captor.getValue().getCurrencyCode()).isEqualTo("SAR");
        assertThat(captor.getValue().isActive()).isFalse();
    }

    @Test
    void syncEntityCurrencies_setsPaysRefDeviseDirectly() {
        List<Map<String, Object>> response = List.of(
                Map.of("id", 179, "isoCode", "TN", "devise", "EUR"));
        when(restTemplate.exchange(eq(ENTITIES_FOR_SYNC_URL),
                eq(HttpMethod.GET), isNull(), any(ParameterizedTypeReference.class)))
                .thenReturn(ResponseEntity.ok(response));
        when(payrollCountryRepository.findByPaysId(179L)).thenReturn(Optional.empty());
        // The CORRECT implementation loads the existing PaysRef row via findById before mutating
        // devise -- stub that lookup here so the test can verify only `devise` changed, nothing
        // else on the row.
        PaysRef existingPaysRef = new PaysRef();
        existingPaysRef.setId(179L);
        existingPaysRef.setIsoCode("TN");
        existingPaysRef.setFrenchLabel("Tunisie"); // must survive the sync unchanged
        existingPaysRef.setDevise("TND"); // the stale value being corrected
        when(paysRefRepository.findById(179L)).thenReturn(Optional.of(existingPaysRef));

        service.syncEntityCurrencies();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PaysRef>> captor = ArgumentCaptor.forClass(List.class);
        verify(paysRefRepository).saveAll(captor.capture());
        PaysRef saved = captor.getValue().stream().filter(p -> p.getId().equals(179L)).findFirst().orElseThrow();
        assertThat(saved.getDevise()).isEqualTo("EUR");
        assertThat(saved.getFrenchLabel()).isEqualTo("Tunisie"); // PROVES no other column was blanked
    }

    @Test
    void syncEntityCurrencies_noMatchingPaysRefRow_stillUpdatesPayrollCountry() {
        List<Map<String, Object>> response = List.of(
                Map.of("id", 999, "isoCode", "ZZ", "devise", "USD"));
        when(restTemplate.exchange(eq(ENTITIES_FOR_SYNC_URL),
                eq(HttpMethod.GET), isNull(), any(ParameterizedTypeReference.class)))
                .thenReturn(ResponseEntity.ok(response));
        when(payrollCountryRepository.findByPaysId(999L)).thenReturn(Optional.empty());
        when(paysRefRepository.findById(999L)).thenReturn(Optional.empty());

        service.syncEntityCurrencies();

        verify(payrollCountryRepository).save(any());
        verify(paysRefRepository, never()).saveAll(any());
    }

    @Test
    void syncEntityCurrencies_emptyResponse_leavesEverythingUntouched() {
        when(restTemplate.exchange(eq(ENTITIES_FOR_SYNC_URL),
                eq(HttpMethod.GET), isNull(), any(ParameterizedTypeReference.class)))
                .thenReturn(ResponseEntity.ok(List.of()));

        service.syncEntityCurrencies();

        verify(payrollCountryRepository, never()).save(any());
        verify(paysRefRepository, never()).saveAll(any());
    }

    @Test
    void syncEntityCurrencies_restCallFails_isSwallowed() {
        when(restTemplate.exchange(eq(ENTITIES_FOR_SYNC_URL),
                eq(HttpMethod.GET), isNull(), any(ParameterizedTypeReference.class)))
                .thenThrow(new RuntimeException("connection refused"));

        assertThatCode(() -> service.syncEntityCurrencies()).doesNotThrowAnyException();

        verify(payrollCountryRepository, never()).save(any());
        verify(paysRefRepository, never()).saveAll(any());
    }

    // ── syncPays(): RH always sends devise=null; pays_ref.devise is NOT NULL at the DB level ──
    // (confirmed against the real dev DB) -- so this step must never write that null through, or
    // every sync would fail the NOT NULL constraint for every country, every 15 minutes.

    private static final String RH_URL = "http://rh-url";
    private static final String PAYS_FOR_SYNC_URL = RH_URL + "/api/hr/pays-for-sync";

    @Test
    void syncPays_rhSendsNullDevise_preservesExistingPaysRefDevise() {
        ReflectionTestUtils.setField(service, "rhServiceUrl", RH_URL);
        List<Map<String, Object>> response = List.of(
                Map.of("id", 179, "isoCode", "TN", "frenchLabel", "Tunisie"));
        when(restTemplate.exchange(eq(PAYS_FOR_SYNC_URL),
                eq(HttpMethod.GET), isNull(), any(ParameterizedTypeReference.class)))
                .thenReturn(ResponseEntity.ok(response));
        PaysRef existing = new PaysRef();
        existing.setId(179L);
        existing.setDevise("EUR"); // previously set by syncEntityCurrencies()
        when(paysRefRepository.findAllById(List.of(179L))).thenReturn(List.of(existing));

        service.syncPays();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PaysRef>> captor = ArgumentCaptor.forClass(List.class);
        verify(paysRefRepository).saveAll(captor.capture());
        PaysRef saved = captor.getValue().stream().filter(p -> p.getId().equals(179L)).findFirst().orElseThrow();
        assertThat(saved.getDevise()).isEqualTo("EUR"); // NOT null -- preserved, not blanked
        assertThat(saved.getFrenchLabel()).isEqualTo("Tunisie");
    }

    @Test
    void syncPays_rhSendsNullDevise_newPaysRefRow_defaultsToEmptyStringNotNull() {
        ReflectionTestUtils.setField(service, "rhServiceUrl", RH_URL);
        List<Map<String, Object>> response = List.of(
                Map.of("id", 999, "isoCode", "ZZ", "frenchLabel", "Nouveau"));
        when(restTemplate.exchange(eq(PAYS_FOR_SYNC_URL),
                eq(HttpMethod.GET), isNull(), any(ParameterizedTypeReference.class)))
                .thenReturn(ResponseEntity.ok(response));
        when(paysRefRepository.findAllById(List.of(999L))).thenReturn(List.of());

        service.syncPays();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PaysRef>> captor = ArgumentCaptor.forClass(List.class);
        verify(paysRefRepository).saveAll(captor.capture());
        PaysRef saved = captor.getValue().stream().filter(p -> p.getId().equals(999L)).findFirst().orElseThrow();
        assertThat(saved.getDevise()).isEqualTo(""); // never null
    }
}
