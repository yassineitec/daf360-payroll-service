package com.daf360.payroll.modules.calibration.service;

import com.daf360.payroll.modules.calibration.entity.CalibrationImport;
import com.daf360.payroll.modules.calibration.entity.PrecisionKpiHistory;
import com.daf360.payroll.modules.calibration.repository.CalibrationImportRepository;
import com.daf360.payroll.modules.calibration.repository.PrecisionKpiHistoryRepository;
import com.daf360.payroll.modules.payroll.entity.PayrollCountry;
import com.daf360.payroll.modules.payroll.repository.PayrollCountryRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * D3-258: tracks the precision KPI per country/period and raises an alert flag
 * when the precision has been below 95% for 2 or more consecutive months
 * (spec H.10 §4.3: target ≥98% from month 3, alert threshold = 95% / 2 months).
 */
@Service
public class KpiHistoryService {

    /** Alert fires when precision drops below this value (spec §4.3). */
    private static final BigDecimal DEFAULT_THRESHOLD     = new BigDecimal("95.00");
    /** Number of consecutive months below threshold before alertSent is set (spec §4.3). */
    private static final int        ALERT_CONSECUTIVE_MONTHS = 2;

    private final PrecisionKpiHistoryRepository kpiRepo;
    private final CalibrationImportRepository importRepo;
    private final PayrollCountryRepository countryRepo;

    public KpiHistoryService(PrecisionKpiHistoryRepository kpiRepo,
                              CalibrationImportRepository importRepo,
                              PayrollCountryRepository countryRepo) {
        this.kpiRepo = kpiRepo;
        this.importRepo = importRepo;
        this.countryRepo = countryRepo;
    }

    /**
     * Creates a PrecisionKpiHistory entry from a completed CalibrationImport.
     * Counts consecutive months below threshold and sets alertSent = true once
     * the count reaches {@value #ALERT_CONSECUTIVE_MONTHS}.
     */
    public PrecisionKpiHistory record(Long importId) {
        CalibrationImport imp = importRepo.findById(importId)
            .orElseThrow(() -> new EntityNotFoundException(
                "CalibrationImport not found: " + importId));

        if (imp.getGlobalPrecisionPct() == null) {
            throw new IllegalStateException(
                "CalibrationImport " + importId + " has no globalPrecisionPct yet");
        }

        boolean belowThreshold = imp.getGlobalPrecisionPct()
            .compareTo(DEFAULT_THRESHOLD) < 0;

        int consecutiveMonths = 0;
        if (belowThreshold) {
            String prevPeriod = previousPeriod(imp.getPeriod());
            consecutiveMonths = kpiRepo
                .findByCountryIdAndPeriod(imp.getCountryId(), prevPeriod)
                .map(prev -> prev.isBelowThreshold()
                    ? prev.getConsecutiveMonthsBelow() + 1
                    : 1)
                .orElse(1);
        }

        PrecisionKpiHistory kpi = new PrecisionKpiHistory();
        kpi.setCountryId(imp.getCountryId());
        kpi.setPeriod(imp.getPeriod());
        kpi.setPrecisionPct(imp.getGlobalPrecisionPct());
        kpi.setThresholdPct(DEFAULT_THRESHOLD);
        kpi.setBelowThreshold(belowThreshold);
        kpi.setConsecutiveMonthsBelow(consecutiveMonths);
        kpi.setAlertSent(consecutiveMonths >= ALERT_CONSECUTIVE_MONTHS);
        kpi.setImportId(importId);
        kpi.setCalculatedAt(OffsetDateTime.now());

        return kpiRepo.save(kpi);
    }

    public List<PrecisionKpiHistory> history(Long paysId) {
        PayrollCountry country = countryRepo.findByPaysIdAndActiveTrue(paysId)
            .orElse(null);
        if (country == null) {
            return List.of();
        }
        return kpiRepo.findByCountryIdOrderByPeriodDesc(country.getId());
    }

    /** Returns the YYYY-MM string for the month preceding the given period. */
    private String previousPeriod(String period) {
        int year  = Integer.parseInt(period.substring(0, 4));
        int month = Integer.parseInt(period.substring(5, 7));
        month--;
        if (month == 0) {
            month = 12;
            year--;
        }
        return String.format("%04d-%02d", year, month);
    }
}
