package com.daf360.payroll.modules.calibration.service;

import com.daf360.payroll.modules.calibration.entity.CalibrationImport;
import com.daf360.payroll.modules.calibration.entity.CalibrationImportLine;
import com.daf360.payroll.modules.calibration.repository.CalibrationImportLineRepository;
import com.daf360.payroll.modules.calibration.repository.CalibrationImportRepository;
import com.daf360.payroll.modules.payroll.entity.PayrollCountry;
import com.daf360.payroll.modules.payroll.repository.PayrollCountryRepository;
import com.daf360.payroll.modules.payroll.repository.PayrollResultRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles D3-256: import calibration data and compare with predicted engine values.
 */
@Slf4j
@Service
public class CalibrationImportService {

    private final CalibrationImportRepository importRepo;
    private final CalibrationImportLineRepository lineRepo;
    private final PayrollCountryRepository countryRepo;
    private final PayrollResultRepository payrollResultRepo;
    private final ObjectMapper objectMapper;

    public CalibrationImportService(CalibrationImportRepository importRepo,
                                     CalibrationImportLineRepository lineRepo,
                                     PayrollCountryRepository countryRepo,
                                     PayrollResultRepository payrollResultRepo,
                                     ObjectMapper objectMapper) {
        this.importRepo = importRepo;
        this.lineRepo = lineRepo;
        this.countryRepo = countryRepo;
        this.payrollResultRepo = payrollResultRepo;
        this.objectMapper = objectMapper;
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
     * amounts (summed from payroll_results for the same country + period).
     *
     * @param importId     the CalibrationImport to update
     * @param importedBy   user who uploaded the file
     * @param fileName     original file name
     * @param actualValues map of rubriqueCode → actual amount from partner payroll file
     */
    public CalibrationImport recordActuals(Long importId,
                                            String importedBy,
                                            String fileName,
                                            Map<String, BigDecimal> actualValues) {
        CalibrationImport imp = importRepo.findById(importId)
            .orElseThrow(() -> new EntityNotFoundException(
                "CalibrationImport not found: " + importId));

        // Build predicted totals per rubrique from the engine's payroll_results
        Map<String, BigDecimal> predictedTotals = buildPredictedTotals(imp);

        BigDecimal threshold      = new BigDecimal("1.00"); // 1 % variance flag threshold
        BigDecimal totalPredicted = BigDecimal.ZERO;
        BigDecimal totalActual    = BigDecimal.ZERO;
        List<CalibrationImportLine> lines = new ArrayList<>();

        for (Map.Entry<String, BigDecimal> entry : actualValues.entrySet()) {
            String     rubriqueCode = entry.getKey();
            BigDecimal actual       = entry.getValue();
            BigDecimal predicted    = predictedTotals.getOrDefault(rubriqueCode, BigDecimal.ZERO);

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

        // Global precision KPI: (1 − |predicted−actual| / predicted) × 100
        BigDecimal globalPrecision;
        if (totalPredicted.compareTo(BigDecimal.ZERO) != 0) {
            BigDecimal absVariance = totalActual.subtract(totalPredicted).abs();
            globalPrecision = BigDecimal.ONE
                .subtract(absVariance.divide(totalPredicted, 6, RoundingMode.HALF_UP))
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
        } else {
            globalPrecision = BigDecimal.ZERO;
        }

        boolean anyExceedsThreshold = lines.stream().anyMatch(CalibrationImportLine::isExceedsThreshold);
        imp.setImportStatus(anyExceedsThreshold ? "REQUIRES_UPDATE" : "COMPARISON_DONE");
        imp.setImportedAt(OffsetDateTime.now());
        imp.setImportedBy(importedBy);
        imp.setFileName(fileName);
        imp.setGlobalPrecisionPct(globalPrecision);

        return importRepo.save(imp);
    }

    /**
     * Aggregates engine-computed amounts by rubriqueCode for all employees
     * in the same country and period as the given CalibrationImport.
     * Parses each PayrollResult.rubriqueDetails JSON array.
     */
    private Map<String, BigDecimal> buildPredictedTotals(CalibrationImport imp) {
        // period format: "YYYY-MM"
        int year, month;
        try {
            String[] parts = imp.getPeriod().split("-");
            year  = Integer.parseInt(parts[0]);
            month = Integer.parseInt(parts[1]);
        } catch (Exception e) {
            log.warn("Cannot parse period '{}' for importId={} — predicted totals will be zero",
                    imp.getPeriod(), imp.getId());
            return Map.of();
        }

        var results = payrollResultRepo.findByCountryIdAndPeriodYearAndPeriodMonth(
                imp.getCountryId(), year, month);

        if (results.isEmpty()) {
            log.info("No payroll_results found for countryId={} period={} — calibration will compare against zero",
                    imp.getCountryId(), imp.getPeriod());
            return Map.of();
        }

        TypeReference<List<Map<String, Object>>> listMapType = new TypeReference<>() {};
        Map<String, BigDecimal> totals = new HashMap<>();

        for (var pr : results) {
            if (pr.getRubriqueDetails() == null || pr.getRubriqueDetails().isBlank()) continue;
            try {
                List<Map<String, Object>> items = objectMapper.readValue(pr.getRubriqueDetails(), listMapType);
                for (var item : items) {
                    String code = (String) item.get("rubriqueCode");
                    Object rawAmt = item.get("amount");
                    if (code == null || rawAmt == null) continue;
                    BigDecimal amt = new BigDecimal(rawAmt.toString());
                    totals.merge(code, amt, BigDecimal::add);
                }
            } catch (Exception e) {
                log.warn("Failed to parse rubriqueDetails for payrollResult id={}: {}", pr.getId(), e.getMessage());
            }
        }

        log.info("Built predicted totals for importId={}: {} rubriques across {} employee results",
                imp.getId(), totals.size(), results.size());
        return totals;
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
