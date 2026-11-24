package com.daf360.payroll.modules.simulation.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Cohort configuration, all overridable in application.yml / environment.
 *
 * <ul>
 *   <li>{@code app.cohort.min-group-size} (k, DEC1) — below k employees an AGGREGATE caller
 *       gets counts only. Default 5.</li>
 *   <li>{@code app.cohort.working-days} (DEC9) — the working-day base handed to the engine.
 *       No parameter set or country row carries one (checked: {@code parameter_sets} has no
 *       such column, {@code payroll_countries} neither); every engine path — individual
 *       simulator, payroll run ({@code RunPayrollRequest.joursOuvresMois}), Rémunération
 *       preview — defaults to 22, which is also {@code TopologicalEvaluator}'s full-month
 *       denominator for FIXE_JOURNALIER rubriques. Default 22, so a cohort and the individual
 *       simulator agree; now a setting instead of a literal.</li>
 *   <li>{@code app.cohort.gross-consistency-tolerance} (DEC6) — how far the net recomputed
 *       from a configured gross may be from the HR net for that gross to be used. Default 0.05
 *       in the payroll currency.</li>
 * </ul>
 */
@Component
public class CohortSettings {

    private final int minGroupSize;
    private final int workingDays;
    private final BigDecimal grossConsistencyTolerance;
    private final BigDecimal minCoverage;

    public static final BigDecimal DEFAULT_MIN_COVERAGE = new BigDecimal("0.90");

    @Autowired
    public CohortSettings(@Value("${app.cohort.min-group-size:5}") int minGroupSize,
                          @Value("${app.cohort.working-days:22}") int workingDays,
                          @Value("${app.cohort.gross-consistency-tolerance:0.05}") BigDecimal grossConsistencyTolerance,
                          @Value("${app.cohort.min-coverage:0.90}") BigDecimal minCoverage) {
        if (minGroupSize < 1) throw new IllegalArgumentException("app.cohort.min-group-size must be >= 1");
        if (workingDays < 1 || workingDays > 31) throw new IllegalArgumentException("app.cohort.working-days must be 1..31");
        if (minCoverage == null || minCoverage.signum() < 0 || minCoverage.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("app.cohort.min-coverage must be within 0..1");
        }
        this.minGroupSize = minGroupSize;
        this.workingDays = workingDays;
        this.grossConsistencyTolerance = grossConsistencyTolerance;
        this.minCoverage = minCoverage;
    }

    /** Defaults for {@code min-coverage}; kept for tests and callers that predate it. */
    public CohortSettings(int minGroupSize, int workingDays, BigDecimal grossConsistencyTolerance) {
        this(minGroupSize, workingDays, grossConsistencyTolerance, DEFAULT_MIN_COVERAGE);
    }

    public int minGroupSize() { return minGroupSize; }
    public int workingDays() { return workingDays; }
    public BigDecimal grossConsistencyTolerance() { return grossConsistencyTolerance; }
    public BigDecimal minCoverage() { return minCoverage; }
}
