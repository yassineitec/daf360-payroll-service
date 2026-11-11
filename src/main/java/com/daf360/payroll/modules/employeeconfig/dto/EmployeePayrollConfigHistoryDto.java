package com.daf360.payroll.modules.employeeconfig.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * One salary change of an employee (Rémunération › Historique des modifications).
 *
 * @param previousGrossSalary gross of the change just before this one; null on the first save
 * @param previousNetSalary   net of the change just before this one; null on the first save
 * @param workingCurrency     currency of every amount above (the country's working currency)
 * @param changedByName       full name from users_ref; null when the user is not synced
 * @param effectiveDate       date the salary applies from; null on rows saved before V37
 *                            (screens fall back to changedAt)
 * @param benefits            selected benefits with their amounts AT SAVE TIME; on rows saved
 *                            before V37 only the codes are known (amount and labels null)
 */
public record EmployeePayrollConfigHistoryDto(
        Long id,
        Long paysId,
        String contractType,
        List<String> selectedBenefitCodes,
        List<BenefitSnapshot> benefits,
        BigDecimal currentGrossSalary,
        BigDecimal currentNetSalary,
        BigDecimal previousGrossSalary,
        BigDecimal previousNetSalary,
        String workingCurrency,
        String reason,
        Long changedBy,
        String changedByName,
        OffsetDateTime changedAt,
        LocalDate effectiveDate,
        String inputCurrency,
        BigDecimal inputAmountOriginal,
        /** Country director who approved it (V38); null for a direct save (first salary). */
        Long approvedBy,
        String approvedByName,
        OffsetDateTime approvedAt
) {
    /** A benefit as it was priced when the change was saved. */
    public record BenefitSnapshot(String code, String labelFr, String labelEn,
                                  BigDecimal monthlyValue, Boolean taxable) {}
}
