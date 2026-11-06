package com.daf360.payroll.modules.employeeconfig.repository;

import com.daf360.payroll.modules.employeeconfig.entity.EmployeePayrollConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface EmployeePayrollConfigRepository extends JpaRepository<EmployeePayrollConfig, Long> {
    Optional<EmployeePayrollConfig> findByProfileUserId(Long profileUserId);

    List<EmployeePayrollConfig> findByProfileUserIdIn(Collection<Long> profileUserIds);
}
