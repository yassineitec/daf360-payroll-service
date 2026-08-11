package com.daf360.payroll.modules.payroll.repository;

import com.daf360.payroll.modules.payroll.entity.PayrollResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PayrollResultRepository extends JpaRepository<PayrollResult, Long> {

    List<PayrollResult> findByEmployeeIdOrderByPeriodYearDescPeriodMonthDesc(Long employeeId);

    Optional<PayrollResult> findByEmployeeIdAndPeriodYearAndPeriodMonth(Long employeeId, int year, int month);

    List<PayrollResult> findByCountryIdAndPeriodYearAndPeriodMonth(Long countryId, int periodYear, int periodMonth);
}
