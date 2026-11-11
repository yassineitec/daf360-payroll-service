package com.daf360.payroll.modules.employeeconfig.service;

import com.daf360.payroll.engine.FxSnapshotService;
import com.daf360.payroll.modules.employeeconfig.dto.CalculateNetRequest;
import com.daf360.payroll.modules.employeeconfig.dto.CalculateNetResponse;
import com.daf360.payroll.modules.employeeconfig.dto.EmployeePayrollConfigDto;
import com.daf360.payroll.modules.employeeconfig.dto.UpsertEmployeePayrollConfigRequest;
import com.daf360.payroll.modules.employeeconfig.entity.EmployeePayrollConfig;
import com.daf360.payroll.modules.employeeconfig.entity.EmployeePayrollConfigHistory;
import com.daf360.payroll.modules.employeeconfig.repository.EmployeePayrollConfigHistoryRepository;
import com.daf360.payroll.modules.employeeconfig.repository.EmployeePayrollConfigRepository;
import com.daf360.payroll.modules.parameterset.entity.ParameterSet;
import com.daf360.payroll.modules.parameterset.entity.SocialChargeRate;
import com.daf360.payroll.modules.parameterset.service.ParameterSetService;
import com.daf360.payroll.modules.payroll.dto.RunPayrollRequest;
import com.daf360.payroll.modules.payroll.dto.RunPayrollResponse;
import com.daf360.payroll.modules.payroll.orchestrator.PayrollOrchestrator;
import com.daf360.payroll.modules.ref.repository.UsersRefRepository;
import com.daf360.payroll.modules.ref.service.FxEntryConversionService;
import com.daf360.payroll.modules.simulation.client.HrEmployeeClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmployeePayrollConfigServiceTest {

    @Mock EmployeePayrollConfigRepository repo;
    @Mock EmployeePayrollConfigHistoryRepository historyRepo;
    @Mock HrEmployeeClient hrEmployeeClient;
    @Mock ParameterSetService paramSetService;
    @Mock PayrollOrchestrator orchestrator;
    @Mock FxSnapshotService fxService;
    @Mock FxEntryConversionService fxEntryService;
    @Mock UsersRefRepository usersRefRepo;

    private static final FxSnapshotService.FxSnapshot TND =
            new FxSnapshotService.FxSnapshot("TND", new BigDecimal("0.30"), new BigDecimal("0.32"), new BigDecimal("0.28"));

    private EmployeePayrollConfigService service() {
        return new EmployeePayrollConfigService(
                repo, historyRepo, hrEmployeeClient, new EmployeeFamilyResolver(hrEmployeeClient),
                new ObjectMapper().findAndRegisterModules(), paramSetService, orchestrator, fxService, fxEntryService,
                usersRefRepo);
    }

    private void engineNet(String net) {
        RunPayrollResponse r = RunPayrollResponse.builder().aggregateNet(new BigDecimal(net)).build();
        when(orchestrator.preview(any(RunPayrollRequest.class), any(BigDecimal.class))).thenReturn(r);
    }

    private void noActiveOldModelRules() {
        when(paramSetService.loadActiveEntity(anyLong())).thenThrow(new NoSuchElementException("none"));
    }

    private static UpsertEmployeePayrollConfigRequest req(String contract, String gross, String net, String currency) {
        return new UpsertEmployeePayrollConfigRequest(179L, contract, List.of(),
                gross == null ? null : new BigDecimal(gross), net == null ? null : new BigDecimal(net),
                "Ajustement", currency, null);
    }

    // ── V38: a salary change on an employee who already has one goes through approval ──

    private EmployeePayrollConfig savedConfig() {
        EmployeePayrollConfig existing = new EmployeePayrollConfig();
        existing.setProfileUserId(10L);
        existing.setPaysId(179L);
        existing.setContractType("CDI");
        existing.setCurrentGrossSalary(new BigDecimal("2500"));
        existing.setUpdatedAt(java.time.OffsetDateTime.now().minusDays(3));
        when(repo.findByProfileUserId(10L)).thenReturn(Optional.of(existing));
        when(repo.save(any(EmployeePayrollConfig.class))).thenAnswer(inv -> inv.getArgument(0));
        when(fxService.resolve(179L)).thenReturn(TND);
        noActiveOldModelRules();
        engineNet("2000");
        return existing;
    }

    @Test
    void upsert_directSalaryChange_onAnAlreadyPaidEmployee_isRefused() {
        EmployeePayrollConfig existing = savedConfig();

        assertThrows(IllegalStateException.class, () -> service().upsert(10L, req("CDI", "2800", null, null), 1L));
        assertThrows(IllegalStateException.class, () -> service().upsert(10L, req("CDD", "2500", null, null), 1L));
        assertEquals(0, new BigDecimal("2500").compareTo(existing.getCurrentGrossSalary()));
        verify(historyRepo, never()).save(any());
    }

    @Test
    void upsert_sameSalaryResaved_isAllowed() {
        savedConfig();

        service().upsert(10L, req("CDI", "2500", null, null), 1L);

        verify(historyRepo).save(any());
    }

    @Test
    void applyApproved_bypassesTheGuard_andRecordsTheApprover() {
        EmployeePayrollConfig existing = savedConfig();
        java.time.OffsetDateTime at = java.time.OffsetDateTime.now();

        service().applyApproved(10L, req("CDI", "2800", null, null), 7L,
                new EmployeePayrollConfigService.Approval(42L, 99L, at));

        assertEquals(0, new BigDecimal("2800").compareTo(existing.getCurrentGrossSalary()));
        ArgumentCaptor<EmployeePayrollConfigHistory> h = ArgumentCaptor.forClass(EmployeePayrollConfigHistory.class);
        verify(historyRepo).save(h.capture());
        assertEquals(7L, h.getValue().getChangedBy());
        assertEquals(99L, h.getValue().getApprovedBy());
        assertEquals(42L, h.getValue().getSalaryChangeRequestId());
    }

    // ── Rémunération redesign (V37): effective date, benefits snapshot, history ──

    @Test
    void upsert_effectiveDate_storedOnConfigAndHistory_defaultsToToday() {
        noActiveOldModelRules();
        engineNet("2000");
        when(fxService.resolve(179L)).thenReturn(TND);
        when(repo.findByProfileUserId(10L)).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        java.time.LocalDate first = java.time.LocalDate.of(2026, 12, 1);
        service().upsert(10L, new UpsertEmployeePayrollConfigRequest(179L, "CDI", List.of(),
                new BigDecimal("2500"), null, "Augmentation", null, first), 1L);
        service().upsert(10L, req("CDI", "2600", null, null), 1L);

        ArgumentCaptor<EmployeePayrollConfigHistory> saved = ArgumentCaptor.forClass(EmployeePayrollConfigHistory.class);
        verify(historyRepo, org.mockito.Mockito.times(2)).save(saved.capture());
        assertEquals(first, saved.getAllValues().get(0).getEffectiveDate());
        assertEquals(java.time.LocalDate.now(), saved.getAllValues().get(1).getEffectiveDate());
    }

    @Test
    void upsert_snapshotsSelectedBenefitsWithTheirAmounts() {
        ParameterSet ps = new ParameterSet();
        ps.setId(5L);
        when(paramSetService.loadActiveEntity(179L)).thenReturn(ps);
        when(paramSetService.loadRates(5L)).thenReturn(List.of());
        com.daf360.payroll.modules.parameterset.entity.BenefitCatalogue meal = new com.daf360.payroll.modules.parameterset.entity.BenefitCatalogue();
        meal.setBenefitCode("MEAL");
        meal.setBenefitLabelFr("Tickets restaurant");
        meal.setMonthlyValue(new BigDecimal("198"));
        meal.setIsTaxable(false);
        when(paramSetService.loadBenefits(5L)).thenReturn(List.of(meal));
        engineNet("2000");
        when(fxService.resolve(179L)).thenReturn(TND);
        when(repo.findByProfileUserId(10L)).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service().upsert(10L, new UpsertEmployeePayrollConfigRequest(179L, "CDI", List.of("MEAL", "GONE"),
                new BigDecimal("2500"), null, "Embauche", null, null), 1L);

        ArgumentCaptor<EmployeePayrollConfigHistory> saved = ArgumentCaptor.forClass(EmployeePayrollConfigHistory.class);
        verify(historyRepo).save(saved.capture());
        String json = saved.getValue().getBenefitsSnapshot();
        assertTrue(json.contains("\"code\":\"MEAL\"") && json.contains("198") && json.contains("Tickets restaurant"), json);
        // A code no longer in the catalogue is kept, without an amount.
        assertTrue(json.contains("\"code\":\"GONE\""), json);
    }

    @Test
    void getHistory_previousValues_changerName_currency_andLegacyRowsWithoutSnapshot() {
        EmployeePayrollConfigHistory newer = historyRow(2L, "3000", "2300", 7L);
        newer.setBenefitsSnapshot("[{\"code\":\"MEAL\",\"labelFr\":\"Tickets restaurant\",\"labelEn\":null,\"monthlyValue\":198,\"taxable\":false}]");
        newer.setEffectiveDate(java.time.LocalDate.of(2026, 12, 1));
        EmployeePayrollConfigHistory older = historyRow(1L, "2500", "1950", 8L);
        older.setSelectedBenefitCodes("[\"MEAL\"]");
        when(historyRepo.findByProfileUserIdOrderByChangedAtDesc(10L)).thenReturn(List.of(newer, older));
        com.daf360.payroll.modules.ref.entity.UsersRef u = new com.daf360.payroll.modules.ref.entity.UsersRef();
        u.setId(7L);
        u.setFullName("Sana Ben Ali");
        when(usersRefRepo.findAllById(any())).thenReturn(List.of(u));
        when(fxService.resolve(179L)).thenReturn(TND);

        var list = service().getHistory(10L);

        assertEquals(2, list.size());
        assertEquals(new BigDecimal("2500"), list.get(0).previousGrossSalary());
        assertEquals(new BigDecimal("1950"), list.get(0).previousNetSalary());
        assertEquals("Sana Ben Ali", list.get(0).changedByName());
        assertEquals("TND", list.get(0).workingCurrency());
        assertEquals(java.time.LocalDate.of(2026, 12, 1), list.get(0).effectiveDate());
        assertEquals(0, new BigDecimal("198").compareTo(list.get(0).benefits().get(0).monthlyValue()));
        // Oldest row: nothing before it; user not synced; pre-V37 row → code only.
        assertNull(list.get(1).previousGrossSalary());
        assertNull(list.get(1).changedByName());
        assertEquals("MEAL", list.get(1).benefits().get(0).code());
        assertNull(list.get(1).benefits().get(0).monthlyValue());
    }

    private static EmployeePayrollConfigHistory historyRow(Long id, String gross, String net, Long changedBy) {
        EmployeePayrollConfigHistory h = new EmployeePayrollConfigHistory();
        h.setId(id);
        h.setProfileUserId(10L);
        h.setPaysId(179L);
        h.setContractType("CDI");
        h.setCurrentGrossSalary(new BigDecimal(gross));
        h.setCurrentNetSalary(new BigDecimal(net));
        h.setReason("r" + id);
        h.setChangedBy(changedBy);
        h.setChangedAt(java.time.OffsetDateTime.now().minusDays(id));
        return h;
    }

    // ── getOrDefault ───────────────────────────────────────────────────────

    @Test
    void getOrDefault_neverConfigured_returnsDefaultWithNullSalaries() {
        when(repo.findByProfileUserId(10L)).thenReturn(Optional.empty());
        when(hrEmployeeClient.findEmployeeByUserId(10L)).thenReturn(Optional.empty());

        EmployeePayrollConfigDto dto = service().getOrDefault(10L);

        assertNull(dto.currentGrossSalary());
        assertNull(dto.currentNetSalary());
        assertEquals("CDI", dto.contractType());
        assertFalse(dto.familyFromHr());
    }

    @Test
    void getOrDefault_legacyRhContractCode_isNormalised() {
        when(repo.findByProfileUserId(10L)).thenReturn(Optional.empty());
        when(hrEmployeeClient.findEmployeeByUserId(10L)).thenReturn(Optional.of(
                new HrEmployeeClient.HrEmployeeDto(10L, 179L, "FIXED_TERM", null, null, null, false, 0)));
        when(fxService.resolve(179L)).thenReturn(TND);

        assertEquals("CDD", service().getOrDefault(10L).contractType());
    }

    @Test
    void getOrDefault_familyComesFromRh_overStoredCopy() {
        EmployeePayrollConfig existing = new EmployeePayrollConfig();
        existing.setProfileUserId(10L);
        existing.setPaysId(179L);
        existing.setIsMarried(false);
        existing.setChildrenCount(0);
        when(repo.findByProfileUserId(10L)).thenReturn(Optional.of(existing));
        when(hrEmployeeClient.findEmployeeByUserId(10L)).thenReturn(Optional.of(
                new HrEmployeeClient.HrEmployeeDto(10L, 179L, "CDI", null, null, null, true, 3)));
        when(fxService.resolve(179L)).thenReturn(TND);

        EmployeePayrollConfigDto dto = service().getOrDefault(10L);

        assertTrue(dto.isMarried());
        assertEquals(3, dto.childrenCount());
        assertTrue(dto.familyFromHr());
    }

    // ── calculateNet: engine preview with the on-screen inputs ─────────────

    @Test
    void calculateNet_usesEnginePreview_withOnScreenCountryAndContract() {
        when(fxService.resolve(179L)).thenReturn(TND);
        engineNet("1579.55");

        CalculateNetResponse response = service().calculateNet(10L,
                new CalculateNetRequest(new BigDecimal("2070.28"), null, 179L, "PERMANENT"));

        assertEquals(new BigDecimal("1579.55"), response.netInHand());
        ArgumentCaptor<RunPayrollRequest> run = ArgumentCaptor.forClass(RunPayrollRequest.class);
        verify(orchestrator).preview(run.capture(), eq(new BigDecimal("2070.28")));
        assertEquals(179L, run.getValue().getPaysId());
        assertEquals("CDI", run.getValue().getContractTypeCode());
        assertEquals(10L, run.getValue().getEmployeeId());
        // Country and contract came with the request: no lookup of the saved config needed.
        verifyNoInteractions(repo);
    }

    @Test
    void calculateNet_convertsForeignInputCurrency() {
        when(fxService.resolve(179L)).thenReturn(TND);
        when(fxEntryService.convert(new BigDecimal("1000"), "EUR", "TND")).thenReturn(new BigDecimal("3400.00"));
        engineNet("2600");

        service().calculateNet(10L, new CalculateNetRequest(new BigDecimal("1000"), "EUR", 179L, "CDI"));

        verify(orchestrator).preview(any(RunPayrollRequest.class), eq(new BigDecimal("3400.00")));
    }

    @Test
    void calculateNet_nativeCurrency_isNoOp() {
        when(fxService.resolve(179L)).thenReturn(new FxSnapshotService.FxSnapshot("EUR", BigDecimal.ONE, null, null));
        when(fxEntryService.isNativeCurrency(179L, "TND")).thenReturn(true);
        engineNet("1579.55");

        service().calculateNet(10L, new CalculateNetRequest(new BigDecimal("2070.28"), "TND", 179L, "CDI"));

        verify(orchestrator).preview(any(RunPayrollRequest.class), eq(new BigDecimal("2070.28")));
        verify(fxEntryService, never()).convert(any(BigDecimal.class), any(), any());
    }

    @Test
    void calculateNet_noCountryAnywhere_throws() {
        when(repo.findByProfileUserId(10L)).thenReturn(Optional.empty());
        when(hrEmployeeClient.findEmployeeByUserId(10L)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service().calculateNet(10L,
                new CalculateNetRequest(new BigDecimal("5000"), null, null, null)));
        verifyNoInteractions(orchestrator);
    }

    // ── upsert ─────────────────────────────────────────────────────────────

    @Test
    void upsert_netIsRecomputedByEngine_notTakenFromClient_andPushedToRh() {
        when(repo.findByProfileUserId(10L)).thenReturn(Optional.empty());
        when(repo.save(any(EmployeePayrollConfig.class))).thenAnswer(inv -> inv.getArgument(0));
        when(fxService.resolve(179L)).thenReturn(TND);
        noActiveOldModelRules();
        engineNet("3800.00");

        // Client sends a stale net (computed with other benefits/contract): ignored.
        EmployeePayrollConfigDto result = service().upsert(10L, req("CDI", "5000.000", "9999", null), 99L);

        assertEquals(new BigDecimal("5000.000"), result.currentGrossSalary());
        assertEquals(new BigDecimal("3800.00"), result.currentNetSalary());
        ArgumentCaptor<EmployeePayrollConfigHistory> history = ArgumentCaptor.forClass(EmployeePayrollConfigHistory.class);
        verify(historyRepo).save(history.capture());
        assertEquals(new BigDecimal("3800.00"), history.getValue().getCurrentNetSalary());
        verify(hrEmployeeClient).pushCurrentNetSalary(10L, new BigDecimal("3800.00"));
    }

    @Test
    void upsert_engineUnavailable_keepsClientNet() {
        when(repo.findByProfileUserId(10L)).thenReturn(Optional.empty());
        when(repo.save(any(EmployeePayrollConfig.class))).thenAnswer(inv -> inv.getArgument(0));
        when(fxService.resolve(179L)).thenReturn(TND);
        noActiveOldModelRules();
        when(orchestrator.preview(any(), any())).thenThrow(new IllegalStateException("No active PayrollCountry"));

        EmployeePayrollConfigDto result = service().upsert(10L, req("CDI", "5000", "3700", null), 99L);

        assertEquals(new BigDecimal("3700"), result.currentNetSalary());
    }

    @Test
    void upsert_convertsAndPersistsOriginalCurrency() {
        when(repo.findByProfileUserId(10L)).thenReturn(Optional.empty());
        when(repo.save(any(EmployeePayrollConfig.class))).thenAnswer(inv -> inv.getArgument(0));
        when(fxService.resolve(179L)).thenReturn(TND);
        noActiveOldModelRules();
        when(fxEntryService.convert(new BigDecimal("1000"), "EUR", "TND")).thenReturn(new BigDecimal("3400.00"));
        engineNet("2600");

        EmployeePayrollConfigDto result = service().upsert(10L, req("CDI", "1000", null, "eur"), 99L);

        assertEquals(new BigDecimal("3400.00"), result.currentGrossSalary());
        assertEquals("EUR", result.inputCurrency());
        assertEquals(new BigDecimal("1000"), result.inputAmountOriginal());
        verify(orchestrator).preview(any(RunPayrollRequest.class), eq(new BigDecimal("3400.00")));
    }

    @Test
    void upsert_staleConvertedEcho_isNotConvertedASecondTime() {
        // Stored: typed 1000 EUR → 3400 TND. An older client shows 3400 next to "EUR" and
        // sends it back on the next save — that must NOT become 3400 EUR → 11 560 TND.
        EmployeePayrollConfig existing = new EmployeePayrollConfig();
        existing.setId(1L);
        existing.setProfileUserId(10L);
        existing.setPaysId(179L);
        existing.setContractType("CDI");
        existing.setCurrentGrossSalary(new BigDecimal("3400.00"));
        existing.setInputCurrency("EUR");
        existing.setInputAmountOriginal(new BigDecimal("1000"));
        when(repo.findByProfileUserId(10L)).thenReturn(Optional.of(existing));
        when(repo.save(any(EmployeePayrollConfig.class))).thenAnswer(inv -> inv.getArgument(0));
        when(fxService.resolve(179L)).thenReturn(TND);
        noActiveOldModelRules();
        engineNet("2600");

        EmployeePayrollConfigDto result = service().upsert(10L, req("CDI", "3400.00", null, "EUR"), 99L);

        assertEquals(new BigDecimal("3400.00"), result.currentGrossSalary());
        assertEquals("EUR", result.inputCurrency());
        assertEquals(new BigDecimal("1000"), result.inputAmountOriginal());
        verify(fxEntryService, never()).convert(any(BigDecimal.class), any(), any());
    }

    @Test
    void upsert_originalAmountResent_isConvertedNormally() {
        // The fixed clients send back the ORIGINAL typed amount: a normal conversion.
        EmployeePayrollConfig existing = new EmployeePayrollConfig();
        existing.setId(1L);
        existing.setProfileUserId(10L);
        existing.setPaysId(179L);
        existing.setCurrentGrossSalary(new BigDecimal("3400.00"));
        existing.setInputCurrency("EUR");
        existing.setInputAmountOriginal(new BigDecimal("1000"));
        when(repo.findByProfileUserId(10L)).thenReturn(Optional.of(existing));
        when(repo.save(any(EmployeePayrollConfig.class))).thenAnswer(inv -> inv.getArgument(0));
        when(fxService.resolve(179L)).thenReturn(TND);
        noActiveOldModelRules();
        when(fxEntryService.convert(new BigDecimal("1000"), "EUR", "TND")).thenReturn(new BigDecimal("3400.00"));
        engineNet("2600");

        EmployeePayrollConfigDto result = service().upsert(10L, req("CDI", "1000", null, "EUR"), 99L);

        assertEquals(new BigDecimal("3400.00"), result.currentGrossSalary());
    }

    @Test
    void upsert_nativeCurrency_storesNullInputFields() {
        when(repo.findByProfileUserId(10L)).thenReturn(Optional.empty());
        when(repo.save(any(EmployeePayrollConfig.class))).thenAnswer(inv -> inv.getArgument(0));
        when(fxService.resolve(179L)).thenReturn(new FxSnapshotService.FxSnapshot("EUR", BigDecimal.ONE, null, null));
        when(fxEntryService.isNativeCurrency(179L, "TND")).thenReturn(true);
        noActiveOldModelRules();
        engineNet("1579.55");

        EmployeePayrollConfigDto result = service().upsert(10L, req("CDI", "2070.280", null, "TND"), 99L);

        assertEquals(new BigDecimal("2070.280"), result.currentGrossSalary());
        assertNull(result.inputCurrency());
        assertNull(result.inputAmountOriginal());
        verify(fxEntryService, never()).convert(any(BigDecimal.class), any(), any());
    }

    @Test
    void upsert_familyIsTakenFromRh_notResetToSingle() {
        // The Rémunération tab never sent the family fields; the save used to reset them to
        // "single, 0 children". Now RH supplies them and they're cached on the config.
        EmployeePayrollConfig existing = new EmployeePayrollConfig();
        existing.setProfileUserId(10L);
        existing.setPaysId(179L);
        existing.setIsMarried(true);
        existing.setChildrenCount(2);
        when(repo.findByProfileUserId(10L)).thenReturn(Optional.of(existing));
        when(repo.save(any(EmployeePayrollConfig.class))).thenAnswer(inv -> inv.getArgument(0));
        when(fxService.resolve(179L)).thenReturn(TND);
        noActiveOldModelRules();
        engineNet("1500");
        when(hrEmployeeClient.findEmployeeByUserId(10L)).thenReturn(Optional.of(
                new HrEmployeeClient.HrEmployeeDto(10L, 179L, "CDI", null, null, null, true, 2)));

        EmployeePayrollConfigDto result = service().upsert(10L, req("CDI", "2000", null, null), 99L);

        assertTrue(result.isMarried());
        assertEquals(2, result.childrenCount());
        assertTrue(existing.getIsMarried());
        assertEquals(2, existing.getChildrenCount());
    }

    @Test
    void upsert_rhUnreachable_keepsCachedFamily() {
        EmployeePayrollConfig existing = new EmployeePayrollConfig();
        existing.setProfileUserId(10L);
        existing.setPaysId(179L);
        existing.setIsMarried(true);
        existing.setChildrenCount(2);
        when(repo.findByProfileUserId(10L)).thenReturn(Optional.of(existing));
        when(repo.save(any(EmployeePayrollConfig.class))).thenAnswer(inv -> inv.getArgument(0));
        when(fxService.resolve(179L)).thenReturn(TND);
        noActiveOldModelRules();
        engineNet("1500");
        when(hrEmployeeClient.findEmployeeByUserId(10L)).thenReturn(Optional.empty());

        EmployeePayrollConfigDto result = service().upsert(10L, req("CDI", "2000", null, null), 99L);

        assertTrue(result.isMarried());
        assertEquals(2, result.childrenCount());
        assertFalse(result.familyFromHr());
    }

    @Test
    void upsert_legacyContract_isNormalised_andUnpricedContractIsRefused() {
        when(repo.findByProfileUserId(10L)).thenReturn(Optional.empty());
        when(repo.save(any(EmployeePayrollConfig.class))).thenAnswer(inv -> inv.getArgument(0));
        when(fxService.resolve(179L)).thenReturn(TND);
        ParameterSet ps = new ParameterSet();
        ps.setId(55L);
        when(paramSetService.loadActiveEntity(179L)).thenReturn(ps);
        SocialChargeRate cdi = new SocialChargeRate();
        cdi.setContractType("CDI");
        SocialChargeRate cdd = new SocialChargeRate();
        cdd.setContractType("CDD");
        when(paramSetService.loadRates(55L)).thenReturn(List.of(cdi, cdd));
        engineNet("1500");

        assertEquals("CDI", service().upsert(10L, req("PERMANENT", "2000", null, null), 99L).contractType());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service().upsert(10L, req("FREELANCE", "2000", null, null), 99L));
        assertTrue(ex.getMessage().contains("CDD, CDI"));
    }
}
