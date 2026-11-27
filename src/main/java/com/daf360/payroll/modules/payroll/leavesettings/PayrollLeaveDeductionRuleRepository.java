package com.daf360.payroll.modules.payroll.leavesettings;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PayrollLeaveDeductionRuleRepository extends JpaRepository<PayrollLeaveDeductionRule, Long> {

    List<PayrollLeaveDeductionRule> findByPaysId(Long paysId);

    Optional<PayrollLeaveDeductionRule> findByPaysIdAndAbsenceTypeCode(Long paysId, String absenceTypeCode);
}
