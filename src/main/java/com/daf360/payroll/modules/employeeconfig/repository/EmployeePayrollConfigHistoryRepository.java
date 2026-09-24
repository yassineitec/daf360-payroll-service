package com.daf360.payroll.modules.employeeconfig.repository;

import com.daf360.payroll.modules.employeeconfig.entity.EmployeePayrollConfigHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EmployeePayrollConfigHistoryRepository extends JpaRepository<EmployeePayrollConfigHistory, Long> {
    List<EmployeePayrollConfigHistory> findByProfileUserIdOrderByChangedAtDesc(Long profileUserId);
}
