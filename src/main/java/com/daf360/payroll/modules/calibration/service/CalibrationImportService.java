package com.daf360.payroll.modules.calibration.service;

import com.daf360.payroll.modules.calibration.entity.CalibrationImport;
import com.daf360.payroll.modules.calibration.entity.CalibrationImportLine;
import com.daf360.payroll.modules.calibration.repository.CalibrationImportLineRepository;
import com.daf360.payroll.modules.calibration.repository.CalibrationImportRepository;
import com.daf360.payroll.modules.payroll.entity.PayrollCountry;
import com.daf360.payroll.modules.payroll.repository.PayrollCountryRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Handles D3-256: import calibration data and compare with predicted engine values.
 */
@Service
public class CalibrationImportService {

    private final CalibrationImportRepository importRepo;
    private final CalibrationImportLineRepository lineRepo;
    private final PayrollCountryRepository countryRepo;

    public CalibrationImportService(CalibrationImportRepository importRepo,
                                     CalibrationImportLineRepository lineRepo,
                                     PayrollCountryRepository countryRepo) {
        this.importRepo = importRepo;
        this.lineRepo = lineRepo;
        this.countryRepo = countryRepo;
    }

    /**
     * Creates a CalibrationImport record for a given country and period.
     * deadlineJ5 = triggeredAt + 7 calendar days (simplified business-day proxy).
     */
    public CalibrationImport openImport(Long paysId, String period, Long paramSetId) {
        PayrollCountry country = countryRepo.findByPaysIdAndActiveTrue(paysId)
            .orElseThrow(() -> new IllegalStateException(
                "No active country for paysId=" + paysId));

        CalibrationImport imp = new CalibrationImport();
        imp.setCountryId(country.getId());
        imp.setPeriod(period);
        imp.setImportStatus("PENDING");
        imp.setParameterSetId(paramSetId);
        imp.setTriggeredAt(OffsetDateTime.now());
        imp.setDeadlineJ5(OffsetDateTime.now().plusDays(7));
        return importRepo.save(imp);
    }

    /**
     * Records actual payroll values and computes variance against the predicted
     * amounts (what the engine computed in payroll_results for this period).
     *
     * @param importId     the CalibrationImport to update
     * @param importedBy   user who uploaded the file
     * @param fileName     original file name
     * @param actualValues map of rubriqueCode → actual amount from partner payroll
     */
    public CalibrationImport recordActuals(Long importId,
                                            String importedBy,
                                            String fileName,
                                            Map<String, BigDecimal> actualValues) {
        CalibrationImport imp = importRepo.findById(importId)
            .orElseThrow(() -> new EntityNotFoundException(
                "CalibrationImport not found: " + importId));

        BigDecimal threshold = new BigDecimal("1.00"); // 1 % variance threshold
        BigDecimal totalPredicted = BigDecimal.ZERO;
        BigDecimal totalActual    = BigDecimal.ZERO;
        List<CalibrationImportLine> lines = new ArrayList<>();

        for (Map.Entry<String, BigDecimal> entry : actualValues.entrySet()) {
            String rubriqueCode    = entry.getKey();
            BigDecimal actual      = entry.getValue();
            // Predicted = engine-computed amount for this rubrique and period.
            // A real implementation would query payroll_results; ZERO is used
            // until payroll results are linked to the calibration import.
            BigDecimal predicted   = BigDecimal.ZERO;

            BigDecimal variance    = actual.subtract(predicted);
            BigDecimal variancePct = predicted.compareTo(BigDecimal.ZERO) != 0
                ? variance.divide(predicted, 4, RoundingMode.HALF_UP)
                          .multiply(BigDecimal.valueOf(100))
                : BigDecimal.ZERO;

            CalibrationImportLine line = new CalibrationImportLine();
            line.setImportId(importId);
            line.setRubriqueCode(rubriqueCode);
            line.setPredictedAmount(predicted);
            line.setActualAmount(actual);
            line.setVarianceAmount(variance);
            line.setVariancePct(variancePct);
            line.setExceedsThreshold(variancePct.abs().compareTo(threshold) > 0);
            line.setCreatedAt(OffsetDateTime.now());
            lines.add(lineRepo.save(line));

            totalPredicted = totalPredicted.add(predicted);
            totalActual    = totalActual.add(actual);
        }

        // Global precision: actualTotal / predictedTotal * 100
        BigDecimal globalPrecision = totalPredicted.compareTo(BigDecimal.ZERO) != 0
            ? totalActual.divide(totalPredicted, 4, RoundingMode.HALF_UP)
                         .multiply(BigDecimal.valueOf(100))
            : BigDecimal.ZERO;

        imp.setImportStatus("COMPARISON_DONE");
        imp.setImportedAt(OffsetDateTime.now());
        imp.setImportedBy(importedBy);
        imp.setFileName(fileName);
        imp.setGlobalPrecisionPct(globalPrecision);

        boolean anyExceedsThreshold = lines.stream()
            .anyMatch(CalibrationImportLine::isExceedsThreshold);
        if (anyExceedsThreshold) {
            imp.setImportStatus("REQUIRES_UPDATE");
        }

        return importRepo.save(imp);
    }

    public List<CalibrationImport> listByCountry(Long paysId) {
        PayrollCountry country = countryRepo.findByPaysIdAndActiveTrue(paysId)
            .orElseThrow(() -> new IllegalStateException(
                "No active country for paysId=" + paysId));
        return importRepo.findByCountryIdOrderByPeriodDesc(country.getId());
    }

    public List<CalibrationImportLine> getLines(Long importId) {
        return lineRepo.findByImportId(importId);
    }
}
