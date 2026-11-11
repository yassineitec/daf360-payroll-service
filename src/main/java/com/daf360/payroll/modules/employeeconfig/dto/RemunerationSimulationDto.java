package com.daf360.payroll.modules.employeeconfig.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * The payroll SIMULATION engine's breakdown for the « Nouvelle rémunération » form: the
 * regular month (base salary + benefits) and, when the employee has exceptional bonuses that
 * month, the same month with them added to the gross. Every amount is in {@code currency}.
 *
 * @param monthly     regular month — base salary + selected benefits
 * @param withBonuses the month of {@code periodYear/periodMonth} with its bonuses added to the
 *                    gross (subject to charges and IRPP); null when there is no bonus that month
 */
public record RemunerationSimulationDto(
        String currency,
        String contractType,
        Boolean married,
        Integer children,
        int periodYear,
        int periodMonth,
        List<Benefit> benefits,
        List<Bonus> bonuses,
        Breakdown monthly,
        Breakdown withBonuses
) {
    /** A selected benefit with its monthly value; taxable ones enter the IRPP base. */
    public record Benefit(String code, String labelFr, String labelEn, BigDecimal monthlyValue, Boolean taxable) {}

    /** An exceptional bonus of the month, as recorded and converted to {@code currency}. */
    public record Bonus(Long id, String label, BigDecimal amount, String bonusCurrency, BigDecimal amountInCurrency) {}

    /**
     * gross = baseSalary + bonuses. taxableBase = gross − employeeChargesOnGross + taxableBenefits.
     * net = taxableBase − irpp − (other employee charges) + nonTaxableBenefits.
     */
    public record Breakdown(
            BigDecimal baseSalary,
            BigDecimal bonuses,
            BigDecimal gross,
            BigDecimal taxableBenefits,
            BigDecimal nonTaxableBenefits,
            BigDecimal employeeCharges,
            BigDecimal taxableBase,
            BigDecimal irpp,
            BigDecimal net,
            BigDecimal employerCharges,
            BigDecimal employerCost,
            boolean convergenceOk
    ) {}
}
