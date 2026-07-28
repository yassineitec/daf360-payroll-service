package com.daf360.payroll.modules.calibration.repository;

import com.daf360.payroll.modules.calibration.entity.CalibrationVariance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CalibrationVarianceRepository extends JpaRepository<CalibrationVariance, Long> {

    List<CalibrationVariance> findByCycleId(Long cycleId);

    void deleteByCycleId(Long cycleId);
}
