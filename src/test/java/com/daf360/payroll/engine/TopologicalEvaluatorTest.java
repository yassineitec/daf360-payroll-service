package com.daf360.payroll.engine;

import com.daf360.payroll.modules.parameterset.entity.PayrollRubrique;
import com.daf360.payroll.modules.parameterset.entity.SocialChargeRate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link TopologicalEvaluator}.
 *
 * These tests are intentionally standalone (no Spring context) — both services
 * are instantiated directly because they have no infrastructure dependencies.
 */
class TopologicalEvaluatorTest {

    private TopologicalEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new TopologicalEvaluator(new FormulaEvaluatorService());
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Backward-compatibility: rate-based charges
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Rate-based GROSS charge: EE = BRUT × employeeRate, ER = BRUT × employerRate")
    void rateBasedGrossCharge() {
        SocialChargeRate cnss = charge("CNSS", "CDI",
                new BigDecimal("0.0918"), new BigDecimal("0.1618"), "GROSS", null, null, null, 0);

        BigDecimal gross = new BigDecimal("3000");
        TopologicalEvaluator.EvaluationResult result =
                evaluator.evaluate(gross, List.of(cnss), "CDI", List.of(), 22);

        // 3000 × 0.0918 = 275.40
        assertThat(result.totalChargesEE()).isEqualByComparingTo("275.4000");
        // 3000 × 0.1618 = 485.40
        assertThat(result.totalChargesER()).isEqualByComparingTo("485.4000");
        // context variables populated
        assertThat(result.context()).containsKey("CNSS_EE");
        assertThat(result.context().get("CNSS_EE")).isEqualByComparingTo("275.4000");
        assertThat(result.context()).containsKey("CNSS_ER");
        assertThat(result.context().get("CNSS_ER")).isEqualByComparingTo("485.4000");
        // aggregate variables
        assertThat(result.context().get("CHARGES_EE")).isEqualByComparingTo("275.4000");
        assertThat(result.context().get("CHARGES_ER")).isEqualByComparingTo("485.4000");
    }

    @Test
    @DisplayName("Rate-based CAPPED_GROSS charge: cap is respected (base = min(BRUT, cap))")
    void rateBasedCappedCharge() {
        // cap = 2000, BRUT = 3000 → effective base = 2000
        SocialChargeRate css = charge("CSS", "CDI",
                new BigDecimal("0.01"), new BigDecimal("0.02"), "CAPPED_GROSS",
                new BigDecimal("2000"), null, null, 0);

        TopologicalEvaluator.EvaluationResult result =
                evaluator.evaluate(new BigDecimal("3000"), List.of(css), "CDI", List.of(), 22);

        // 2000 × 0.01 = 20.00
        assertThat(result.totalChargesEE()).isEqualByComparingTo("20.0000");
        // 2000 × 0.02 = 40.00
        assertThat(result.totalChargesER()).isEqualByComparingTo("40.0000");
    }

    @Test
    @DisplayName("Contract type filter: charges for other types are ignored")
    void contractTypeFilter() {
        SocialChargeRate cdiCharge = charge("CNSS", "CDI",
                new BigDecimal("0.09"), BigDecimal.ZERO, "GROSS", null, null, null, 0);
        SocialChargeRate cddCharge = charge("CNSS", "CDD",
                new BigDecimal("0.05"), BigDecimal.ZERO, "GROSS", null, null, null, 0);

        TopologicalEvaluator.EvaluationResult result =
                evaluator.evaluate(new BigDecimal("1000"), List.of(cdiCharge, cddCharge), "CDI", List.of(), 22);

        // Only CDI charge applied: 1000 × 0.09 = 90
        assertThat(result.totalChargesEE()).isEqualByComparingTo("90.0000");
    }

    @Test
    @DisplayName("Multiple rate-based charges sum correctly into CHARGES_EE / CHARGES_ER")
    void multipleRateBasedCharges() {
        BigDecimal gross = new BigDecimal("2000");
        SocialChargeRate cnss = charge("CNSS", "CDI",
                new BigDecimal("0.0918"), new BigDecimal("0.1618"), "GROSS", null, null, null, 0);
        SocialChargeRate css = charge("CSS", "CDI",
                new BigDecimal("0.01"), new BigDecimal("0.02"), "GROSS", null, null, null, 0);

        TopologicalEvaluator.EvaluationResult result =
                evaluator.evaluate(gross, List.of(cnss, css), "CDI", List.of(), 22);

        // CNSS_EE = 2000 × 0.0918 = 183.60, CSS_EE = 2000 × 0.01 = 20.00 → total = 203.60
        assertThat(result.totalChargesEE()).isEqualByComparingTo("203.6000");
        // CNSS_ER = 2000 × 0.1618 = 323.60, CSS_ER = 2000 × 0.02 = 40.00 → total = 363.60
        assertThat(result.totalChargesER()).isEqualByComparingTo("363.6000");
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Formula-based charges
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Formula charge EE uses custom expression referencing BRUT")
    void formulaChargeReferencesBrut() {
        SocialChargeRate charge = charge("TFP", "CDI",
                BigDecimal.ZERO, BigDecimal.ZERO, "FORMULE", null,
                "BRUT * 0.02", "BRUT * 0.03", 0);

        TopologicalEvaluator.EvaluationResult result =
                evaluator.evaluate(new BigDecimal("5000"), List.of(charge), "CDI", List.of(), 22);

        // EE = 5000 × 0.02 = 100
        assertThat(result.totalChargesEE()).isEqualByComparingTo("100.0000");
        // ER = 5000 × 0.03 = 150
        assertThat(result.totalChargesER()).isEqualByComparingTo("150.0000");
    }

    @Test
    @DisplayName("Formula charge can reference a prior charge's _EE output")
    void formulaChargeReferencesPriorCharge() {
        // CNSS runs first (evalOrder=0), produces CNSS_EE = 1000 × 0.10 = 100
        SocialChargeRate cnss = charge("CNSS", "CDI",
                new BigDecimal("0.10"), BigDecimal.ZERO, "GROSS", null, null, null, 0);

        // TFP formula = CNSS_EE * 0.5 → 100 × 0.5 = 50
        SocialChargeRate tfp = charge("TFP", "CDI",
                BigDecimal.ZERO, BigDecimal.ZERO, "FORMULE", null,
                "CNSS_EE * 0.5", "CNSS_EE * 0.5", 10);

        TopologicalEvaluator.EvaluationResult result =
                evaluator.evaluate(new BigDecimal("1000"), List.of(cnss, tfp), "CDI", List.of(), 22);

        assertThat(result.context().get("CNSS_EE")).isEqualByComparingTo("100.0000");
        assertThat(result.context().get("TFP_EE")).isEqualByComparingTo("50.0000");
        assertThat(result.totalChargesEE()).isEqualByComparingTo("150.0000");
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Cycle detection
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Cyclic charge dependency throws CyclicDependencyException")
    void cyclicChargeDependencyThrows() {
        // A references B_EE → A depends on B
        // B references A_EE → B depends on A
        SocialChargeRate a = charge("AAA", "CDI",
                BigDecimal.ZERO, BigDecimal.ZERO, "FORMULE", null,
                "BBB_EE * 1", null, 0);
        SocialChargeRate b = charge("BBB", "CDI",
                BigDecimal.ZERO, BigDecimal.ZERO, "FORMULE", null,
                "AAA_EE * 1", null, 0);

        assertThatThrownBy(() ->
                evaluator.evaluate(new BigDecimal("1000"), List.of(a, b), "CDI", List.of(), 22))
                .isInstanceOf(CyclicDependencyException.class)
                .hasMessageContaining("Cycle détecté");
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Rubrique evaluation
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("FIXE_MENSUEL rubrique returns fixed amount unchanged")
    void fixeMensuelRubrique() {
        PayrollRubrique rubrique = rubrique("TRANSPORT", "CDI", "FIXE_MENSUEL",
                new BigDecimal("150.00"), null, null, null, true, 0);

        TopologicalEvaluator.EvaluationResult result =
                evaluator.evaluate(new BigDecimal("3000"), List.of(), "CDI", List.of(rubrique), 22);

        assertThat(result.evaluatedRubriques()).hasSize(1);
        assertThat(result.evaluatedRubriques().get(0).amount()).isEqualByComparingTo("150.0000");
    }

    @Test
    @DisplayName("POURCENTAGE_BRUT rubrique computes rate × gross")
    void pourcentageBrutRubrique() {
        PayrollRubrique rubrique = rubrique("PRIME_TRANSPORT", "CDI", "POURCENTAGE_BRUT",
                null, new BigDecimal("0.05"), null, null, true, 0);

        TopologicalEvaluator.EvaluationResult result =
                evaluator.evaluate(new BigDecimal("2000"), List.of(), "CDI", List.of(rubrique), 22);

        // 2000 × 0.05 = 100
        assertThat(result.evaluatedRubriques().get(0).amount()).isEqualByComparingTo("100.0000");
    }

    @Test
    @DisplayName("FORMULE rubrique can reference CHARGES_EE from Phase 1.5")
    void formulaRubriqueReferencesChargesEE() {
        SocialChargeRate cnss = charge("CNSS", "CDI",
                new BigDecimal("0.10"), BigDecimal.ZERO, "GROSS", null, null, null, 0);

        // rubrique formula = CHARGES_EE (= CNSS_EE = 1000 × 0.10 = 100)
        PayrollRubrique rubrique = rubrique("ADJ", "CDI", "FORMULE",
                null, null, null, "CHARGES_EE", true, 0);

        TopologicalEvaluator.EvaluationResult result =
                evaluator.evaluate(new BigDecimal("1000"), List.of(cnss), "CDI", List.of(rubrique), 22);

        assertThat(result.evaluatedRubriques().get(0).amount()).isEqualByComparingTo("100.0000");
    }

    @Test
    @DisplayName("FORMULE rubrique referencing a prior rubrique by code")
    void formulaRubriqueReferencesPriorRubrique() {
        PayrollRubrique base = rubrique("PRIME_BASE", "CDI", "FIXE_MENSUEL",
                new BigDecimal("200"), null, null, null, true, 10);
        // BONUS = PRIME_BASE * 0.5 = 100
        PayrollRubrique bonus = rubrique("BONUS", "CDI", "FORMULE",
                null, null, null, "PRIME_BASE * 0.5", true, 20);

        TopologicalEvaluator.EvaluationResult result =
                evaluator.evaluate(new BigDecimal("3000"), List.of(), "CDI", List.of(base, bonus), 22);

        assertThat(result.evaluatedRubriques()).hasSize(2);
        assertThat(result.evaluatedRubriques().get(1).amount()).isEqualByComparingTo("100.0000");
    }

    @Test
    @DisplayName("Inactive rubriques are excluded from evaluation")
    void inactiveRubriqueExcluded() {
        PayrollRubrique active = rubrique("ACTIVE_R", "CDI", "FIXE_MENSUEL",
                new BigDecimal("100"), null, null, null, true, 0);
        PayrollRubrique inactive = rubrique("INACTIVE_R", "CDI", "FIXE_MENSUEL",
                new BigDecimal("999"), null, null, null, false, 0);

        TopologicalEvaluator.EvaluationResult result =
                evaluator.evaluate(new BigDecimal("1000"), List.of(), "CDI", List.of(active, inactive), 22);

        assertThat(result.evaluatedRubriques()).hasSize(1);
        assertThat(result.evaluatedRubriques().get(0).amount()).isEqualByComparingTo("100.0000");
    }

    @Test
    @DisplayName("sanitize() uppercases and replaces non-alphanumeric with underscore")
    void sanitize() {
        assertThat(TopologicalEvaluator.sanitize("cnss")).isEqualTo("CNSS");
        assertThat(TopologicalEvaluator.sanitize("prime-transport")).isEqualTo("PRIME_TRANSPORT");
        assertThat(TopologicalEvaluator.sanitize("prime ancienneté")).isEqualTo("PRIME_ANCIENNET_");
        assertThat(TopologicalEvaluator.sanitize(null)).isEqualTo("UNKNOWN");
        assertThat(TopologicalEvaluator.sanitize("  ")).isEqualTo("UNKNOWN");
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Builders
    // ─────────────────────────────────────────────────────────────────────────

    private static SocialChargeRate charge(String code, String contractType,
                                            BigDecimal eeRate, BigDecimal erRate,
                                            String baseCalc, BigDecimal cap,
                                            String formulaEe, String formulaEr,
                                            int evalOrder) {
        SocialChargeRate r = new SocialChargeRate();
        r.setChargeCode(code);
        r.setChargeLabel(code);
        r.setContractType(contractType);
        r.setEmployeeRate(eeRate != null ? eeRate : BigDecimal.ZERO);
        r.setEmployerRate(erRate != null ? erRate : BigDecimal.ZERO);
        r.setBaseCalculation(baseCalc);
        r.setCapAmount(cap);
        r.setFormulaEe(formulaEe);
        r.setFormulaEr(formulaEr);
        r.setEvalOrder(evalOrder);
        return r;
    }

    private static PayrollRubrique rubrique(String code, String contractType, String calcMode,
                                             BigDecimal amount, BigDecimal rate, BigDecimal cap,
                                             String formula, boolean active, int displayOrder) {
        PayrollRubrique r = new PayrollRubrique();
        r.setCode(code);
        r.setLabelFr(code);
        r.setContractTypes(contractType);
        r.setCalcMode(calcMode);
        r.setAmount(amount);
        r.setRate(rate);
        r.setCapAmount(cap);
        r.setFormulaExpression(formula);
        r.setIsActive(active);
        r.setDisplayOrder(displayOrder);
        r.setNature("AVANTAGE");
        r.setDirection("CREDIT");
        r.setEmployeeSharePct(BigDecimal.ZERO);
        r.setEmployerSharePct(BigDecimal.ZERO);
        r.setIsSubjectToSocialCharges(false);
        r.setIsSubjectToIrpp(true);
        return r;
    }
}
