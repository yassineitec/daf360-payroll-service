package com.daf360.payroll.modules.employeeconfig.service;

import com.daf360.payroll.modules.employeeconfig.dto.CalculateNetResponse;
import com.daf360.payroll.modules.employeeconfig.dto.EmployeePayrollConfigDto;
import com.daf360.payroll.modules.employeeconfig.dto.UpsertEmployeePayrollConfigRequest;
import com.daf360.payroll.modules.employeeconfig.entity.EmployeePayrollConfig;
import com.daf360.payroll.modules.employeeconfig.repository.EmployeePayrollConfigHistoryRepository;
import com.daf360.payroll.modules.employeeconfig.repository.EmployeePayrollConfigRepository;
import com.daf360.payroll.modules.parameterset.entity.BenefitCatalogue;
import com.daf360.payroll.modules.parameterset.entity.ParameterSet;
import com.daf360.payroll.modules.parameterset.service.ParameterSetService;
import com.daf360.payroll.engine.PayrollSimulatorService;
import com.daf360.payroll.modules.simulation.client.HrEmployeeClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmployeePayrollConfigServiceTest {

    @Mock EmployeePayrollConfigRepository repo;
    @Mock EmployeePayrollConfigHistoryRepository historyRepo;
    @Mock HrEmployeeClient hrEmployeeClient;
    @Mock ParameterSetService paramSetService;
    @Mock PayrollSimulatorService simulatorService;
    @Mock com.daf360.payroll.engine.FxSnapshotService fxService;
    @Mock com.daf360.payroll.modules.ref.service.FxEntryConversionService fxEntryService;

    private EmployeePayrollConfigService service() {
        return new EmployeePayrollConfigService(
                repo, historyRepo, hrEmployeeClient, new ObjectMapper(), paramSetService, simulatorService,
                fxService, fxEntryService);
    }

    @Test
    void getOrDefault_neverConfigured_returnsDefaultWithNullGrossAndNetSalary() {
        when(repo.findByProfileUserId(10L)).thenReturn(Optional.empty());
        when(hrEmployeeClient.findEmployeeByUserId(10L)).thenReturn(Optional.empty());

        EmployeePayrollConfigDto dto = service().getOrDefault(10L);

        assertNull(dto.currentGrossSalary());
        assertNull(dto.currentNetSalary());
    }

    @Test
    void upsert_savesGrossSalaryOnBothEntityAndHistory() {
        when(repo.findByProfileUserId(10L)).thenReturn(Optional.empty());
        when(repo.save(any(EmployeePayrollConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        UpsertEmployeePayrollConfigRequest req = new UpsertEmployeePayrollConfigRequest(
                179L, "CDI", List.of(), new BigDecimal("5000.000"), new BigDecimal("3800.000"),
                false, 0, "Ajustement", null);
        when(fxService.resolve(179L)).thenReturn(new com.daf360.payroll.engine.FxSnapshotService.FxSnapshot("EUR", BigDecimal.ONE, new BigDecimal("1.08"), new BigDecimal("0.95")));

        EmployeePayrollConfigDto result = service().upsert(10L, req, 99L);

        assertEquals(new BigDecimal("5000.000"), result.currentGrossSalary());
        assertEquals(new BigDecimal("3800.000"), result.currentNetSalary());

        ArgumentCaptor<com.daf360.payroll.modules.employeeconfig.entity.EmployeePayrollConfigHistory> historyCaptor =
                ArgumentCaptor.forClass(com.daf360.payroll.modules.employeeconfig.entity.EmployeePayrollConfigHistory.class);
        org.mockito.Mockito.verify(historyRepo).save(historyCaptor.capture());
        assertEquals(new BigDecimal("5000.000"), historyCaptor.getValue().getCurrentGrossSalary());
    }

    @Test
    void calculateNet_happyPath_returnsNetFromSimulatorAndFiltersBenefitsAndForwardsExactArgs() {
        EmployeePayrollConfig existing = new EmployeePayrollConfig();
        existing.setProfileUserId(10L);
        existing.setPaysId(179L);
        existing.setContractType("CDI");
        existing.setSelectedBenefitCodes("[\"MEAL\"]");
        when(repo.findByProfileUserId(10L)).thenReturn(Optional.of(existing));
        when(fxService.resolve(179L)).thenReturn(new com.daf360.payroll.engine.FxSnapshotService.FxSnapshot("EUR", BigDecimal.ONE, new BigDecimal("1.08"), new BigDecimal("0.95")));

        ParameterSet ps = new ParameterSet();
        ps.setId(55L);
        when(paramSetService.loadActiveEntity(179L)).thenReturn(ps);
        when(paramSetService.loadRates(55L)).thenReturn(List.of());
        when(paramSetService.loadRubriques(55L)).thenReturn(List.of());

        BenefitCatalogue meal = new BenefitCatalogue();
        meal.setBenefitCode("MEAL");
        BenefitCatalogue transport = new BenefitCatalogue();
        transport.setBenefitCode("TRANSPORT");
        when(paramSetService.loadBenefits(55L)).thenReturn(List.of(meal, transport));

        PayrollSimulatorService.PayrollResult simulatedResult = new PayrollSimulatorService.PayrollResult(
                new BigDecimal("3800.000"), null, null, null, null, null, null, null,
                null, null, null, 0, true, List.of());
        when(simulatorService.computeFromGross(
                any(BigDecimal.class), any(ParameterSet.class), anyList(), anyList(), anyList(),
                any(String.class), anyInt(), any(), any()))
                .thenReturn(simulatedResult);

        CalculateNetResponse response = service().calculateNet(10L, new BigDecimal("6000.000"), null);

        assertEquals(new BigDecimal("3800.000"), response.netInHand());

        ArgumentCaptor<BigDecimal> grossCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<String> contractTypeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> joursCaptor = ArgumentCaptor.forClass(Integer.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BenefitCatalogue>> benefitsCaptor = ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(simulatorService).computeFromGross(
                grossCaptor.capture(), any(ParameterSet.class), anyList(), benefitsCaptor.capture(), anyList(),
                contractTypeCaptor.capture(), joursCaptor.capture(), any(), any());

        assertEquals(new BigDecimal("6000.000"), grossCaptor.getValue());
        assertEquals("CDI", contractTypeCaptor.getValue());
        assertEquals(22, joursCaptor.getValue());
        assertEquals(1, benefitsCaptor.getValue().size());
        assertEquals("MEAL", benefitsCaptor.getValue().get(0).getBenefitCode());
    }

    /** Jamais enregistré, pas de pays RH : les valeurs du formulaire suffisent au calcul
     *  (net → brut), et ce sont elles qui partent au moteur. */
    @Test
    void calculateGross_neverSaved_usesFormValues() {
        when(repo.findByProfileUserId(10L)).thenReturn(Optional.empty());
        when(hrEmployeeClient.findEmployeeByUserId(10L)).thenReturn(Optional.empty());
        when(fxService.resolve(179L)).thenReturn(new com.daf360.payroll.engine.FxSnapshotService.FxSnapshot("TND", BigDecimal.ONE, new BigDecimal("0.3"), new BigDecimal("0.28")));

        ParameterSet ps = new ParameterSet();
        ps.setId(55L);
        when(paramSetService.loadActiveEntity(179L)).thenReturn(ps);
        when(paramSetService.loadRates(55L)).thenReturn(List.of());
        when(paramSetService.loadRubriques(55L)).thenReturn(List.of());
        BenefitCatalogue meal = new BenefitCatalogue();
        meal.setBenefitCode("MEAL");
        BenefitCatalogue transport = new BenefitCatalogue();
        transport.setBenefitCode("TRANSPORT");
        when(paramSetService.loadBenefits(55L)).thenReturn(List.of(meal, transport));

        when(simulatorService.computeFromNet(
                any(BigDecimal.class), any(ParameterSet.class), anyList(), anyList(), anyList(),
                any(String.class), anyInt(), any(), any()))
                .thenReturn(new PayrollSimulatorService.PayrollResult(
                        new BigDecimal("1580.000"), null, null, new BigDecimal("2104.567"), null, null, null, null,
                        null, null, null, 7, true, List.of()));

        com.daf360.payroll.modules.employeeconfig.dto.CalculateGrossResponse response = service().calculateGross(
                10L, new BigDecimal("1580"), null,
                new com.daf360.payroll.modules.employeeconfig.dto.CalculationFormValues(
                        179L, "CDD", List.of("MEAL"), true, 2));

        assertEquals(new BigDecimal("2104.57"), response.grossSalary());
        assertEquals(new BigDecimal("1580.000"), response.netInHand());

        ArgumentCaptor<String> contract = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Boolean> married = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<Integer> children = ArgumentCaptor.forClass(Integer.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BenefitCatalogue>> benefits = ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(simulatorService).computeFromNet(
                eq(new BigDecimal("1580")), eq(ps), anyList(), benefits.capture(), anyList(),
                contract.capture(), eq(22), married.capture(), children.capture());
        assertEquals("CDD", contract.getValue());
        assertEquals(true, married.getValue());
        assertEquals(2, children.getValue());
        assertEquals(List.of("MEAL"), benefits.getValue().stream().map(BenefitCatalogue::getBenefitCode).toList());
    }

    @Test
    void calculateNet_noPaysConfigured_throwsAndNeverCallsSimulationCollaborators() {
        when(repo.findByProfileUserId(10L)).thenReturn(Optional.empty());
        when(hrEmployeeClient.findEmployeeByUserId(10L)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> service().calculateNet(10L, new BigDecimal("5000.000"), null));

        verifyNoInteractions(paramSetService);
        verifyNoInteractions(simulatorService);
    }

    @Test
    void calculateNet_convertsInputCurrencyBeforeCallingSimulator() {
        EmployeePayrollConfig existing = new EmployeePayrollConfig();
        existing.setProfileUserId(10L);
        existing.setPaysId(179L);
        existing.setContractType("CDI");
        when(repo.findByProfileUserId(10L)).thenReturn(Optional.of(existing));
        when(fxService.resolve(179L)).thenReturn(
                new com.daf360.payroll.engine.FxSnapshotService.FxSnapshot("EUR", BigDecimal.ONE, new BigDecimal("1.08"), new BigDecimal("0.95")));

        ParameterSet ps = new ParameterSet();
        ps.setId(55L);
        when(paramSetService.loadActiveEntity(179L)).thenReturn(ps);
        when(paramSetService.loadRates(55L)).thenReturn(List.of());
        when(paramSetService.loadRubriques(55L)).thenReturn(List.of());
        when(paramSetService.loadBenefits(55L)).thenReturn(List.of());

        when(fxEntryService.convert(new BigDecimal("1500.000"), "USD", "EUR"))
                .thenReturn(new BigDecimal("450.00"));

        PayrollSimulatorService.PayrollResult simulatedResult = new PayrollSimulatorService.PayrollResult(
                new BigDecimal("380.00"), null, null, null, null, null, null, null,
                null, null, null, 0, true, List.of());
        when(simulatorService.computeFromGross(
                eq(new BigDecimal("450.00")), any(ParameterSet.class), anyList(), anyList(), anyList(),
                any(String.class), anyInt(), any(), any()))
                .thenReturn(simulatedResult);

        CalculateNetResponse response = service().calculateNet(10L, new BigDecimal("1500.000"), "USD");

        assertEquals(new BigDecimal("380.00"), response.netInHand());
    }

    @Test
    void calculateNet_nativeCurrencyForTunisia_treatedAsNoOp() {
        // Tunisia's platform "working currency" (EUR) is itself a label for real TND, calibrated
        // 1:1 -- picking "TND" from the entry-currency dropdown must behave exactly like not
        // picking a currency at all, not trigger a real market-rate conversion.
        EmployeePayrollConfig existing = new EmployeePayrollConfig();
        existing.setProfileUserId(10L);
        existing.setPaysId(179L);
        existing.setContractType("CDI");
        when(repo.findByProfileUserId(10L)).thenReturn(Optional.of(existing));
        when(fxService.resolve(179L)).thenReturn(
                new com.daf360.payroll.engine.FxSnapshotService.FxSnapshot("EUR", BigDecimal.ONE, new BigDecimal("1.08"), new BigDecimal("0.95")));

        ParameterSet ps = new ParameterSet();
        ps.setId(55L);
        when(paramSetService.loadActiveEntity(179L)).thenReturn(ps);
        when(paramSetService.loadRates(55L)).thenReturn(List.of());
        when(paramSetService.loadRubriques(55L)).thenReturn(List.of());
        when(paramSetService.loadBenefits(55L)).thenReturn(List.of());
        when(fxEntryService.isNativeCurrency(179L, "TND")).thenReturn(true);

        PayrollSimulatorService.PayrollResult simulatedResult = new PayrollSimulatorService.PayrollResult(
                new BigDecimal("1579.5523"), null, null, null, null, null, null, null,
                null, null, null, 0, true, List.of());
        when(simulatorService.computeFromGross(
                eq(new BigDecimal("2070.28")), any(ParameterSet.class), anyList(), anyList(), anyList(),
                any(String.class), anyInt(), any(), any()))
                .thenReturn(simulatedResult);

        CalculateNetResponse response = service().calculateNet(10L, new BigDecimal("2070.28"), "TND");

        assertEquals(new BigDecimal("1579.5523"), response.netInHand());
        org.mockito.Mockito.verify(fxEntryService, org.mockito.Mockito.never())
                .convert(any(BigDecimal.class), any(), any());
    }

    @Test
    void upsert_convertsAndPersistsOriginalCurrencyOnBothEntityAndHistory() {
        when(repo.findByProfileUserId(10L)).thenReturn(Optional.empty());
        when(repo.save(any(EmployeePayrollConfig.class))).thenAnswer(inv -> inv.getArgument(0));
        when(fxService.resolve(179L)).thenReturn(
                new com.daf360.payroll.engine.FxSnapshotService.FxSnapshot("EUR", BigDecimal.ONE, new BigDecimal("1.08"), new BigDecimal("0.95")));
        when(fxEntryService.convert(new BigDecimal("1500.000"), "USD", "EUR"))
                .thenReturn(new BigDecimal("450.00"));

        UpsertEmployeePayrollConfigRequest req = new UpsertEmployeePayrollConfigRequest(
                179L, "CDI", List.of(), new BigDecimal("1500.000"), new BigDecimal("1200.000"),
                false, 0, "Ajustement", "USD");

        EmployeePayrollConfigDto result = service().upsert(10L, req, 99L);

        assertEquals(new BigDecimal("450.00"), result.currentGrossSalary());
        assertEquals("USD", result.inputCurrency());
        assertEquals(new BigDecimal("1500.000"), result.inputAmountOriginal());
        assertEquals("EUR", result.workingCurrency());

        ArgumentCaptor<com.daf360.payroll.modules.employeeconfig.entity.EmployeePayrollConfigHistory> historyCaptor =
                ArgumentCaptor.forClass(com.daf360.payroll.modules.employeeconfig.entity.EmployeePayrollConfigHistory.class);
        org.mockito.Mockito.verify(historyRepo).save(historyCaptor.capture());
        assertEquals("USD", historyCaptor.getValue().getInputCurrency());
        assertEquals(new BigDecimal("1500.000"), historyCaptor.getValue().getInputAmountOriginal());
    }

    @Test
    void upsert_nativeCurrencyForTunisia_treatedAsNoOp() {
        when(repo.findByProfileUserId(10L)).thenReturn(Optional.empty());
        when(repo.save(any(EmployeePayrollConfig.class))).thenAnswer(inv -> inv.getArgument(0));
        when(fxService.resolve(179L)).thenReturn(
                new com.daf360.payroll.engine.FxSnapshotService.FxSnapshot("EUR", BigDecimal.ONE, new BigDecimal("1.08"), new BigDecimal("0.95")));
        when(fxEntryService.isNativeCurrency(179L, "TND")).thenReturn(true);

        UpsertEmployeePayrollConfigRequest req = new UpsertEmployeePayrollConfigRequest(
                179L, "CDI", List.of(), new BigDecimal("2070.280"), new BigDecimal("1579.552"),
                false, 0, "Ajustement", "TND");

        EmployeePayrollConfigDto result = service().upsert(10L, req, 99L);

        assertEquals(new BigDecimal("2070.280"), result.currentGrossSalary());
        assertNull(result.inputCurrency());
        assertNull(result.inputAmountOriginal());
        org.mockito.Mockito.verify(fxEntryService, org.mockito.Mockito.never())
                .convert(any(BigDecimal.class), any(), any());
    }

    @Test
    void upsert_sameAsWorkingCurrency_storesNullInputFields() {
        when(repo.findByProfileUserId(10L)).thenReturn(Optional.empty());
        when(repo.save(any(EmployeePayrollConfig.class))).thenAnswer(inv -> inv.getArgument(0));
        when(fxService.resolve(179L)).thenReturn(
                new com.daf360.payroll.engine.FxSnapshotService.FxSnapshot("EUR", BigDecimal.ONE, new BigDecimal("1.08"), new BigDecimal("0.95")));

        UpsertEmployeePayrollConfigRequest req = new UpsertEmployeePayrollConfigRequest(
                179L, "CDI", List.of(), new BigDecimal("2500.000"), new BigDecimal("1900.000"),
                false, 0, "Ajustement", null);

        EmployeePayrollConfigDto result = service().upsert(10L, req, 99L);

        assertEquals(new BigDecimal("2500.000"), result.currentGrossSalary());
        assertNull(result.inputCurrency());
        assertNull(result.inputAmountOriginal());
        org.mockito.Mockito.verifyNoInteractions(fxEntryService);
    }
}
