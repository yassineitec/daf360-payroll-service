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
 * PayrollRubriques (CREDIT/DEBIT) layer on top of the legacy BenefitCatalogue.
 * joursTravailes is required for FIXE_JOURNALIER rubriques (standard month = 22 days).
 */
@Service
public class PayrollSimulatorService {

    private static final int SCALE = 4;
    private static final int STANDARD_WORKING_DAYS = 22;

    private final IrppCalculatorService irppCalculator;
    private final ConvergenceEngine convergenceEngine;

    public PayrollSimulatorService(IrppCalculatorService irppCalculator,
                                   ConvergenceEngine convergenceEngine) {
        this.irppCalculator = irppCalculator;
        this.convergenceEngine = convergenceEngine;
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

        // Base charges on gross (used for POURCENTAGE_CHARGES mode)
        BigDecimal baseEmployeeCharges = computeCharges(gross, rates, contractType, true);
        BigDecimal baseEmployerCharges = computeCharges(gross, rates, contractType, false);
        BigDecimal totalBaseCharges = baseEmployeeCharges.add(baseEmployerCharges);

        // Filter and accumulate rubrique contributions
        List<PayrollRubrique> applicable = filterRubriques(rubriques, contractType);

        BigDecimal taxableCredit      = BigDecimal.ZERO;  // CREDIT, subject to IRPP → enters taxable base
        BigDecimal nonTaxableCredit   = BigDecimal.ZERO;  // CREDIT, exempt from IRPP → added to net
        BigDecimal totalDebit         = BigDecimal.ZERO;  // DEBIT → subtracted from net
        BigDecimal employerShareTotal = BigDecimal.ZERO;  // employer part of rubrique (goes into loaded cost)
        BigDecimal employeeShareTotal = BigDecimal.ZERO;  // employee part of rubrique (deducted from net)
        BigDecimal socialChargeAdj    = BigDecimal.ZERO;  // rubriques that widen the social charge base

        for (PayrollRubrique r : applicable) {
            BigDecimal amt = computeRubriqueAmount(r, gross, totalBaseCharges, joursTravailes);
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
        BigDecimal employeeCharges;
        BigDecimal employerCharges;
        if (socialChargeAdj.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal adjustedBase = gross.add(socialChargeAdj);
            employeeCharges = computeCharges(adjustedBase, rates, contractType, true);
            employerCharges = computeCharges(adjustedBase, rates, contractType, false);
        } else {
            employeeCharges = baseEmployeeCharges;
            employerCharges = baseEmployerCharges;
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
                iterations, convergenceOk);
    }

    // -----------------------------------------------------------------------
    //  Rubrique helpers
    // -----------------------------------------------------------------------

    private BigDecimal computeRubriqueAmount(PayrollRubrique r,
                                              BigDecimal gross,
                                              BigDecimal totalBaseCharges,
                                              int joursTravailes) {
        return switch (r.getCalcMode()) {
            case "FIXE_MENSUEL" ->
                    r.getAmount() != null ? r.getAmount() : BigDecimal.ZERO;
            case "FIXE_JOURNALIER" ->
                    r.getAmount() != null
                            ? r.getAmount()
                              .multiply(BigDecimal.valueOf(joursTravailes))
                              .divide(BigDecimal.valueOf(STANDARD_WORKING_DAYS), SCALE, RoundingMode.HALF_UP)
                            : BigDecimal.ZERO;
            case "POURCENTAGE_BRUT" ->
                    r.getRate() != null
                            ? gross.multiply(r.getRate()).setScale(SCALE, RoundingMode.HALF_UP)
                            : BigDecimal.ZERO;
            case "POURCENTAGE_CHARGES" ->
                    r.getRate() != null
                            ? totalBaseCharges.multiply(r.getRate()).setScale(SCALE, RoundingMode.HALF_UP)
                            : BigDecimal.ZERO;
            default -> BigDecimal.ZERO;
        };
    }

    private List<PayrollRubrique> filterRubriques(List<PayrollRubrique> rubriques, String contractType) {
        if (rubriques == null || rubriques.isEmpty()) return List.of();
        return rubriques.stream()
                .filter(r -> Boolean.TRUE.equals(r.getIsActive()))
                .filter(r -> r.getContractTypes() == null
                        || r.getContractTypes().isBlank()
                        || List.of(r.getContractTypes().split(",")).contains(contractType))
                .toList();
    }

    // -----------------------------------------------------------------------
    //  Social charge helpers
    // -----------------------------------------------------------------------

    private BigDecimal computeCharges(BigDecimal base,
                                       List<SocialChargeRate> rates,
                                       String contractType,
                                       boolean employee) {
        return rates.stream()
                .filter(r -> r.getContractType().equals(contractType))
                .map(r -> {
                    BigDecimal rate = employee ? r.getEmployeeRate() : r.getEmployerRate();
                    return applyRate(base, rate, r.getCapAmount());
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal applyRate(BigDecimal base, BigDecimal rate, BigDecimal cap) {
        if (rate == null) return BigDecimal.ZERO;
        BigDecimal effectiveBase = (cap != null) ? base.min(cap) : base;
        return effectiveBase.multiply(rate).setScale(SCALE, RoundingMode.HALF_UP);
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
            boolean convergenceOk
    ) {}
}
