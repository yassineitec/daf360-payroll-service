package com.daf360.payroll.modules.payroll.variable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The month's « éléments variables » of one employee, exactly as the run used them — stored as
 * JSON on the payroll result ({@code payroll_results.variable_inputs_json}, V41) so a payslip
 * can always show WHY an amount is what it is, and returned by the pop-up preview
 * ({@code GET /api/payroll/engine/variable-inputs}).
 *
 * Every amount is in the country's working currency. {@code warnings} are French sentences
 * shown on the payslip (a source that could not be read, a missing parameter…): a gap is
 * reported, never silently priced at zero.
 */
public record PayrollVariableInputs(
        int periodYear,
        int periodMonth,
        String currency,

        // ── Pay period of the country (V42: e.g. November = 21/10 → 20/11; calendar month by default).
        //    Overtime, absences, autorisations and entry/exit are counted inside it. ──
        LocalDate periodFrom,
        LocalDate periodTo,

        // ── Calendar of the country of WORK (RH employee_profiles.pays_id) ──
        Long workPaysId,
        List<String> weekendDays,
        /** Working days of the pay period (name kept for the snapshots already stored). */
        long workingDaysInMonth,
        LocalDate hireDate,
        LocalDate exitDate,
        long workingDaysEmployed,
        /** Working days of the period outside the employment window (entry/exit). */
        long nonEmployedDays,

        // ── Rates ──
        BigDecimal monthlyGross,
        String regimeCode,
        BigDecimal hoursPerWeek,
        /** hoursPerWeek × 52 ÷ 12. */
        BigDecimal monthlyHours,
        /** monthlyGross ÷ monthlyHours. */
        BigDecimal hourlyRate,
        /** monthlyGross ÷ workingDaysInMonth. */
        BigDecimal dailyRate,

        Overtime overtime,
        Absences absences,
        Autorisations autorisations,
        List<Bonus> bonuses,
        BigDecimal bonusesTotal,
        List<Advance> advances,
        BigDecimal advancesTotal,
        /** Congé / maladie balances at the start and the end of the period (information only). */
        List<Balance> balances,

        boolean hrRead,
        boolean timesheetRead,
        List<String> warnings
) {

    /** Weekend overtime from APPROVED timesheet hours, capped per day. */
    public record Overtime(
            /**
             * Majoration in % — since V43 the hours-weighted average of the days' own majorations
             * (each day carries its own in {@link OvertimeDay#majorationPct}). Null = not configured.
             */
            BigDecimal majorationPct,
            BigDecimal dailyCapHours,
            /** Where the cap came from: EMPLOYEE, ROLE, COUNTRY or DEFAULT (V43; null before). */
            String capSource,
            List<OvertimeDay> days,
            /** Hours paid (after the daily cap). */
            BigDecimal paidHours,
            /** Hours worked on weekend days above the daily cap — shown, not paid. */
            BigDecimal cappedOutHours,
            /** paidHours × hourlyRate × (1 + majoration/100). */
            BigDecimal amount
    ) {}

    /** One weekend day; {@code majorationPct} / {@code amount} are absent on snapshots before V43. */
    public record OvertimeDay(LocalDate date, String dayOfWeek, BigDecimal workedHours,
                              BigDecimal paidHours, boolean capped, BigDecimal majorationPct, BigDecimal amount) {}

    public record Absences(
            List<AbsenceItem> items,
            /** Sum of the days of types the country deducts (payroll_leave_deduction_rules, V42). */
            BigDecimal unpaidDays,
            BigDecimal paidDays,
            /** unpaidDays × dailyRate (entry/exit days are the separate nonEmployedDays × dailyRate). */
            BigDecimal amount
    ) {}

    public record AbsenceItem(String typeCode, String label, String category, LocalDate from, LocalDate to,
                              BigDecimal days, boolean deductsSalary) {}

    public record Autorisations(
            int approvedMinutes,
            /** Null = no monthly limit for the country. */
            Integer monthlyQuotaMinutes,
            int deductedMinutes,
            BigDecimal deductedHours,
            BigDecimal amount
    ) {}

    public record Bonus(Long id, String label, BigDecimal amount, String currency, BigDecimal amountConverted) {}

    public record Advance(Long advanceId, Integer seq, BigDecimal amount, String currency,
                          BigDecimal amountConverted, String status) {}

    /**
     * One RH balance (CONGE / MALADIE) rebuilt for the period: RH keeps only the current figure,
     * so start/end come from the approved leaves around the period. Null = no balance recorded.
     */
    public record Balance(String field, BigDecimal atPeriodStart, BigDecimal atPeriodEnd,
                          BigDecimal takenInPeriod) {}
}
