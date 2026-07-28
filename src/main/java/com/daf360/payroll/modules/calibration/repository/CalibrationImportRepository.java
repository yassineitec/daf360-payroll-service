package com.daf360.payroll.modules.calibration.repository;

import com.daf360.payroll.modules.calibration.entity.CalibrationImport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CalibrationImportRepository extends JpaRepository<CalibrationImport, Long> {

    Optional<CalibrationImport> findByCountryIdAndPeriod(Long countryId, String period);

    List<CalibrationImport> findByCountryIdOrderByPeriodDesc(Long countryId);
}
