package com.daf360.payroll.modules.calibration.repository;

import com.daf360.payroll.modules.calibration.entity.PrecisionKpiHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PrecisionKpiHistoryRepository extends JpaRepository<PrecisionKpiHistory, Long> {

    Optional<PrecisionKpiHistory> findByCountryIdAndPeriod(Long countryId, String period);

    List<PrecisionKpiHistory> findByCountryIdOrderByPeriodDesc(Long countryId);
}
