package com.daf360.payroll.modules.admin.service;

import com.daf360.payroll.modules.admin.dto.AuditEntryDto;
import com.daf360.payroll.modules.admin.dto.PayrollCountryDto;
import com.daf360.payroll.modules.admin.dto.SavePayrollCountryRequest;
import org.springframework.security.access.AccessDeniedException;
import java.util.Optional;
import com.daf360.payroll.modules.employeeconfig.entity.EmployeePayrollConfigHistory;
import com.daf360.payroll.modules.employeeconfig.repository.EmployeePayrollConfigHistoryRepository;
import com.daf360.payroll.modules.payroll.entity.PayrollCountry;
import com.daf360.payroll.modules.payroll.repository.PayrollCountryRepository;
import com.daf360.payroll.modules.ref.entity.PaysRef;
import com.daf360.payroll.modules.ref.entity.UsersRef;
import com.daf360.payroll.modules.ref.repository.PaysRefRepository;
import com.daf360.payroll.modules.ref.repository.UsersRefRepository;
import com.daf360.payroll.modules.ref.service.UserContextService;
import com.daf360.payroll.modules.salaryadvance.entity.SalaryAdvance;
import com.daf360.payroll.modules.salaryadvance.entity.SalaryAdvanceHistory;
import com.daf360.payroll.modules.salaryadvance.repository.SalaryAdvanceHistoryRepository;
import com.daf360.payroll.modules.salaryadvance.repository.SalaryAdvanceRepository;
import com.daf360.payroll.security.PaysScopeContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PayrollAdminServiceTest {

    @Mock PayrollCountryRepository               countryRepo;
    @Mock PaysRefRepository                      paysRefRepo;
    @Mock UsersRefRepository                     usersRefRepo;
    @Mock EmployeePayrollConfigHistoryRepository configHistoryRepo;
    @Mock SalaryAdvanceHistoryRepository         advanceHistoryRepo;
    @Mock SalaryAdvanceRepository                advanceRepo;
    @Mock UserContextService                     userContext;

    PayrollAdminService service;

    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-09-01T10:00:00Z");

    @BeforeEach
    void setUp() {
        service = new PayrollAdminService(countryRepo, paysRefRepo, usersRefRepo,
                configHistoryRepo, advanceHistoryRepo, advanceRepo, userContext);
    }

    @Test
    void listCountries_keepsOnlyTheCallersScope_withPaysLabels() {
        when(userContext.getCurrentUserPaysScope()).thenReturn(PaysScopeContext.PaysScope.of(Set.of(179L)));
        when(paysRefRepo.findAll()).thenReturn(List.of(pays(179L, "TN", "Tunisie"), pays(52L, "EG", "Égypte")));
        when(countryRepo.findAll()).thenReturn(List.of(country(1L, 179L, "TND"), country(2L, 52L, "EGP")));

        List<PayrollCountryDto> result = service.listCountries();

        assertEquals(1, result.size());
        assertEquals(179L, result.get(0).paysId());
        assertEquals("Tunisie", result.get(0).paysLabel());
        assertEquals("TN", result.get(0).isoCode());
        assertEquals("TND", result.get(0).currencyCode());
    }

    @Test
    void audit_mergesBothSourcesNewestFirst_withNames() {
        EmployeePayrollConfigHistory config = new EmployeePayrollConfigHistory();
        config.setId(10L);
        config.setPaysId(179L);
        config.setProfileUserId(7L);
        config.setChangedBy(1L);
        config.setContractType("CDI");
        config.setCurrentGrossSalary(new BigDecimal("3000.000"));
        config.setReason("Augmentation");
        config.setChangedAt(T0);

        SalaryAdvanceHistory advance = new SalaryAdvanceHistory();
        advance.setId(20L);
        advance.setSalaryAdvanceId(5L);
        advance.setFromStatus("PENDING_FINANCE");
        advance.setToStatus("APPROVED");
        advance.setActorUserId(2L);
        advance.setCreatedAt(T0.plusDays(1));

        SalaryAdvance a = new SalaryAdvance();
        a.setId(5L);
        a.setProfileUserId(8L);

        when(configHistoryRepo.findByPaysIdOrderByChangedAtDesc(eq(179L), any())).thenReturn(List.of(config));
        when(advanceHistoryRepo.findLatestByPaysId(eq(179L), any())).thenReturn(List.of(advance));
        when(advanceRepo.findAllById(anyIterable())).thenReturn(List.of(a));
        when(usersRefRepo.findAllById(anyIterable())).thenReturn(List.of(
                user(1L, "Admin Paie"), user(2L, "DAF"), user(7L, "Salma"), user(8L, "Karim")));

        List<AuditEntryDto> log = service.audit(179L, 50, true, true);

        assertEquals(2, log.size());
        AuditEntryDto first = log.get(0);   // the advance, one day later
        assertEquals(PayrollAdminService.SOURCE_SALARY_ADVANCE, first.source());
        assertEquals("APPROVED", first.action());
        assertEquals("PENDING_FINANCE", first.fromStatus());
        assertEquals("DAF", first.actorName());
        assertEquals("Karim", first.subjectName());

        AuditEntryDto second = log.get(1);
        assertEquals(PayrollAdminService.SOURCE_EMPLOYEE_CONFIG, second.source());
        assertEquals("Augmentation", second.action());
        assertEquals("CDI · 3000.000", second.detail());
        assertEquals("Admin Paie", second.actorName());
        assertEquals("Salma", second.subjectName());
        assertNull(second.fromStatus());
    }

    @Test
    void audit_skipsSourcesTheCallerMayNotRead() {
        lenient().when(advanceRepo.findAllById(anyIterable())).thenReturn(List.of());

        List<AuditEntryDto> log = service.audit(179L, 50, false, false);

        assertEquals(0, log.size());
        verify(configHistoryRepo, never()).findByPaysIdOrderByChangedAtDesc(any(), any());
        verify(advanceHistoryRepo, never()).findLatestByPaysId(any(), any());
        verify(usersRefRepo, never()).findAllById(anyCollection());
    }

    // ── Pays de paie : création / modification ──────────────────────────────

    private static SavePayrollCountryRequest countryReq(Long paysId, String currency, Integer month, String forex) {
        return new SavePayrollCountryRequest(paysId, currency, month, forex, true);
    }

    @Test
    void createCountry_normalisesTheCurrency_andSaves() {
        when(userContext.getCurrentUserPaysScope()).thenReturn(PaysScopeContext.PaysScope.of(Set.of(179L)));
        when(paysRefRepo.findById(179L)).thenReturn(Optional.of(pays(179L, "TN", "Tunisie")));
        when(countryRepo.findByPaysId(179L)).thenReturn(Optional.empty());
        when(countryRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PayrollCountryDto dto = service.createCountry(countryReq(179L, " tnd ", 1, "[{\"name\":\"BCT\"}]"));

        assertEquals("TND", dto.currencyCode());
        assertEquals("Tunisie", dto.paysLabel());
        assertEquals(true, dto.active());
    }

    @Test
    void createCountry_alreadyConfigured_isRefused() {
        when(userContext.getCurrentUserPaysScope()).thenReturn(PaysScopeContext.PaysScope.of(Set.of(179L)));
        when(paysRefRepo.findById(179L)).thenReturn(Optional.of(pays(179L, "TN", "Tunisie")));
        when(countryRepo.findByPaysId(179L)).thenReturn(Optional.of(country(1L, 179L, "TND")));

        assertThrows(IllegalStateException.class, () -> service.createCountry(countryReq(179L, "TND", 1, null)));
        verify(countryRepo, never()).save(any());
    }

    @Test
    void createCountry_outOfScope_isRefused() {
        when(userContext.getCurrentUserPaysScope()).thenReturn(PaysScopeContext.PaysScope.of(Set.of(52L)));

        assertThrows(AccessDeniedException.class, () -> service.createCountry(countryReq(179L, "TND", 1, null)));
        verify(countryRepo, never()).save(any());
    }

    @Test
    void updateCountry_rejectsBadValues() {
        when(userContext.getCurrentUserPaysScope()).thenReturn(PaysScopeContext.PaysScope.of(Set.of(179L)));
        when(countryRepo.findById(1L)).thenReturn(Optional.of(country(1L, 179L, "TND")));

        assertThrows(IllegalArgumentException.class, () -> service.updateCountry(1L, countryReq(null, "TN", 1, null)));
        assertThrows(IllegalArgumentException.class, () -> service.updateCountry(1L, countryReq(null, "TND", 13, null)));
        assertThrows(IllegalArgumentException.class, () -> service.updateCountry(1L, countryReq(null, "TND", 1, "{pas du json")));
        assertThrows(IllegalArgumentException.class, () -> service.updateCountry(1L, countryReq(null, "TND", 1, "\"texte\"")));
        verify(countryRepo, never()).save(any());
    }

    private static PaysRef pays(Long id, String iso, String label) {
        PaysRef p = new PaysRef();
        p.setId(id);
        p.setIsoCode(iso);
        p.setFrenchLabel(label);
        return p;
    }

    private static PayrollCountry country(Long id, Long paysId, String currency) {
        PayrollCountry c = new PayrollCountry();
        c.setId(id);
        c.setPaysId(paysId);
        c.setCurrencyCode(currency);
        c.setActive(true);
        return c;
    }

    private static UsersRef user(Long id, String name) {
        UsersRef u = new UsersRef();
        u.setId(id);
        u.setFullName(name);
        return u;
    }
}
