package com.daf360.payroll.modules.simulation.service;

import com.daf360.payroll.config.AppProperties;
import com.daf360.payroll.engine.ConvergenceEngine;
import com.daf360.payroll.engine.FormulaEvaluatorService;
import com.daf360.payroll.engine.FxSnapshotService;
import com.daf360.payroll.engine.IrppCalculatorService;
import com.daf360.payroll.engine.PayrollSimulatorService;
import com.daf360.payroll.engine.TopologicalEvaluator;
import com.daf360.payroll.modules.employeeconfig.entity.EmployeePayrollConfig;
import com.daf360.payroll.modules.employeeconfig.repository.EmployeePayrollConfigRepository;
import com.daf360.payroll.modules.parameterset.entity.BenefitCatalogue;
import com.daf360.payroll.modules.parameterset.entity.ParameterSet;
import com.daf360.payroll.modules.parameterset.entity.SocialChargeRate;
import com.daf360.payroll.modules.parameterset.service.ParameterSetService;
import com.daf360.payroll.modules.payroll.entity.PayrollCountry;
import com.daf360.payroll.modules.payroll.repository.PayrollCountryRepository;
import com.daf360.payroll.modules.ref.entity.PayrollFxRate;
import com.daf360.payroll.modules.ref.repository.PayrollFxRateRepository;
import com.daf360.payroll.modules.ref.service.FxEntryConversionService;
import com.daf360.payroll.modules.simulation.client.HrEmployeeClient;
import com.daf360.payroll.modules.simulation.client.HrEmployeeClient.HrEmployeeDto;
import com.daf360.payroll.modules.simulation.client.HrServiceUnavailableException;
import com.daf360.payroll.modules.simulation.dto.CohortAggregateResponse;
import com.daf360.payroll.modules.simulation.dto.CohortFilterRequest;
import com.daf360.payroll.modules.simulation.dto.EmployeeWhatIfRequest;
import com.daf360.payroll.modules.simulation.dto.EmployeeWhatIfResponse;
import com.daf360.payroll.modules.simulation.dto.EmployeeWhatIfRow;
import com.daf360.payroll.modules.simulation.dto.SimulationMode;
import com.daf360.payroll.modules.simulation.dto.SimulationRequest;
import com.daf360.payroll.modules.simulation.repository.SimulationResultRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Cohort hardening + what-if, on the REAL engine with a synthetic Tunisia parameter set
 * (same fixture as PayrollSimulatorServiceTunisiaConfigTest: 2025 brackets, abattement,
 * family deductions, CNSS / CSS) and synthetic employees. No real salary or person.
 */
class CohortAggregateServiceTest {

    private static final long TN = 179L;
    private static final BigDecimal CENT = new BigDecimal("0.01");

    private PayrollSimulatorService simulator;
    private ParameterSetService paramSets;
    private HrEmployeeClient hr;
    private EmployeePayrollConfigRepository configRepo;
    private ObjectMapper om;
    private CohortAggregateService service;
    private ParameterSet ps;
    private List<HrEmployeeDto> workforce;

    private static final CohortAggregateService.CohortAccess INDIVIDUAL =
            new CohortAggregateService.CohortAccess(true, 1L, "PAYROLL_VIEW_INDIVIDUAL");
    private static final CohortAggregateService.CohortAccess AGGREGATE =
            new CohortAggregateService.CohortAccess(false, 2L, "PAYROLL_VIEW_AGGREGATE");

    @BeforeEach
    void setUp() {
        om = new ObjectMapper().registerModule(new JavaTimeModule());
        simulator = new PayrollSimulatorService(new IrppCalculatorService(new ObjectMapper()),
                new ConvergenceEngine(), new TopologicalEvaluator(new FormulaEvaluatorService()));

        ps = new ParameterSet();
        ps.setId(9L);
        ps.setVersion(1);
        ps.setFiscalYear(2025);
        ps.setIrppBrackets("[{\"lower\":0,\"upper\":5000,\"rate\":0},{\"lower\":5000,\"upper\":10000,\"rate\":0.15},"
                + "{\"lower\":10000,\"upper\":20000,\"rate\":0.25},{\"lower\":20000,\"upper\":30000,\"rate\":0.30},"
                + "{\"lower\":30000,\"upper\":40000,\"rate\":0.33},{\"lower\":40000,\"upper\":50000,\"rate\":0.36},"
                + "{\"lower\":50000,\"upper\":70000,\"rate\":0.38},{\"lower\":70000,\"upper\":null,\"rate\":0.40}]");
        ps.setConvergenceTolerance(new BigDecimal("0.01"));
        ps.setMaxConvergenceIterations(50);
        ps.setAbattementRatePct(new BigDecimal("0.10"));
        ps.setAbattementAnnualCap(new BigDecimal("2000"));
        ps.setFamilyDeductionMarriedAmount(new BigDecimal("300"));
        ps.setFamilyDeductionPerChildAmount(new BigDecimal("100"));
        ps.setFamilyDeductionMaxChildren(4);

        paramSets = mock(ParameterSetService.class);
        when(paramSets.loadActiveEntity(TN)).thenReturn(ps);
        when(paramSets.loadRates(9L)).thenReturn(List.of(
                rate("CNSS", "0.0968", "0.1657", "GROSS"), rate("CSS", "0.005", "0", "TAXABLE_BASE2")));
        when(paramSets.loadBenefits(9L)).thenReturn(List.of(benefit("BEN_T", "100", true), benefit("BEN_X", "50", false)));
        when(paramSets.loadRubriques(9L)).thenReturn(List.of());

        // Synthetic workforce: grade G1 = 3 people (below k), G2 = 5 people (= k), one without salary.
        workforce = new ArrayList<>(List.of(
                emp(1L, "G1", "1500", false, 0), emp(2L, "G1", "1800", true, 2), emp(3L, "G1", "2200", false, 0),
                emp(4L, "G2", "2600", false, 0), emp(5L, "G2", "3000", true, 1), emp(6L, "G2", "3500", false, 0),
                emp(7L, "G2", "4200", false, 0), emp(8L, "G2", "1900", null, null),
                emp(9L, "G3", null, false, 0)));
        hr = mock(HrEmployeeClient.class);
        when(hr.fetchActiveEmployeesOrThrow(TN)).thenAnswer(i -> workforce);
        when(hr.findEmployeeByUserId(anyLong())).thenAnswer(i ->
                workforce.stream().filter(e -> e.userId().equals(i.getArgument(0))).findFirst());

        // Employee 2 has a payroll config selecting only BEN_T; everyone else has none (benefits assumed).
        EmployeePayrollConfig cfg2 = new EmployeePayrollConfig();
        cfg2.setProfileUserId(2L);
        cfg2.setPaysId(TN);
        cfg2.setSelectedBenefitCodes("[\"BEN_T\"]");
        configRepo = mock(EmployeePayrollConfigRepository.class);
        when(configRepo.findByProfileUserIdIn(any())).thenReturn(List.of(cfg2));
        when(configRepo.findByProfileUserId(anyLong())).thenAnswer(i ->
                i.getArgument(0).equals(2L) ? Optional.of(cfg2) : Optional.empty());

        service = new CohortAggregateService(hr, paramSets, new CohortEmployeeCalculator(simulator, om),
                fxResolver(), configRepo, new CohortSettings(5, 22, new BigDecimal("0.05")));
    }

    private CohortFxResolver fxResolver() {
        FxSnapshotService snap = mock(FxSnapshotService.class);
        when(snap.localCurrency(TN)).thenReturn("TND");
        PayrollFxRateRepository rates = mock(PayrollFxRateRepository.class);
        when(rates.findByCurrencyCode("TND")).thenReturn(Optional.of(fx("TND", "0.300000")));
        when(rates.findByCurrencyCode("CHF")).thenReturn(Optional.of(fx("CHF", "1.088613")));
        PayrollCountryRepository countries = mock(PayrollCountryRepository.class);
        PayrollCountry tn = new PayrollCountry();
        tn.setEurRate(new BigDecimal("1.000000"));     // the stored (wrong) static rate — must be ignored
        when(countries.findByPaysIdAndActiveTrue(TN)).thenReturn(Optional.of(tn));
        return new CohortFxResolver(snap, rates, countries, new AppProperties());
    }

    // ── A2 — visibility ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A2: AGGREGATE caller, group of 3 (< k=5) → headcount + suppressed, no amount at all")
    void aggregate_belowK_isSuppressed() {
        CohortAggregateResponse r = service.aggregate(group("G1", "PERCENT", "5", null, null), AGGREGATE);
        assertThat(r.headcount()).isEqualTo(3);
        assertThat(r.suppressed()).isTrue();
        assertThat(r.visibility()).isEqualTo("AGGREGATE");
        assertThat(amountsOf(r)).allMatch(a -> a == null);
        assertThat(r.employees()).isNull();
        assertThat(r.messages()).anyMatch(m -> m.code().equals("SUPPRESSED"));
    }

    @Test
    @DisplayName("A2: AGGREGATE caller, group of 5 (= k) → totals, never per-employee rows")
    void aggregate_atK_showsTotals() {
        CohortAggregateResponse r = service.aggregate(group("G2", "PERCENT", "5", null, null), AGGREGATE);
        assertThat(r.headcount()).isEqualTo(5);
        assertThat(r.suppressed()).isFalse();
        assertThat(r.currentMonthlyCost()).isNotNull();
        assertThat(r.employees()).isNull();
    }

    @Test
    @DisplayName("A2: includeEmployees=true without VIEW_INDIVIDUAL → 403")
    void aggregate_includeEmployees_requiresIndividual() {
        assertThatThrownBy(() -> service.aggregate(group("G2", "PERCENT", "5", null, true), AGGREGATE))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("A2: INDIVIDUAL caller sees exact figures for any group size")
    void individual_smallGroup_exact() {
        CohortAggregateResponse r = service.aggregate(group("G1", "PERCENT", "5", null, true), INDIVIDUAL);
        assertThat(r.suppressed()).isFalse();
        assertThat(r.currentMonthlyCost()).isNotNull();
        assertThat(r.employees()).hasSize(3);
    }

    @Test
    @DisplayName("Acceptance 4: nothing an AGGREGATE caller can obtain carries a per-employee amount or id")
    void aggregate_noLeak_scan() throws Exception {
        CohortAggregateResponse full = service.aggregate(group(null, "PERCENT", "5", null, true), INDIVIDUAL);
        List<String> forbidden = new ArrayList<>();
        for (EmployeeWhatIfRow row : full.employees()) {
            forbidden.add(row.current().loadedCost().toPlainString());
            forbidden.add(row.projected().loadedCost().toPlainString());
            forbidden.add(row.current().gross().toPlainString());
            forbidden.add(row.projected().netInHand().toPlainString());
        }
        for (String g : new String[]{null, "G1", "G2", "G3"}) {
            for (String base : new String[]{"NET", "GROSS"}) {
                String json = om.writeValueAsString(service.aggregate(group(g, "PERCENT", "5", base, null), AGGREGATE));
                JsonNode node = om.readTree(json);
                assertThat(node.get("employees").isNull()).isTrue();
                assertThat(fieldNames(node)).doesNotContain("profileUserId", "userId");
                for (String f : forbidden) assertThat(json).doesNotContain(f);
            }
        }
    }

    // ── A3 — honest failures ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A3: HR unreachable → HrServiceUnavailableException (503), never zeros")
    void hrDown_isAnError() {
        when(hr.fetchActiveEmployeesOrThrow(TN)).thenThrow(new HrServiceUnavailableException("down", null));
        assertThatThrownBy(() -> service.aggregate(group(null, null, null, null, null), INDIVIDUAL))
                .isInstanceOf(HrServiceUnavailableException.class);
    }

    @Test
    @DisplayName("A3: empty cohort → clear empty response, not an error")
    void emptyCohort() {
        CohortAggregateResponse r = service.aggregate(group("NOPE", null, null, null, null), AGGREGATE);
        assertThat(r.matched()).isZero();
        assertThat(r.headcount()).isZero();
        assertThat(r.currentMonthlyCost()).isNull();
        assertThat(r.messages()).anyMatch(m -> m.code().equals("EMPTY"));
    }

    @Test
    @DisplayName("A3: employees without salary are counted, not dropped silently")
    void noSalary_isCounted() {
        CohortAggregateResponse r = service.aggregate(group(null, null, null, null, null), INDIVIDUAL);
        assertThat(r.matched()).isEqualTo(9);
        assertThat(r.excludedNoSalary()).isEqualTo(1);
        assertThat(r.headcount()).isEqualTo(8);
        assertThat(r.benefitsAssumed()).isEqualTo(7);
        assertThat(r.parameterSetId()).isEqualTo(9L);
        assertThat(r.parameterSetVersion()).isEqualTo(1);
        assertThat(r.fiscalYear()).isEqualTo(2025);
        assertThat(r.asOf()).isNotNull();
    }

    @Test
    @DisplayName("DEC4: when nothing converges, totals are absent and nonConverged = simulated")
    void allNonConverged_noTotals() {
        ps.setMaxConvergenceIterations(1);
        CohortAggregateResponse r = service.aggregate(group(null, "PERCENT", "5", null, null), INDIVIDUAL);
        assertThat(r.nonConverged()).isEqualTo(r.matched() - r.excludedNoSalary());
        assertThat(r.headcount()).isZero();
        assertThat(r.currentMonthlyCost()).isNull();
        assertThat(r.messages()).anyMatch(m -> m.code().equals("ALL_NON_CONVERGED"));
    }

    // ── A4 — validation ─────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @CsvSource({ "PERCENT,-51", "PERCENT,201", "PCT,-50.01" })
    @DisplayName("DEC3: percentage outside −50..+200 rejected")
    void percentBounds(String type, String value) {
        assertThatThrownBy(() -> service.aggregate(group(null, type, value, null, null), INDIVIDUAL))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("DEC3: −50 and +200 are accepted")
    void percentBounds_inclusive() {
        assertThat(service.aggregate(group("G2", "PERCENT", "-50", null, null), INDIVIDUAL).headcount()).isEqualTo(5);
        assertThat(service.aggregate(group("G2", "PERCENT", "200", null, null), INDIVIDUAL).headcount()).isEqualTo(5);
    }

    @Test
    @DisplayName("DEC3: an amount that makes any salary negative is rejected, not floored")
    void amountMakingSalaryNegative_rejected() {
        assertThatThrownBy(() -> service.aggregate(group(null, "AMOUNT", "-1600", null, null), INDIVIDUAL))
                .isInstanceOf(CohortEmployeeCalculator.NonPositiveSalaryException.class);
        assertThatThrownBy(() -> service.aggregate(group(null, "ABSOLU", "-99999", "GROSS", null), INDIVIDUAL))
                .isInstanceOf(CohortEmployeeCalculator.NonPositiveSalaryException.class);
    }

    @Test
    @DisplayName("A4: unknown modifierType / salaryBase and a non-empty entite → 400")
    void invalidInputs() {
        assertThatThrownBy(() -> service.aggregate(group(null, "XYZ", "5", null, null), INDIVIDUAL))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.aggregate(group(null, "PERCENT", "5", "TAX", null), INDIVIDUAL))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.aggregate(
                new CohortFilterRequest(TN, null, null, null, "ITEC", "PCT", BigDecimal.ONE), INDIVIDUAL))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── A6 — FX ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A6: TND→EUR comes from payroll_fx_rates (0.300), not the stored static 1.000")
    void fx_tunisiaEurIsNotOne() {
        CohortAggregateResponse r = service.aggregate(group("G2", null, null, null, null), INDIVIDUAL);
        assertThat(r.fx().eurRate()).isEqualByComparingTo("0.3");
        assertThat(r.fx().eurSource()).isEqualTo(CohortFxResolver.SRC_SYNCED);
        assertThat(r.currentMonthlyCostEur()).isEqualByComparingTo(r.currentMonthlyCost().multiply(new BigDecimal("0.3")));
        assertThat(r.currentMonthlyCostEur()).isNotEqualByComparingTo(r.currentMonthlyCost());
        assertThat(r.fx().chfRate()).isCloseTo(new BigDecimal("0.275580"), within(new BigDecimal("0.000001")));
    }

    // ── B1 — what-if correctness ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Acceptance 7: group totals = Σ per-employee figures exactly; annual = 12 × monthly")
    void totals_equal_sumOfRows() {
        CohortAggregateResponse r = service.aggregate(group(null, "PERCENT", "7.5", "GROSS", true), INDIVIDUAL);
        List<EmployeeWhatIfRow> in = r.employees().stream().filter(x -> !x.nonConverged()).toList();
        BigDecimal cur = in.stream().map(x -> x.current().loadedCost()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal proj = in.stream().map(x -> x.projected().loadedCost()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(r.currentMonthlyCost()).isEqualByComparingTo(cur);
        assertThat(r.projectedMonthlyCost()).isEqualByComparingTo(proj);
        assertThat(r.deltaMonthly()).isEqualByComparingTo(proj.subtract(cur));
        assertThat(r.deltaAnnual()).isEqualByComparingTo(r.deltaMonthly().multiply(BigDecimal.valueOf(12)));
        assertThat(r.headcount()).isEqualTo(in.size());
    }

    @ParameterizedTest(name = "{0} {1} on {2}")
    @CsvSource({ "PERCENT,5,NET", "PERCENT,-10,NET", "AMOUNT,250,NET", "AMOUNT,-300,NET",
                 "PERCENT,5,GROSS", "PERCENT,-10,GROSS", "AMOUNT,250,GROSS", "AMOUNT,-300,GROSS" })
    @DisplayName("Acceptance 6: the new figures satisfy the engine both ways (± 0.01)")
    void whatIf_roundTrips(String type, String value, String base) {
        CohortAggregateResponse r = service.aggregate(group(null, type, value, base, true), INDIVIDUAL);
        BigDecimal v = new BigDecimal(value);
        for (EmployeeWhatIfRow row : r.employees()) {
            assertThat(row.nonConverged()).isFalse();
            HrEmployeeDto e = byId(row.profileUserId());
            List<BenefitCatalogue> ben = benefitsOf(e.userId());
            if (base.equals("NET")) {
                BigDecimal expectedNet = apply(type, v, e.salaireNetRh());
                assertThat(row.projected().netInHand()).isCloseTo(expectedNet, within(CENT));
                BigDecimal back = simulator.computeFromGross(row.projected().gross(), ps, paramSets.loadRates(9L), ben,
                        List.of(), "CDI", 22, e.married(), row.children()).netInHand();
                assertThat(back).isCloseTo(expectedNet, within(CENT));
            } else {
                BigDecimal expectedGross = apply(type, v, row.current().gross());
                assertThat(row.projected().gross()).isEqualByComparingTo(expectedGross);
                PayrollSimulatorService.PayrollResult back = simulator.computeFromNet(row.projected().netInHand(), ps,
                        paramSets.loadRates(9L), ben, List.of(), "CDI", 22, e.married(), row.children());
                // gross → net is exact (top-down); net → gross is the engine's solver, which stops at
                // the parameter set's tolerance on the NET (0.01) — so the net comes back within 0.01
                // and the gross within 0.01 / marginal net rate (observed ≤ 0.04).
                assertThat(back.netInHand()).isCloseTo(row.projected().netInHand(), within(CENT));
                assertThat(back.gross()).isCloseTo(expectedGross, within(new BigDecimal("0.05")));
            }
        }
    }

    @ParameterizedTest(name = "employee {0}: {1} {2} on {3}")
    @CsvSource({ "4,PERCENT,5,NET", "4,AMOUNT,-200,GROSS", "2,PERCENT,12,GROSS", "2,AMOUNT,300,NET",
                 "5,PERCENT,-8,NET", "8,PERCENT,3,GROSS" })
    @DisplayName("Acceptance 6: single employee = individual simulator to the cent (incl. family status, own benefits)")
    void employee_equalsIndividualSimulator(long id, String type, String value, String base) {
        EmployeeWhatIfResponse w = service.whatIfEmployee(
                new EmployeeWhatIfRequest(TN, id, type, new BigDecimal(value), base), INDIVIDUAL);
        EmployeeWhatIfRow row = w.employee();
        IndividualSimulationService individual = individualSimulator();
        List<String> codes = id == 2L ? List.of("BEN_T") : null;   // own selection vs all (DEC5)

        SimulationRequest now = new SimulationRequest(TN, byId(id).salaireNetRh(), id, null, 22, codes, null, null,
                null, null, null, null, SimulationMode.NET_TO_BRUT, null, null);
        assertSame(individual.compute(now).result(), row.current());

        SimulationRequest next = base.equals("NET")
                ? new SimulationRequest(TN, apply(type, new BigDecimal(value), byId(id).salaireNetRh()), id, null, 22,
                    codes, null, null, null, null, null, null, SimulationMode.NET_TO_BRUT, null, null)
                : new SimulationRequest(TN, null, id, null, 22, codes, null, null, null, null, null, null,
                    SimulationMode.BRUT_TO_NET, apply(type, new BigDecimal(value), row.current().gross()), null);
        assertSame(individual.compute(next).result(), row.projected());
    }

    @Test
    @DisplayName("DEC12: employee mode refused without VIEW_INDIVIDUAL; unknown employee → 404")
    void employee_guards() {
        assertThatThrownBy(() -> service.whatIfEmployee(
                new EmployeeWhatIfRequest(TN, 4L, "PERCENT", BigDecimal.ONE, null), AGGREGATE))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.whatIfEmployee(
                new EmployeeWhatIfRequest(TN, 999L, "PERCENT", BigDecimal.ONE, null), INDIVIDUAL))
                .isInstanceOf(jakarta.persistence.EntityNotFoundException.class);
    }

    @Test
    @DisplayName("DEC8/DEC5: family status and own benefit selection reach the engine")
    void familyAndBenefits_reachEngine() {
        CohortAggregateResponse r = service.aggregate(group("G1", "PERCENT", "0", null, true), INDIVIDUAL);
        EmployeeWhatIfRow married = r.employees().stream().filter(x -> x.profileUserId() == 2L).findFirst().orElseThrow();
        assertThat(married.married()).isTrue();
        assertThat(married.children()).isEqualTo(2);
        assertThat(married.benefitsAssumed()).isFalse();
        // only BEN_T (taxable) selected → no exempt benefit in S4
        assertThat(married.current().grossWithBenefits()).isEqualByComparingTo(married.current().gross());
        EmployeeWhatIfRow single = r.employees().stream().filter(x -> x.profileUserId() == 1L).findFirst().orElseThrow();
        assertThat(single.benefitsAssumed()).isTrue();
        assertThat(single.current().grossWithBenefits()).isEqualByComparingTo(single.current().gross().add(new BigDecimal("50")));
    }

    // ── DEC15 — backward compatibility ──────────────────────────────────────────────────

    @Test
    @DisplayName("Acceptance 11: an old-format request returns every old field with its old name")
    void oldFormat_keepsOldFields() throws Exception {
        CohortAggregateResponse r = service.aggregate(
                new CohortFilterRequest(TN, "G2", null, null, null, "PCT", new BigDecimal("5")), INDIVIDUAL);
        JsonNode n = om.readTree(om.writeValueAsString(r));
        for (String f : List.of("headcount", "currentMonthlyCost", "projectedMonthlyCost", "deltaMonthly", "deltaAnnual",
                "currentMonthlyCostEur", "projectedMonthlyCostEur", "currentMonthlyCostChf", "projectedMonthlyCostChf",
                "localCurrency", "modifierType", "modifierValue", "appliedFilters")) {
            assertThat(n.has(f)).as(f).isTrue();
        }
        assertThat(n.get("modifierType").asText()).isEqualTo("PCT");
        assertThat(n.get("localCurrency").asText()).isEqualTo("TND");
        assertThat(n.get("headcount").asInt()).isEqualTo(5);
        assertThat(r.salaryBase()).isEqualTo("NET");
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────

    private IndividualSimulationService individualSimulator() {
        FxSnapshotService snap = mock(FxSnapshotService.class);
        when(snap.resolve(TN)).thenReturn(new FxSnapshotService.FxSnapshot("TND", null, null, null));
        return new IndividualSimulationService(paramSets, simulator, snap,
                new FxEntryConversionService(mock(PayrollFxRateRepository.class)),
                mock(SimulationResultRepository.class), om, hr);
    }

    private static void assertSame(PayrollSimulatorService.PayrollResult ref, EmployeeWhatIfRow.Figures f) {
        assertThat(f.netInHand()).isEqualByComparingTo(ref.netInHand());
        assertThat(f.gross()).isEqualByComparingTo(ref.gross());
        assertThat(f.loadedCost()).isEqualByComparingTo(ref.loadedCost());
        assertThat(f.irppAmount()).isEqualByComparingTo(ref.irppAmount());
        assertThat(f.employerCharges()).isEqualByComparingTo(ref.employerCharges());
        assertThat(f.employeeCharges()).isEqualByComparingTo(ref.employeeCharges());
    }

    private CohortFilterRequest group(String grade, String type, String value, String base, Boolean rows) {
        return new CohortFilterRequest(TN, grade, null, null, null, type,
                value == null ? null : new BigDecimal(value), base, rows);
    }

    private static BigDecimal apply(String type, BigDecimal v, BigDecimal base) {
        return type.startsWith("P")
                ? base.multiply(BigDecimal.ONE.add(v.divide(BigDecimal.valueOf(100)))).setScale(4, java.math.RoundingMode.HALF_UP)
                : base.add(v).setScale(4, java.math.RoundingMode.HALF_UP);
    }

    private HrEmployeeDto byId(long id) {
        return workforce.stream().filter(e -> e.userId() == id).findFirst().orElseThrow();
    }

    private List<BenefitCatalogue> benefitsOf(long id) {
        List<BenefitCatalogue> all = paramSets.loadBenefits(9L);
        return id == 2L ? all.stream().filter(b -> b.getBenefitCode().equals("BEN_T")).toList() : all;
    }

    private static List<BigDecimal> amountsOf(CohortAggregateResponse r) {
        List<BigDecimal> l = new ArrayList<>();
        l.add(r.currentMonthlyCost()); l.add(r.projectedMonthlyCost()); l.add(r.deltaMonthly()); l.add(r.deltaAnnual());
        l.add(r.currentMonthlyCostEur()); l.add(r.projectedMonthlyCostEur());
        l.add(r.currentMonthlyCostChf()); l.add(r.projectedMonthlyCostChf());
        l.add(r.avgNetBefore()); l.add(r.avgNetAfter()); l.add(r.avgGrossBefore()); l.add(r.avgGrossAfter());
        l.add(r.deltaPct());
        return l;
    }

    private static List<String> fieldNames(JsonNode n) {
        List<String> names = new ArrayList<>();
        collect(n, names);
        return names;
    }

    private static void collect(JsonNode n, List<String> out) {
        if (n.isObject()) {
            for (Iterator<String> it = n.fieldNames(); it.hasNext(); ) {
                String k = it.next();
                out.add(k);
                collect(n.get(k), out);
            }
        } else if (n.isArray()) {
            n.forEach(c -> collect(c, out));
        }
    }

    private static HrEmployeeDto emp(long id, String grade, String net, Boolean married, Integer children) {
        return new HrEmployeeDto(id, TN, "CDI", grade, "D1", net == null ? null : new BigDecimal(net), married, children);
    }

    private static SocialChargeRate rate(String code, String ee, String er, String base) {
        SocialChargeRate r = new SocialChargeRate();
        r.setContractType("CDI");
        r.setChargeCode(code);
        r.setChargeLabel(code);
        r.setEmployeeRate(new BigDecimal(ee));
        r.setEmployerRate(new BigDecimal(er));
        r.setBaseCalculation(base);
        r.setEvalOrder(0);
        return r;
    }

    private static BenefitCatalogue benefit(String code, String monthly, boolean taxable) {
        BenefitCatalogue b = new BenefitCatalogue();
        b.setBenefitCode(code);
        b.setMonthlyValue(new BigDecimal(monthly));
        b.setIsTaxable(taxable);
        return b;
    }

    private static PayrollFxRate fx(String code, String toEur) {
        PayrollFxRate r = new PayrollFxRate();
        r.setCurrencyCode(code);
        r.setRateToEur(new BigDecimal(toEur));
        r.setSyncedAt(LocalDateTime.of(2026, 11, 24, 17, 10));
        return r;
    }
}
