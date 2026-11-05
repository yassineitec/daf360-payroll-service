package com.daf360.payroll.engine;

import com.daf360.payroll.modules.parameterset.entity.ParameterSet;
import com.daf360.payroll.modules.parameterset.entity.SocialChargeRate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for the Tunisia payroll configuration audit
 * (docs/PAYROLL_CONFIG_VERIFICATION.md): IRPP annualization, the 10%/2000 abattement,
 * family deductions, and CSS modeled on the post-abattement base (TAXABLE_BASE2) instead
 * of gross. Reference vectors reproduced from the HR/payroll team's own worksheet
 * (CALCUL_SALAIRE.xlsx), tolerance 0.01 TND as used by the audit.
 *
 * <p>Standalone — no Spring context, mirrors {@link TopologicalEvaluatorTest}.
 */
class PayrollSimulatorServiceTunisiaConfigTest {

    private PayrollSimulatorService simulator;
    private ParameterSet ps;

    @BeforeEach
    void setUp() {
        simulator = new PayrollSimulatorService(
                new IrppCalculatorService(new ObjectMapper()),
                new ConvergenceEngine(),
                new TopologicalEvaluator(new FormulaEvaluatorService()));

        ps = new ParameterSet();
        ps.setIrppBrackets("[" +
                "{\"lower\":0,\"upper\":5000,\"rate\":0}," +
                "{\"lower\":5000,\"upper\":10000,\"rate\":0.15}," +
                "{\"lower\":10000,\"upper\":20000,\"rate\":0.25}," +
                "{\"lower\":20000,\"upper\":30000,\"rate\":0.30}," +
                "{\"lower\":30000,\"upper\":40000,\"rate\":0.33}," +
                "{\"lower\":40000,\"upper\":50000,\"rate\":0.36}," +
                "{\"lower\":50000,\"upper\":70000,\"rate\":0.38}," +
                "{\"lower\":70000,\"upper\":null,\"rate\":0.40}]");
        ps.setConvergenceTolerance(new BigDecimal("0.01"));
        ps.setMaxConvergenceIterations(50);
        ps.setAbattementRatePct(new BigDecimal("0.10"));
        ps.setAbattementAnnualCap(new BigDecimal("2000"));
        ps.setFamilyDeductionMarriedAmount(new BigDecimal("300"));
        ps.setFamilyDeductionPerChildAmount(new BigDecimal("100"));
        ps.setFamilyDeductionMaxChildren(4);
    }

    private List<SocialChargeRate> tunisiaRates() {
        SocialChargeRate cnss = rate("CNSS", "0.0968", "0.1657", "GROSS", null);
        SocialChargeRate css  = rate("CSS", "0.005", "0", "TAXABLE_BASE2", null);
        return List.of(cnss, css);
    }

    private SocialChargeRate rate(String code, String eeRate, String erRate, String baseCalc, BigDecimal cap) {
        SocialChargeRate r = new SocialChargeRate();
        r.setContractType("CDI");
        r.setChargeCode(code);
        r.setChargeLabel(code);
        r.setEmployeeRate(new BigDecimal(eeRate));
        r.setEmployerRate(new BigDecimal(erRate));
        r.setBaseCalculation(baseCalc);
        r.setCapAmount(cap);
        r.setEvalOrder(0);
        return r;
    }

    @Test
    @DisplayName("Gross 3000, single, no children: IRPP/CSS/NET match the reference worksheet")
    void grossToNet_single_3000() {
        PayrollSimulatorService.PayrollResult result = simulator.computeFromGross(
                new BigDecimal("3000"), ps, tunisiaRates(), List.of(), List.of(), "CDI", 22);

        assertThat(result.taxableBase()).isEqualByComparingTo("2709.6000");
        assertThat(result.irppAmount()).isEqualByComparingTo("535.0233");
        assertThat(result.netInHand()).isEqualByComparingTo("2161.8617");
        // CNSS_EE (290.400) + CSS (12.715) reported together for transparency
        assertThat(result.employeeCharges()).isEqualByComparingTo("303.1150");
    }

    @Test
    @DisplayName("Gross 3000, married + 2 children: family deduction lowers IRPP base to 30016")
    void grossToNet_married_twoChildren_3000() {
        PayrollSimulatorService.PayrollResult result = simulator.computeFromGross(
                new BigDecimal("3000"), ps, tunisiaRates(), List.of(), List.of(), "CDI", 22,
                true, 2);

        // taxableBase (monthly) is unaffected by family deductions — only the IRPP base is
        assertThat(result.taxableBase()).isEqualByComparingTo("2709.6000");
        assertThat(result.irppAmount()).isEqualByComparingTo("521.2733");
        assertThat(result.netInHand()).isEqualByComparingTo("2175.8200");
    }

    @Test
    @DisplayName("Children beyond familyDeductionMaxChildren don't widen the deduction further")
    void familyDeduction_cappedAtMaxChildren() {
        PayrollSimulatorService.PayrollResult withFour = simulator.computeFromGross(
                new BigDecimal("3000"), ps, tunisiaRates(), List.of(), List.of(), "CDI", 22, true, 4);
        PayrollSimulatorService.PayrollResult withTen = simulator.computeFromGross(
                new BigDecimal("3000"), ps, tunisiaRates(), List.of(), List.of(), "CDI", 22, true, 10);

        assertThat(withTen.irppAmount()).isEqualByComparingTo(withFour.irppAmount());
        assertThat(withTen.netInHand()).isEqualByComparingTo(withFour.netInHand());
    }

    @Test
    @DisplayName("A parameter set with all-zero abattement/family fields reproduces pre-fix behaviour exactly")
    void zeroedFields_matchLegacyBehaviour() {
        ps.setAbattementRatePct(BigDecimal.ZERO);
        ps.setAbattementAnnualCap(null);
        ps.setFamilyDeductionMarriedAmount(BigDecimal.ZERO);
        ps.setFamilyDeductionPerChildAmount(BigDecimal.ZERO);
        ps.setFamilyDeductionMaxChildren(0);

        List<SocialChargeRate> cnssOnly = List.of(rate("CNSS", "0.0968", "0.1657", "GROSS", null));

        PayrollSimulatorService.PayrollResult result = simulator.computeFromGross(
                new BigDecimal("3000"), ps, cnssOnly, List.of(), List.of(), "CDI", 22);

        // annualBase = 2709.60 × 12 = 32515.20, base2 = ROUNDUP(32515.20) = 32516 (no abattement)
        // IRPP(32516) = 750 + 2500 + 3000 + 2516×0.33 = 7080.28 → /12 = 590.0233
        assertThat(result.irppAmount()).isEqualByComparingTo("590.0233");
        assertThat(result.employeeCharges()).isEqualByComparingTo("290.4000");
    }
}
