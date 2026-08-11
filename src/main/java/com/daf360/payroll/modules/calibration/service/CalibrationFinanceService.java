package com.daf360.payroll.modules.calibration.service;

import com.daf360.payroll.engine.FxSnapshotService;
import com.daf360.payroll.modules.calibration.entity.CalibrationCycle;
import com.daf360.payroll.modules.calibration.entity.PayrollBudgetLine;
import com.daf360.payroll.modules.calibration.entity.PayrollForecastOutput;
import com.daf360.payroll.modules.calibration.event.ParameterSetActivatedEvent;
import com.daf360.payroll.modules.calibration.repository.CalibrationCycleRepository;
import com.daf360.payroll.modules.calibration.repository.PayrollBudgetLineRepository;
import com.daf360.payroll.modules.calibration.repository.PayrollForecastOutputRepository;
import com.daf360.payroll.modules.parameterset.entity.ParameterSet;
import com.daf360.payroll.modules.simulation.entity.SimulationResult;
import com.daf360.payroll.modules.simulation.repository.SimulationResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.List;

/**
 * Generates finance outputs automatically when a ParameterSet is fully activated
 * (both HR and Finance have approved). Runs in a separate transaction after the
 * activation commits so that a failure here never rolls back the approval itself.
 *
 * F.04 — two PayrollBudgetLine rows: EMPLOYEE_NET and EMPLOYER_LOADED
 * F.07 — three PayrollForecastOutput rows: MONTHLY, QUARTERLY, ANNUAL
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CalibrationFinanceService {

    private final CalibrationCycleRepository    cycleRepo;
    private final SimulationResultRepository    simRepo;
    private final PayrollBudgetLineRepository   budgetLineRepo;
    private final PayrollForecastOutputRepository forecastRepo;
    private final FxSnapshotService             fxService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onParameterSetActivated(ParameterSetActivatedEvent event) {
        ParameterSet ps = event.parameterSet();
        log.info("CalibrationFinanceService: generating F.04+F.07 outputs for parameterSetId={} paysId={}",
                ps.getId(), ps.getPaysId());
        try {
            generate(ps);
        } catch (Exception ex) {
            log.error("F.04/F.07 generation failed for parameterSetId={} — finance outputs not created",
                    ps.getId(), ex);
        }
    }

    private void generate(ParameterSet ps) {
        Long paysId = ps.getPaysId();

        // ── Resolve period from the most recent calibration cycle (any terminal status) ──
        String period = cycleRepo.findByPaysIdOrderByPeriodDesc(paysId).stream()
                .filter(c -> "CLOSED".equals(c.getStatus()) || "REQUIRES_UPDATE".equals(c.getStatus()))
                .findFirst()
                .map(CalibrationCycle::getPeriod)
                .orElse(YearMonth.now().toString()); // fallback: current month

        // ── Resolve EMPLOYER_LOADED from calibration cycle predictedTotalLoadedCost ──
        BigDecimal employerLoaded = cycleRepo.findByPaysIdOrderByPeriodDesc(paysId).stream()
                .filter(c -> "CLOSED".equals(c.getStatus()) || "REQUIRES_UPDATE".equals(c.getStatus()))
                .findFirst()
                .map(CalibrationCycle::getPredictedTotalLoadedCost)
                .orElse(null);

        Integer headcount = cycleRepo.findByPaysIdOrderByPeriodDesc(paysId).stream()
                .filter(c -> "CLOSED".equals(c.getStatus()) || "REQUIRES_UPDATE".equals(c.getStatus()))
                .findFirst()
                .map(CalibrationCycle::getHeadcount)
                .orElse(null);

        // ── Fallback: aggregate loadedCost from the latest COHORT simulation ──
        List<SimulationResult> latestCohort = latestCohortRows(paysId);
        if (employerLoaded == null && !latestCohort.isEmpty()) {
            employerLoaded = latestCohort.stream()
                    .map(SimulationResult::getLoadedCost)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (headcount == null) headcount = latestCohort.size();
        }

        if (employerLoaded == null || employerLoaded.compareTo(BigDecimal.ZERO) == 0) {
            log.warn("No cost data found for paysId={} — skipping F.04/F.07 generation", paysId);
            return;
        }

        // ── EMPLOYEE_NET: sum of inputNet from latest cohort ──
        BigDecimal employeeNet = latestCohort.isEmpty() ? null
                : latestCohort.stream()
                        .map(SimulationResult::getInputNet)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

        String currency = fxService.localCurrency(paysId);

        // ── F.04 — create two budget lines ──
        budgetLineRepo.save(buildBudgetLine(ps, paysId, period, "EMPLOYER_LOADED",
                employerLoaded, headcount, currency));

        if (employeeNet != null) {
            budgetLineRepo.save(buildBudgetLine(ps, paysId, period, "EMPLOYEE_NET",
                    employeeNet, headcount, currency));
        }

        // ── F.07 — create three forecast outputs from EMPLOYER_LOADED ──
        forecastRepo.save(buildForecast(ps, paysId, period, "MONTHLY",
                employerLoaded, BigDecimal.ONE, headcount, currency));
        forecastRepo.save(buildForecast(ps, paysId, period, "QUARTERLY",
                employerLoaded, BigDecimal.valueOf(3), headcount, currency));
        forecastRepo.save(buildForecast(ps, paysId, period, "ANNUAL",
                employerLoaded, BigDecimal.valueOf(12), headcount, currency));

        log.info("F.04/F.07 outputs created for parameterSetId={} period={} employerLoaded={}",
                ps.getId(), period, employerLoaded);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private List<SimulationResult> latestCohortRows(Long paysId) {
        List<SimulationResult> all =
                simRepo.findByPaysIdAndSimulationTypeOrderBySimulatedAtDesc(paysId, "COHORT");
        if (all.isEmpty()) return List.of();
        Long cohortId = all.get(0).getCohortId();
        if (cohortId == null) return List.of();
        return simRepo.findByCohortId(cohortId);
    }

    private PayrollBudgetLine buildBudgetLine(ParameterSet ps, Long paysId, String period,
                                               String lineType, BigDecimal monthly,
                                               Integer headcount, String currency) {
        PayrollBudgetLine line = new PayrollBudgetLine();
        line.setParameterSetId(ps.getId());
        line.setPaysId(paysId);
        line.setPeriod(period);
        line.setLineType(lineType);
        line.setMonthlyAmount(monthly.setScale(4, RoundingMode.HALF_UP));
        line.setMonthlyEur(fxService.convertToEur(monthly, paysId));
        line.setMonthlyChf(fxService.convertToChf(monthly, paysId));
        line.setHeadcount(headcount);
        line.setLocalCurrency(currency);
        return line;
    }

    private PayrollForecastOutput buildForecast(ParameterSet ps, Long paysId, String period,
                                                 String forecastType, BigDecimal monthly,
                                                 BigDecimal multiplier, Integer headcount,
                                                 String currency) {
        BigDecimal amount = monthly.multiply(multiplier).setScale(4, RoundingMode.HALF_UP);
        PayrollForecastOutput f = new PayrollForecastOutput();
        f.setParameterSetId(ps.getId());
        f.setPaysId(paysId);
        f.setPeriod(period);
        f.setForecastType(forecastType);
        f.setForecastAmount(amount);
        f.setForecastEur(fxService.convertToEur(amount, paysId));
        f.setForecastChf(fxService.convertToChf(amount, paysId));
        f.setLocalCurrency(currency);
        f.setHeadcount(headcount);
        return f;
    }
}
