package com.daf360.payroll.modules.salaryadvance.dto;

import com.daf360.payroll.modules.salaryadvance.entity.ContractStatus;
import com.daf360.payroll.modules.salaryadvance.entity.InstallmentStatus;
import com.daf360.payroll.modules.salaryadvance.entity.SalaryAdvanceStatus;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Every request and response shape of {@code /api/payroll/salary-advances}, as records —
 * one file because they only make sense together. Months travel as "yyyy-MM".
 */
public final class SalaryAdvanceDtos {
    private SalaryAdvanceDtos() {}

    // ── Requests ────────────────────────────────────────────────────────────

    /**
     * What the employee asks (spec §2): an amount, the desired availability date and a number
     * of months. No user id: the employee is always the caller. No ceiling on the amount.
     */
    public record SubmitRequest(
            @NotNull @DecimalMin("0.001") @Digits(integer = 12, fraction = 3) BigDecimal amount,
            @NotNull @Min(1) @Max(24) Integer installments,
            @NotNull LocalDate availabilityDate,
            @Size(max = 1000) String reason
    ) {}

    /** A director validation, a refusal, a withdrawal. Notes required on a refusal. */
    public record DecisionRequest(@Size(max = 1000) String notes) {}

    /**
     * The DAF grants the advance with its final terms (spec §4). The schedule and the debt
     * contract are built from these, never from what was asked.
     */
    public record ApproveRequest(
            @NotNull @DecimalMin("0.001") @Digits(integer = 12, fraction = 3) BigDecimal grantedAmount,
            @NotNull @Min(1) @Max(24) Integer grantedInstallments,
            @NotNull LocalDate effectiveDate,
            @NotBlank @Pattern(regexp = "\\d{4}-\\d{2}") String firstDeductionMonth,
            @Size(max = 1000) String notes
    ) {}

    /** Payroll confirms the payout. The amount is the granted one, never a field. */
    public record DisburseRequest(
            @NotBlank String method,
            @Size(max = 100) String reference,
            @NotNull LocalDate disbursedOn,
            @Size(max = 1000) String notes
    ) {}

    /** Payroll ticks installments off after the payroll run. Notes required to skip or waive. */
    public record InstallmentActionRequest(
            @NotEmpty @Size(max = 500) List<Long> installmentIds,
            @Size(max = 500) String notes
    ) {}

    /** Read and write shape of one entity's rules. {@code paysId} comes from the path on write. */
    public record PolicyDto(
            Long paysId,
            @NotBlank @Size(min = 3, max = 3) String currency,
            @NotNull @Min(1) @Max(24) Integer maxInstallments,
            @NotNull @Min(0) Integer minSeniorityMonths,
            @NotNull Boolean isActive,
            /** Above it, a director validates first. Null = no director step. */
            @DecimalMin("0.001") @Digits(integer = 12, fraction = 3) BigDecimal directorThreshold,
            /** Null on write = keep / default true. */
            Boolean requireSignedContract,
            OffsetDateTime updatedAt
    ) {}

    // ── Responses ───────────────────────────────────────────────────────────

    /**
     * May the caller ask, over how long. {@code blockers} are stable codes the frontend
     * translates — the same checks the submit runs:
     * NO_POLICY, NO_PROFILE, NOT_ACTIVE, SENIORITY, OPEN_ADVANCE, OFFBOARDING.
     */
    public record Eligibility(
            boolean eligible,
            List<String> blockers,
            String currency,
            Integer maxInstallments,
            Integer minSeniorityMonths,
            /** The current payroll month in the entity's zone. */
            String earliestFirstMonth
    ) {}

    public record InstallmentDto(
            Long id,
            Integer seq,
            String dueMonth,
            BigDecimal amount,
            InstallmentStatus status,
            OffsetDateTime processedAt,
            String notes
    ) {}

    public record HistoryEntryDto(
            String fromStatus,
            String toStatus,
            String actorName,
            String notes,
            OffsetDateTime createdAt
    ) {}

    public record AdvanceDto(
            Long id,
            Long paysId,
            Long employeeUserId,
            String employeeName,
            String currency,
            /** Asked until approval, granted from then on. */
            BigDecimal amount,
            Integer installments,
            /** Null until the DAF sets it. */
            String firstDeductionMonth,
            /** Last month of the schedule, null without one. */
            String lastDeductionMonth,
            /** amount / installments, rounded down to the millime — the last month takes the rest. */
            BigDecimal monthlyAmount,
            BigDecimal requestedAmount,
            Integer requestedInstallments,
            LocalDate requestedAvailabilityDate,
            LocalDate effectiveDate,
            String reason,
            SalaryAdvanceStatus status,
            /** The country threshold the request was routed against; null = none. */
            BigDecimal directorThreshold,
            boolean requireSignedContract,
            String directorDecidedByName,
            OffsetDateTime directorDecidedAt,
            String directorNotes,
            String financeDecidedByName,
            OffsetDateTime financeDecidedAt,
            String financeNotes,
            ContractStatus contractStatus,
            boolean contractAvailable,
            OffsetDateTime contractGeneratedAt,
            boolean signedContractAvailable,
            String signedContractUploadedByName,
            OffsetDateTime signedContractUploadedAt,
            String disbursementMethod,
            String disbursementReference,
            LocalDate disbursedOn,
            String disbursedByName,
            BigDecimal outstandingAmount,
            OffsetDateTime repaidAt,
            OffsetDateTime cancelledAt,
            String cancellationReason,
            OffsetDateTime createdAt,
            List<InstallmentDto> schedule,
            List<HistoryEntryDto> history
    ) {}

    /** One line of the monthly list for the payroll software; the matricule is its key. */
    public record DeductionRowDto(
            Long installmentId,
            Long salaryAdvanceId,
            Long employeeUserId,
            String employeeName,
            String payrollMatricule,
            Long paysId,
            String month,
            Integer seq,
            Integer installmentsTotal,
            BigDecimal amount,
            String currency,
            InstallmentStatus status,
            BigDecimal outstandingAmount
    ) {}

    /** A contract file streamed back from RH. */
    public record ContractFile(byte[] content, String fileName, String contentType) {}
}
