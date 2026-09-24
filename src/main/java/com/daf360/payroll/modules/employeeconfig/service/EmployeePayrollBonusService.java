package com.daf360.payroll.modules.employeeconfig.service;

import com.daf360.payroll.modules.employeeconfig.dto.CreateEmployeePayrollBonusRequest;
import com.daf360.payroll.modules.employeeconfig.dto.EmployeePayrollBonusDto;
import com.daf360.payroll.modules.employeeconfig.entity.EmployeePayrollBonus;
import com.daf360.payroll.modules.employeeconfig.repository.EmployeePayrollBonusRepository;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * "Primes exceptionnelles" — one-off, per-employee bonuses. Record only: not fed into
 * PayrollSimulatorService/TopologicalEvaluator (see
 * docs/superpowers/specs/2026-09-23-employee-config-enhancements-design.md, Non-Goals).
 * Added or deleted, never edited in place.
 */
@Service
public class EmployeePayrollBonusService {

    private final EmployeePayrollBonusRepository repo;

    public EmployeePayrollBonusService(EmployeePayrollBonusRepository repo) {
        this.repo = repo;
    }

    public List<EmployeePayrollBonusDto> list(Long profileUserId) {
        return repo.findByProfileUserIdOrderByPeriodYearDescPeriodMonthDesc(profileUserId).stream()
                .map(this::toDto)
                .toList();
    }

    public EmployeePayrollBonusDto create(Long profileUserId, CreateEmployeePayrollBonusRequest req, Long createdBy) {
        EmployeePayrollBonus entity = new EmployeePayrollBonus();
        entity.setProfileUserId(profileUserId);
        entity.setAmount(req.amount());
        entity.setCurrency(req.currency());
        entity.setPeriodMonth(req.periodMonth());
        entity.setPeriodYear(req.periodYear());
        entity.setLabel(req.label());
        entity.setComment(req.comment());
        entity.setCreatedBy(createdBy);
        entity.setCreatedAt(OffsetDateTime.now());
        return toDto(repo.save(entity));
    }

    public void delete(Long profileUserId, Long bonusId) {
        EmployeePayrollBonus bonus = repo.findById(bonusId)
                .filter(b -> b.getProfileUserId().equals(profileUserId))
                .orElseThrow(() -> new java.util.NoSuchElementException("Bonus not found: " + bonusId));
        repo.deleteById(bonus.getId());
    }

    private EmployeePayrollBonusDto toDto(EmployeePayrollBonus e) {
        return new EmployeePayrollBonusDto(
                e.getId(), e.getProfileUserId(), e.getAmount(), e.getCurrency(),
                e.getPeriodMonth(), e.getPeriodYear(), e.getLabel(), e.getComment(),
                e.getCreatedBy(), e.getCreatedAt());
    }
}
