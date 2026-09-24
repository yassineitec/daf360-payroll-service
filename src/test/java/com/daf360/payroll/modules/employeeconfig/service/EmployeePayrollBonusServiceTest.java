package com.daf360.payroll.modules.employeeconfig.service;

import com.daf360.payroll.modules.employeeconfig.dto.CreateEmployeePayrollBonusRequest;
import com.daf360.payroll.modules.employeeconfig.dto.EmployeePayrollBonusDto;
import com.daf360.payroll.modules.employeeconfig.entity.EmployeePayrollBonus;
import com.daf360.payroll.modules.employeeconfig.repository.EmployeePayrollBonusRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmployeePayrollBonusServiceTest {

    @Mock EmployeePayrollBonusRepository repo;

    @Test
    void create_savesAllFieldsAndReturnsDto() {
        when(repo.save(any(EmployeePayrollBonus.class))).thenAnswer(inv -> {
            EmployeePayrollBonus b = inv.getArgument(0);
            b.setId(1L);
            return b;
        });

        EmployeePayrollBonusService service = new EmployeePayrollBonusService(repo);
        CreateEmployeePayrollBonusRequest req = new CreateEmployeePayrollBonusRequest(
                new BigDecimal("500.000"), "TND", 12, 2026, "Prime de fin d'année", "Décision comité");

        EmployeePayrollBonusDto result = service.create(10L, req, 99L);

        assertEquals(1L, result.id());
        assertEquals(10L, result.profileUserId());
        assertEquals(new BigDecimal("500.000"), result.amount());
        assertEquals("TND", result.currency());
        assertEquals(12, result.periodMonth());
        assertEquals(2026, result.periodYear());
        assertEquals("Prime de fin d'année", result.label());
        assertEquals(99L, result.createdBy());
    }

    @Test
    void list_ordersByPeriodDescending() {
        when(repo.findByProfileUserIdOrderByPeriodYearDescPeriodMonthDesc(10L)).thenReturn(List.of());

        List<EmployeePayrollBonusDto> result = new EmployeePayrollBonusService(repo).list(10L);

        assertEquals(0, result.size());
    }

    @Test
    void delete_matchingProfileUserId_deletesBonus() {
        EmployeePayrollBonus bonus = new EmployeePayrollBonus();
        bonus.setId(5L);
        bonus.setProfileUserId(10L);
        when(repo.findById(5L)).thenReturn(java.util.Optional.of(bonus));

        EmployeePayrollBonusService service = new EmployeePayrollBonusService(repo);
        service.delete(10L, 5L);

        org.mockito.Mockito.verify(repo).deleteById(5L);
    }

    @Test
    void delete_mismatchedProfileUserId_throwsAndNeverDeletes() {
        EmployeePayrollBonus bonus = new EmployeePayrollBonus();
        bonus.setId(5L);
        bonus.setProfileUserId(10L);
        when(repo.findById(5L)).thenReturn(java.util.Optional.of(bonus));

        EmployeePayrollBonusService service = new EmployeePayrollBonusService(repo);

        org.junit.jupiter.api.Assertions.assertThrows(java.util.NoSuchElementException.class,
                () -> service.delete(999L, 5L));

        org.mockito.Mockito.verify(repo, org.mockito.Mockito.never()).deleteById(org.mockito.ArgumentMatchers.anyLong());
    }
}
