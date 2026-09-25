package com.daf360.payroll.modules.salaryadvance.repository;

import com.daf360.payroll.modules.salaryadvance.entity.SalaryAdvanceHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SalaryAdvanceHistoryRepository extends JpaRepository<SalaryAdvanceHistory, Long> {

    List<SalaryAdvanceHistory> findBySalaryAdvanceIdOrderByCreatedAtAsc(Long salaryAdvanceId);
}
