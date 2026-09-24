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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmployeePayrollConfigServiceTest {

    @Mock EmployeePayrollConfigRepository repo;
    @Mock EmployeePayrollConfigHistoryRepository historyRepo;
    @Mock HrEmployeeClient hrEmployeeClient;
    @Mock ParameterSetService paramSetService;
    @Mock PayrollSimulatorService simulatorService;

    private EmployeePayrollConfigService service() {
        return new EmployeePayrollConfigService(
                repo, historyRepo, hrEmployeeClient, new ObjectMapper(), paramSetService, simulatorService);
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
                179L, "CDI", List.of(), new BigDecimal("5000.000"), new BigDecimal("3800.000"), "Ajustement");

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
                any(String.class), anyInt()))
                .thenReturn(simulatedResult);

        CalculateNetResponse response = service().calculateNet(10L, new BigDecimal("6000.000"));

        assertEquals(new BigDecimal("3800.000"), response.netInHand());

        ArgumentCaptor<BigDecimal> grossCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<String> contractTypeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> joursCaptor = ArgumentCaptor.forClass(Integer.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BenefitCatalogue>> benefitsCaptor = ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(simulatorService).computeFromGross(
                grossCaptor.capture(), any(ParameterSet.class), anyList(), benefitsCaptor.capture(), anyList(),
                contractTypeCaptor.capture(), joursCaptor.capture());

        assertEquals(new BigDecimal("6000.000"), grossCaptor.getValue());
        assertEquals("CDI", contractTypeCaptor.getValue());
        assertEquals(22, joursCaptor.getValue());
        assertEquals(1, benefitsCaptor.getValue().size());
        assertEquals("MEAL", benefitsCaptor.getValue().get(0).getBenefitCode());
    }

    @Test
    void calculateNet_noPaysConfigured_throwsAndNeverCallsSimulationCollaborators() {
        when(repo.findByProfileUserId(10L)).thenReturn(Optional.empty());
        when(hrEmployeeClient.findEmployeeByUserId(10L)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> service().calculateNet(10L, new BigDecimal("5000.000")));

        verifyNoInteractions(paramSetService);
        verifyNoInteractions(simulatorService);
    }
}
