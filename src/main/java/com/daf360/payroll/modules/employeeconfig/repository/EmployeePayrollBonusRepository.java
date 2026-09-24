package com.daf360.payroll.modules.employeeconfig.repository;

import com.daf360.payroll.modules.employeeconfig.entity.EmployeePayrollBonus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EmployeePayrollBonusRepository extends JpaRepository<EmployeePayrollBonus, Long> {
    List<EmployeePayrollBonus> findByProfileUserIdOrderByPeriodYearDescPeriodMonthDesc(Long profileUserId);
}
