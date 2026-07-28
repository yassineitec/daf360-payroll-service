package com.daf360.payroll.modules.calibration.service;

import com.daf360.payroll.modules.calibration.dto.CalibrationCycleDto;
import com.daf360.payroll.modules.calibration.dto.PartnerPayrollRow;
import com.daf360.payroll.modules.calibration.entity.CalibrationCycle;
import com.daf360.payroll.modules.calibration.entity.CalibrationVariance;
import com.daf360.payroll.modules.calibration.repository.CalibrationCycleRepository;
import com.daf360.payroll.modules.calibration.repository.CalibrationVarianceRepository;
import com.daf360.payroll.modules.parameterset.service.ParameterSetService;
import com.daf360.payroll.modules.simulation.entity.SimulationResult;
import com.daf360.payroll.modules.simulation.repository.SimulationResultRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

@Service
public class CalibrationCycleService {

    private final CalibrationCycleRepository cycleRepo;
    private final CalibrationVarianceRepository varianceRepo;
    private final SimulationResultRepository simResultRepo;
    private final ParameterSetService paramSetService;

    public CalibrationCycleService(CalibrationCycleRepository cycleRepo,
                                    CalibrationVarianceRepository varianceRepo,
                                    SimulationResultRepository simResultRepo,
                                    ParameterSetService paramSetService) {
        this.cycleRepo = cycleRepo;
        this.varianceRepo = varianceRepo;
        this.simResultRepo = simResultRepo;
        this.paramSetService = paramSetService;
    }

    public List<CalibrationCycleDto> listByPays(Long paysId) {
        return cycleRepo.findByPaysIdOrderByPeriodDesc(paysId).stream()
                .map(this::toDto).toList();
    }

    public CalibrationCycleDto getById(Long id) {
        return toDto(findOrThrow(id));
    }

    @Transactional
    public CalibrationCycleDto openCycle(Long paysId, String period, Long createdBy) {
        cycleRepo.findByPaysIdAndPeriod(paysId, period)
                .ifPresent(existing -> {
                    throw new IllegalStateException(
                            "Cycle already exists for paysId=" + paysId + " period=" + period);
                });

        var ps = paramSetService.loadActiveEntity(paysId);

        // Compute predicted total loaded cost from COHORT simulation results
        BigDecimal predicted = simResultRepo
                .findByPaysIdAndSimulationTypeOrderBySimulatedAtDesc(paysId, "COHORT")
                .stream()
                .findFirst()
                .map(r -> simResultRepo.findByCohortId(r.getCohortId()))
                .orElse(List.of())
                .stream()
                .map(SimulationResult::getLoadedCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        CalibrationCycle cycle = new CalibrationCycle();
        cycle.setPaysId(paysId);
        cycle.setPeriod(period);
        cycle.setParameterSetId(ps.getId());
        cycle.setStatus("OPEN");
        cycle.setPredictedTotalLoadedCost(predicted);
        cycle.setCreatedBy(createdBy);

        return toDto(cycleRepo.save(cycle));
    }

    @Transactional
    public CalibrationCycleDto uploadActuals(Long cycleId,
                                              List<PartnerPayrollRow> rows,
                                              Long closedBy) {
        CalibrationCycle cycle = findOrThrow(cycleId);
        if (!"OPEN".equals(cycle.getStatus())) {
            throw new IllegalStateException("Cycle is not OPEN: " + cycleId);
        }

        // Collect predicted costs per employee from the latest cohort simulation
        Map<Long, BigDecimal> predicted = buildPredictedMap(cycle);

        // Delete old variances for this cycle
        varianceRepo.deleteByCycleId(cycleId);

        BigDecimal totalActual = BigDecimal.ZERO;
        List<CalibrationVariance> variances = new ArrayList<>();

        for (PartnerPayrollRow row : rows) {
            BigDecimal predictedCost = predicted.getOrDefault(row.profileUserId(), BigDecimal.ZERO);
            BigDecimal varianceAmount = row.actualLoadedCost().subtract(predictedCost);
            BigDecimal variancePct = predictedCost.compareTo(BigDecimal.ZERO) == 0
                    ? BigDecimal.ZERO
                    : varianceAmount.divide(predictedCost, 6, RoundingMode.HALF_UP)
                            .multiply(new BigDecimal("100"));

            CalibrationVariance v = new CalibrationVariance();
            v.setCycleId(cycleId);
            v.setProfileUserId(row.profileUserId());
            v.setPredictedLoadedCost(predictedCost);
            v.setActualLoadedCost(row.actualLoadedCost());
            v.setVarianceAmount(varianceAmount);
            v.setVariancePct(variancePct);
            v.setContractType(row.contractType());
            v.setSourceLine(row.sourceLine());

            variances.add(v);
            totalActual = totalActual.add(row.actualLoadedCost());
        }

        varianceRepo.saveAll(variances);

        BigDecimal totalPredicted = cycle.getPredictedTotalLoadedCost() != null
                ? cycle.getPredictedTotalLoadedCost() : BigDecimal.ZERO;

        BigDecimal globalVariancePct = totalPredicted.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO
                : totalActual.subtract(totalPredicted)
                        .divide(totalPredicted, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"));

        cycle.setActualTotalLoadedCost(totalActual);
        cycle.setVariancePct(globalVariancePct);
        cycle.setHeadcount(rows.size());
        cycle.setClosedAt(OffsetDateTime.now());
        cycle.setClosedBy(closedBy);

        // If variance > calibration threshold, flag REQUIRES_UPDATE
        BigDecimal threshold = paramSetService
                .loadActiveEntity(cycle.getPaysId())
                .getCalibrationThresholdPct();

        cycle.setStatus(globalVariancePct.abs().compareTo(threshold) > 0
                ? "REQUIRES_UPDATE"
                : "CLOSED");

        return toDto(cycleRepo.save(cycle));
    }

    private Map<Long, BigDecimal> buildPredictedMap(CalibrationCycle cycle) {
        return simResultRepo
                .findByPaysIdAndSimulationTypeOrderBySimulatedAtDesc(cycle.getPaysId(), "COHORT")
                .stream()
                .findFirst()
                .map(r -> simResultRepo.findByCohortId(r.getCohortId()))
                .orElse(List.of())
                .stream()
                .filter(r -> r.getProfileUserId() != null)
                .collect(Collectors.toMap(
                        SimulationResult::getProfileUserId,
                        SimulationResult::getLoadedCost,
                        (a, b) -> a));
    }

    private CalibrationCycle findOrThrow(Long id) {
        return cycleRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("CalibrationCycle not found: " + id));
    }

    private CalibrationCycleDto toDto(CalibrationCycle c) {
        return new CalibrationCycleDto(
                c.getId(), c.getPaysId(), c.getPeriod(), c.getParameterSetId(),
                c.getStatus(), c.getPredictedTotalLoadedCost(), c.getActualTotalLoadedCost(),
                c.getVariancePct(), c.getHeadcount(), c.getClosedAt(), c.getNotes(), c.getCreatedAt());
    }
}
