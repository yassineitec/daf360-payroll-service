package com.daf360.payroll.modules.employeeconfig.repository;

import com.daf360.payroll.modules.employeeconfig.entity.EmployeePayrollConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EmployeePayrollConfigRepository extends JpaRepository<EmployeePayrollConfig, Long> {
    Optional<EmployeePayrollConfig> findByProfileUserId(Long profileUserId);
}
