package com.daf360.payroll.modules.calibration.repository;

import com.daf360.payroll.modules.calibration.entity.CalibrationCycle;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CalibrationCycleRepository extends JpaRepository<CalibrationCycle, Long> {

    List<CalibrationCycle> findByPaysIdOrderByPeriodDesc(Long paysId);

    Optional<CalibrationCycle> findByPaysIdAndPeriod(Long paysId, String period);

    List<CalibrationCycle> findByPaysIdAndStatus(Long paysId, String status);

    List<CalibrationCycle> findByStatus(String status);
}
