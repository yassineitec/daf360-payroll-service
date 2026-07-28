package com.daf360.payroll.modules.payroll.repository;

import com.daf360.payroll.modules.payroll.entity.PayrollForexSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PayrollForexSnapshotRepository extends JpaRepository<PayrollForexSnapshot, Long> {

    List<PayrollForexSnapshot> findByPayrollResultId(Long resultId);
}
