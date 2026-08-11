package com.daf360.payroll.modules.calibration.repository;

import com.daf360.payroll.modules.calibration.entity.PayrollForecastOutput;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PayrollForecastOutputRepository extends JpaRepository<PayrollForecastOutput, Long> {

    List<PayrollForecastOutput> findByPaysIdOrderByPeriodDesc(Long paysId);

    List<PayrollForecastOutput> findByParameterSetId(Long parameterSetId);
}
