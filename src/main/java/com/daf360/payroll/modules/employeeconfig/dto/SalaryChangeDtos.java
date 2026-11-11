package com.daf360.payroll.modules.employeeconfig.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** Salary change requests (V38): RH / payroll submit, the country director decides. */
public final class SalaryChangeDtos {

    private SalaryChangeDtos() {}

    /** « Nouvelle rémunération » submitted for approval — the same fields as a save. */
    public record SubmitSalaryChangeRequest(
            @NotNull Long paysId,
            @NotBlank String contractType,
            List<String> selectedBenefitCodes,
            /** As typed, in inputCurrency (null = working currency). */
            @NotNull @Positive BigDecimal grossSalary,
            String inputCurrency,
            LocalDate effectiveDate,
            @NotBlank String reason
    ) {}

    /** Approve (notes optional) / reject (notes required) / cancel (notes optional). */
    public record DecisionRequest(String notes) {}

    public record SalaryChangeDto(
            Long id,
            Long profileUserId,
            String employeeName,
            Long paysId,
            String status,
            String contractType,
            String currentContractType,
            List<String> selectedBenefitCodes,
            BigDecimal grossSalary,
            String inputCurrency,
            String workingCurrency,
            BigDecimal currentGrossSalary,
            BigDecimal currentNetSalary,
            BigDecimal newGrossSalary,
            BigDecimal newNetSalary,
            BigDecimal monthlyCostBefore,
            BigDecimal monthlyCostAfter,
            LocalDate effectiveDate,
            String reason,
            /** The simulation the approver decides on (null if it could not be read back). */
            RemunerationSimulationDto simulation,
            Long submittedBy,
            String submittedByName,
            OffsetDateTime submittedAt,
            Long decidedBy,
            String decidedByName,
            OffsetDateTime decidedAt,
            String decisionNotes
    ) {}
}
