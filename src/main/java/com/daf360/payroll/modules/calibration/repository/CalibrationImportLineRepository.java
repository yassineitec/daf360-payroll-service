package com.daf360.payroll.modules.calibration.repository;

import com.daf360.payroll.modules.calibration.entity.CalibrationImportLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CalibrationImportLineRepository extends JpaRepository<CalibrationImportLine, Long> {

    List<CalibrationImportLine> findByImportId(Long importId);
}
