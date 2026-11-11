package com.daf360.payroll.modules.employeeconfig.service;

import com.daf360.payroll.modules.employeeconfig.dto.EmployeePayrollConfigDto;
import com.daf360.payroll.modules.employeeconfig.dto.RemunerationSimulationDto;
import com.daf360.payroll.modules.employeeconfig.dto.SalaryChangeDtos.SalaryChangeDto;
import com.daf360.payroll.modules.employeeconfig.dto.SalaryChangeDtos.SubmitSalaryChangeRequest;
import com.daf360.payroll.modules.employeeconfig.dto.UpsertEmployeePayrollConfigRequest;
import com.daf360.payroll.modules.employeeconfig.entity.SalaryChangeRequest;
import com.daf360.payroll.modules.employeeconfig.repository.SalaryChangeRequestRepository;
import com.daf360.payroll.modules.ref.repository.UsersRefRepository;
import com.daf360.payroll.modules.ref.service.UserContextService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SalaryChangeRequestServiceTest {

    @Mock SalaryChangeRequestRepository repo;
    @Mock EmployeePayrollConfigService configService;
    @Mock RemunerationSimulationService simulationService;
    @Mock UsersRefRepository usersRefRepo;
    @Mock UserContextService userContext;

    private SalaryChangeRequestService service() {
        return new SalaryChangeRequestService(repo, configService, simulationService, usersRefRepo, userContext,
                new ObjectMapper().findAndRegisterModules());
    }

    private static EmployeePayrollConfigDto config(OffsetDateTime updatedAt, String gross) {
        return new EmployeePayrollConfigDto(10L, 179L, "CDI", List.of(), gross == null ? null : new BigDecimal(gross),
                gross == null ? null : new BigDecimal("2000"), false, 0, 1L, updatedAt, null, null, "TND", true, null);
    }

    private static RemunerationSimulationDto sim(String base, String net, String cost) {
        RemunerationSimulationDto.Breakdown b = new RemunerationSimulationDto.Breakdown(
                new BigDecimal(base), BigDecimal.ZERO, new BigDecimal(base), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ONE, new BigDecimal(base), BigDecimal.ONE, new BigDecimal(net), BigDecimal.ONE,
                new BigDecimal(cost), true);
        return new RemunerationSimulationDto("TND", "CDI", false, 0, 2026, 12, List.of(), List.of(), b, null);
    }

    private static SubmitSalaryChangeRequest submit(String gross) {
        return new SubmitSalaryChangeRequest(179L, "CDI", List.of("MEAL"), new BigDecimal(gross), null,
                LocalDate.of(2026, 12, 1), "Augmentation annuelle");
    }

    private void saved() {
        when(repo.save(any())).thenAnswer(inv -> {
            SalaryChangeRequest r = inv.getArgument(0);
            if (r.getId() == null) r.setId(500L);
            return r;
        });
    }

    @Test
    void submit_createsAPendingRequest_withCostBeforeAndAfter_andAppliesNothing() {
        when(configService.getOrDefault(10L)).thenReturn(config(OffsetDateTime.now(), "3000"));
        when(simulationService.simulate(eq(10L), any()))
                .thenReturn(sim("3300", "2400", "4100"))      // the new salary
                .thenReturn(sim("3000", "2200", "3700"));     // the current one
        when(repo.findFirstByProfileUserIdAndStatus(10L, "PENDING")).thenReturn(Optional.empty());
        saved();

        SalaryChangeDto dto = service().submit(10L, submit("3300"), 7L);

        assertEquals("PENDING", dto.status());
        assertEquals(0, new BigDecimal("3700").compareTo(dto.monthlyCostBefore()));
        assertEquals(0, new BigDecimal("4100").compareTo(dto.monthlyCostAfter()));
        assertEquals(0, new BigDecimal("2400").compareTo(dto.newNetSalary()));
        assertEquals(0, new BigDecimal("3000").compareTo(dto.currentGrossSalary()));
        assertNotNull(dto.simulation());
        verify(configService, never()).applyApproved(any(), any(), any(), any());
        verify(configService, never()).upsert(any(), any(), any());
    }

    @Test
    void resubmitWhilePending_updatesTheSameRequest() {
        when(configService.getOrDefault(10L)).thenReturn(config(OffsetDateTime.now(), "3000"));
        when(simulationService.simulate(eq(10L), any())).thenReturn(sim("3500", "2550", "4300"));
        SalaryChangeRequest pending = new SalaryChangeRequest();
        pending.setId(42L);
        pending.setProfileUserId(10L);
        pending.setStatus("PENDING");
        when(repo.findFirstByProfileUserIdAndStatus(10L, "PENDING")).thenReturn(Optional.of(pending));
        saved();

        SalaryChangeDto dto = service().submit(10L, submit("3500"), 7L);

        assertEquals(42L, dto.id());
        assertEquals(0, new BigDecimal("3500").compareTo(pending.getGrossSalary()));
    }

    @Test
    void firstSalary_isNotSubmitted_butSavedDirectly() {
        when(configService.getOrDefault(10L)).thenReturn(config(null, null));

        assertThrows(IllegalStateException.class, () -> service().submit(10L, submit("3000"), 7L));
        verify(repo, never()).save(any());
    }

    private SalaryChangeRequest pendingRow() {
        SalaryChangeRequest r = new SalaryChangeRequest();
        r.setId(42L);
        r.setProfileUserId(10L);
        r.setPaysId(179L);
        r.setStatus("PENDING");
        r.setContractType("CDI");
        r.setSelectedBenefitCodes("[\"MEAL\"]");
        r.setGrossSalary(new BigDecimal("3300"));
        r.setNewNetSalary(new BigDecimal("2400"));
        r.setReason("Augmentation annuelle");
        r.setEffectiveDate(LocalDate.of(2026, 12, 1));
        r.setSubmittedBy(7L);
        r.setSubmittedAt(OffsetDateTime.now());
        when(repo.findById(42L)).thenReturn(Optional.of(r));
        return r;
    }

    @Test
    void approve_appliesTheChange_asTheSubmitter_withTheApproverRecorded() {
        SalaryChangeRequest r = pendingRow();
        saved();

        SalaryChangeDto dto = service().approve(42L, "OK budget", 99L);

        ArgumentCaptor<UpsertEmployeePayrollConfigRequest> applied = ArgumentCaptor.forClass(UpsertEmployeePayrollConfigRequest.class);
        ArgumentCaptor<EmployeePayrollConfigService.Approval> approval = ArgumentCaptor.forClass(EmployeePayrollConfigService.Approval.class);
        verify(configService).applyApproved(eq(10L), applied.capture(), eq(7L), approval.capture());
        assertEquals(0, new BigDecimal("3300").compareTo(applied.getValue().currentGrossSalary()));
        assertEquals(List.of("MEAL"), applied.getValue().selectedBenefitCodes());
        assertEquals(LocalDate.of(2026, 12, 1), applied.getValue().effectiveDate());
        assertEquals(99L, approval.getValue().approvedBy());
        assertEquals(42L, approval.getValue().requestId());
        assertEquals("APPROVED", dto.status());
        assertEquals("APPROVED", r.getStatus());
    }

    @Test
    void reject_requiresAReason_andAppliesNothing() {
        pendingRow();
        saved();

        assertThrows(IllegalArgumentException.class, () -> service().reject(42L, " ", 99L));
        SalaryChangeDto dto = service().reject(42L, "Hors budget", 99L);

        assertEquals("REJECTED", dto.status());
        assertEquals("Hors budget", dto.decisionNotes());
        verify(configService, never()).applyApproved(any(), any(), any(), any());
    }

    @Test
    void noOneDecidesOnTheirOwnSalary() {
        pendingRow();

        assertThrows(AccessDeniedException.class, () -> service().approve(42L, null, 10L));
        assertThrows(AccessDeniedException.class, () -> service().reject(42L, "x", 10L));
    }

    @Test
    void decidedRequest_cannotBeDecidedAgain() {
        SalaryChangeRequest r = pendingRow();
        r.setStatus("APPROVED");

        assertThrows(IllegalStateException.class, () -> service().approve(42L, null, 99L));
        assertThrows(IllegalStateException.class, () -> service().cancel(42L, null, 7L));
    }
}
