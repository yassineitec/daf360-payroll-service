package com.daf360.payroll.modules.payroll.leavesettings;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OvertimeDayRateRepository extends JpaRepository<OvertimeDayRate, Long> {

    List<OvertimeDayRate> findByPaysId(Long paysId);

    Optional<OvertimeDayRate> findByPaysIdAndDayOfWeek(Long paysId, String dayOfWeek);
}
