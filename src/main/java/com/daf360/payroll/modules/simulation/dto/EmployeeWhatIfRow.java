package com.daf360.payroll.modules.simulation.dto;

import java.math.BigDecimal;

/**
 * One employee of a what-if: current and projected figures, straight from the engine
 * (unrounded, DEC14). Only ever returned to PAYROLL_VIEW_INDIVIDUAL holders (DEC1).
 *
 * @param grossSource  CONFIG = current gross read from employee_payroll_config and consistent
 *                     with the HR net; COMPUTED = current gross computed from the HR net (DEC6)
 */
public record EmployeeWhatIfRow(
        Long profileUserId,
        String contractType,
        String grade,
        String discipline,
        Figures current,
        Figures projected,
        BigDecimal deltaNet,
        BigDecimal deltaGross,
        BigDecimal deltaLoadedCost,
        BigDecimal deltaLoadedCostPct,
        boolean nonConverged,
        boolean benefitsAssumed,
        /** Has a payroll configuration whose benefit list is NULL or empty: computed without any benefit. */
        boolean benefitsNoneSelected,
        String grossSource,
        Boolean married,
        Integer children
) {

    /**
     * The five strata of a payslip plus the charges, as the individual simulator stores them:
     * S1 net versé, S2 net imposable, S3 brut, S4 brut + avantages exonérés, S5 coût chargé.
     */
    public record Figures(
            BigDecimal netInHand,
            BigDecimal netTaxable,
            BigDecimal gross,
            BigDecimal grossWithBenefits,
            BigDecimal loadedCost,
            BigDecimal irppAmount,
            BigDecimal employeeCharges,
            BigDecimal employerCharges,
            BigDecimal taxableBase,
            boolean convergenceOk,
            int iterationsUsed
    ) {}
}
