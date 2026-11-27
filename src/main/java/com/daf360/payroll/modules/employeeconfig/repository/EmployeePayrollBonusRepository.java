package com.daf360.payroll.modules.employeeconfig.repository;

import com.daf360.payroll.modules.employeeconfig.entity.EmployeePayrollBonus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EmployeePayrollBonusRepository extends JpaRepository<EmployeePayrollBonus, Long> {
    List<EmployeePayrollBonus> findByProfileUserIdOrderByPeriodYearDescPeriodMonthDesc(Long profileUserId);

    /** The month's one-off bonuses — added to the gross by the payroll run (« Prime exceptionnelle »). */
    List<EmployeePayrollBonus> findByProfileUserIdAndPeriodYearAndPeriodMonthOrderByIdAsc(
            Long profileUserId, Integer periodYear, Integer periodMonth);
}
