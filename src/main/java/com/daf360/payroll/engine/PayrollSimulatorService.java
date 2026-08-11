package com.daf360.payroll.engine;

import com.daf360.payroll.modules.parameterset.entity.BenefitCatalogue;
import com.daf360.payroll.modules.parameterset.entity.ParameterSet;
import com.daf360.payroll.modules.parameterset.entity.PayrollRubrique;
import com.daf360.payroll.modules.parameterset.entity.SocialChargeRate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Orchestrates the 5-strata payroll calculation:
 *   Net in hand → Net taxable → Base imposable → Brut → Coût chargé
 *
 * Two entry points:
 *   computeFromGross  — straightforward top-down for verification / budgeting
 *   computeFromNet    — uses ConvergenceEngine to invert gross→net, then top-down
 *
 * <h3>Computation engine</h3>
 * All social charge lines and payroll rubriques are evaluated by {@link TopologicalEvaluator},
 * which resolves them in topological dependency order and supports:
 * <ul>
 *   <li>Charge formulas that reference BRUT or results of prior charge lines</li>
 *   <li>Rubrique formulas that reference BRUT, any charge result, the aggregate
 *       CHARGES_EE/CHARGES_ER, and results of prior rubriques</li>
 *   <li>Automatic cycle detection — throws {@link CyclicDependencyException} if a
 *       dependency loop is present</li>
 * </ul>
 *
 * <h3>Backward compatibility</h3>
 * Existing rate-based charges (no formula set) and all legacy BenefitCatalogue rows
 * continue to work unchanged.
 */
@Service
public class PayrollSimulatorService {

    private static final int SCALE = 4;

    private final IrppCalculatorService irppCalculator;
    private final ConvergenceEngine convergenceEngine;
    private final TopologicalEvaluator topologicalEvaluator;

    public PayrollSimulatorService(IrppCalculatorService irppCalculator,
                                   ConvergenceEngine convergenceEngine,
                                   TopologicalEvaluator topologicalEvaluator) {
        this.irppCalculator      = irppCalculator;
        this.convergenceEngine   = convergenceEngine;
        this.topologicalEvaluator = topologicalEvaluator;
    }

    // -----------------------------------------------------------------------
    //  Public API
    // -----------------------------------------------------------------------

    public PayrollResult computeFromGross(BigDecimal gross,
                                          ParameterSet ps,
                                          List<SocialChargeRate> rates,
                                          List<BenefitCatalogue> benefits,
                                          List<PayrollRubrique> rubriques,
                                          String contractType,
                                          int joursTravailes) {
        return topDown(gross, ps, rates, benefits, rubriques, contractType, joursTravailes, 0, true);
    }

    public PayrollResult computeFromNet(BigDecimal targetNet,
                                        ParameterSet ps,
                                        List<SocialChargeRate> rates,
                                        List<BenefitCatalogue> benefits,
                                        List<PayrollRubrique> rubriques,
                                        String contractType,
                                        int joursTravailes) {

        BigDecimal tolerance = ps.getConvergenceTolerance() != null
                ? ps.getConvergenceTolerance()
                : new BigDecimal("0.01");
        int maxIter = ps.getMaxConvergenceIterations() != null
                ? ps.getMaxConvergenceIterations()
                : 50;

        BigDecimal lo = targetNet;
        BigDecimal hi = targetNet.multiply(new BigDecimal("3"));

        ConvergenceEngine.Result cr;
        try {
            cr = convergenceEngine.solve(
                    gross -> topDown(gross, ps, rates, benefits, rubriques, contractType, joursTravailes, 0, true)
                            .netInHand().subtract(targetNet),
                    targetNet.multiply(new BigDecimal("1.5")),
                    lo, hi,
                    tolerance, maxIter);
        } catch (ConvergenceException ex) {
            return topDown(ex.getLastEstimate(), ps, rates, benefits, rubriques,
                    contractType, joursTravailes, ex.getIterations(), false);
        }

        return topDown(cr.value(), ps, rates, benefits, rubriques, contractType, joursTravailes,
                cr.iterations(), cr.converged());
    }

    // -----------------------------------------------------------------------
    //  5-strata top-down computation
    // -----------------------------------------------------------------------

    private PayrollResult topDown(BigDecimal gross,
                                   ParameterSet ps,
                                   List<SocialChargeRate> rates,
                                   List<BenefitCatalogue> benefits,
                                   List<PayrollRubrique> rubriques,
                                   String contractType,
                                   int joursTravailes,
                                   int iterations,
                                   boolean convergenceOk) {

        // Evaluate all charges + rubriques in topological dependency order
        TopologicalEvaluator.EvaluationResult eval =
                topologicalEvaluator.evaluate(gross, rates, contractType, rubriques, joursTravailes);

        BigDecimal employeeCharges = eval.totalChargesEE();
        BigDecimal employerCharges = eval.totalChargesER();

        BigDecimal taxableCredit      = BigDecimal.ZERO;
        BigDecimal nonTaxableCredit   = BigDecimal.ZERO;
        BigDecimal totalDebit         = BigDecimal.ZERO;
        BigDecimal employerShareTotal = BigDecimal.ZERO;
        BigDecimal employeeShareTotal = BigDecimal.ZERO;
        BigDecimal socialChargeAdj    = BigDecimal.ZERO;

        for (TopologicalEvaluator.EvaluatedRubrique er : eval.evaluatedRubriques()) {
            PayrollRubrique r   = er.rubrique();
            BigDecimal     amt  = er.amount();

            if ("CREDIT".equals(r.getDirection())) {
                if (Boolean.TRUE.equals(r.getIsSubjectToIrpp())) {
                    taxableCredit = taxableCredit.add(amt);
                } else {
                    nonTaxableCredit = nonTaxableCredit.add(amt);
                }
                if (Boolean.TRUE.equals(r.getIsSubjectToSocialCharges())) {
                    socialChargeAdj = socialChargeAdj.add(amt);
                }
                BigDecimal erPct = r.getEmployerSharePct() != null ? r.getEmployerSharePct() : BigDecimal.ZERO;
                BigDecimal eePct = r.getEmployeeSharePct() != null ? r.getEmployeeSharePct() : BigDecimal.ZERO;
                employerShareTotal = employerShareTotal.add(amt.multiply(erPct).setScale(SCALE, RoundingMode.HALF_UP));
                employeeShareTotal = employeeShareTotal.add(amt.multiply(eePct).setScale(SCALE, RoundingMode.HALF_UP));
            } else {
                totalDebit = totalDebit.add(amt);
            }
        }

        // Recalculate charges if any rubrique widens the social charge base
        if (socialChargeAdj.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal adjustedBase = gross.add(socialChargeAdj);
            TopologicalEvaluator.EvaluationResult adjusted =
                    topologicalEvaluator.evaluate(adjustedBase, rates, contractType, List.of(), joursTravailes);
            employeeCharges = adjusted.totalChargesEE();
            employerCharges = adjusted.totalChargesER();
        }

        // Legacy BenefitCatalogue (backward compatibility)
        BigDecimal taxableBenefits    = computeTaxableBenefits(benefits);
        BigDecimal nonTaxableBenefits = computeNonTaxableBenefits(benefits);

        // Strata 3 — Base imposable
        BigDecimal taxableBase = gross
                .subtract(employeeCharges)
                .add(taxableBenefits)
                .add(taxableCredit)
                .setScale(SCALE, RoundingMode.HALF_UP);

        // Strata 2 — IRPP
        BigDecimal irpp = irppCalculator.compute(ps.getIrppBrackets(), taxableBase);
        BigDecimal netTaxable = taxableBase;

        // Strata 1 — Net in hand
        BigDecimal netInHand = netTaxable
                .subtract(irpp)
                .add(nonTaxableBenefits)
                .add(nonTaxableCredit)
                .subtract(totalDebit)
                .subtract(employeeShareTotal)
                .setScale(SCALE, RoundingMode.HALF_UP);

        // Strata 5 — Coût chargé
        BigDecimal loadedCost = gross
                .add(employerCharges)
                .add(employerShareTotal)
                .setScale(SCALE, RoundingMode.HALF_UP);

        return new PayrollResult(
                netInHand, netTaxable, taxableBase, gross, loadedCost,
                irpp, employeeCharges, employerCharges,
                taxableCredit.add(nonTaxableCredit), totalDebit, employerShareTotal,
                iterations, convergenceOk,
                eval.evaluatedRubriques());
    }

    // -----------------------------------------------------------------------
    //  Legacy BenefitCatalogue helpers
    // -----------------------------------------------------------------------

    private BigDecimal computeTaxableBenefits(List<BenefitCatalogue> benefits) {
        return benefits.stream()
                .filter(b -> Boolean.TRUE.equals(b.getIsTaxable()))
                .map(b -> b.getMonthlyValue() != null ? b.getMonthlyValue() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal computeNonTaxableBenefits(List<BenefitCatalogue> benefits) {
        return benefits.stream()
                .filter(b -> Boolean.FALSE.equals(b.getIsTaxable()))
                .map(b -> b.getMonthlyValue() != null ? b.getMonthlyValue() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // -----------------------------------------------------------------------
    //  Result record
    // -----------------------------------------------------------------------

    public record PayrollResult(
            BigDecimal netInHand,
            BigDecimal netTaxable,
            BigDecimal taxableBase,
            BigDecimal gross,
            BigDecimal loadedCost,
            BigDecimal irppAmount,
            BigDecimal employeeCharges,
            BigDecimal employerCharges,
            BigDecimal rubriquesCredit,        // total CREDIT rubriques applied
            BigDecimal rubriquesDebit,         // total DEBIT rubriques applied
            BigDecimal employerShareRubriques, // employer contribution included in loadedCost
            int iterationsUsed,
            boolean convergenceOk,
            List<TopologicalEvaluator.EvaluatedRubrique> evaluatedRubriques
    ) {}
}
