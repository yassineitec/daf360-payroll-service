package com.daf360.payroll.modules.calibration.repository;

import com.daf360.payroll.modules.calibration.entity.PayrollBudgetLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PayrollBudgetLineRepository extends JpaRepository<PayrollBudgetLine, Long> {

    List<PayrollBudgetLine> findByPaysIdOrderByPeriodDesc(Long paysId);

    List<PayrollBudgetLine> findByParameterSetId(Long parameterSetId);
}
