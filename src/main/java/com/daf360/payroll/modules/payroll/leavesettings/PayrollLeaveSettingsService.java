package com.daf360.payroll.modules.payroll.leavesettings;

import com.daf360.payroll.modules.payroll.variable.HrPayrollInputsClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Per-country payroll rules for absences and the pay period (V42), edited in Paie ›
 * Administration › « Absences et période de paie » and read by the payroll run.
 *
 * <ul>
 *   <li><b>Pay period</b> — start 1 = calendar month; otherwise month M runs from
 *       {@code startDay} of M−1 to {@code endDay} of M, end = start − 1 (e.g. 21 → 20: November =
 *       21/10 → 20/11, December = 21/11 → 20/12). Only the variable elements follow it.</li>
 *   <li><b>Deductible leaves</b> — the RH leave catalogue (global) with, per country, whether an
 *       approved day of each type is withheld from the salary. No rule = paid.</li>
 * </ul>
 */
@Service
public class PayrollLeaveSettingsService {

    private final PayrollPeriodSettingRepository periodRepo;
    private final PayrollLeaveDeductionRuleRepository ruleRepo;
    private final HrPayrollInputsClient hrClient;

    public PayrollLeaveSettingsService(PayrollPeriodSettingRepository periodRepo,
                                       PayrollLeaveDeductionRuleRepository ruleRepo,
                                       HrPayrollInputsClient hrClient) {
        this.periodRepo = periodRepo;
        this.ruleRepo = ruleRepo;
        this.hrClient = hrClient;
    }

    // ── Read by the payroll run ─────────────────────────────────────────────────────────────

    /** The pay period of {@code year}/{@code month} in this country. */
    @Transactional(readOnly = true)
    public PayPeriod periodFor(Long paysId, int year, int month) {
        PayrollPeriodSetting s = paysId != null ? periodRepo.findById(paysId).orElse(null) : null;
        int start = s != null && s.getPeriodStartDay() != null ? s.getPeriodStartDay() : 1;
        return period(year, month, start);
    }

    /** RH leave-type codes withheld from the salary in this country. */
    @Transactional(readOnly = true)
    public Set<String> deductibleCodes(Long paysId) {
        if (paysId == null) return Set.of();
        return ruleRepo.findByPaysId(paysId).stream()
                .filter(r -> Boolean.TRUE.equals(r.getDeductsSalary()))
                .map(r -> r.getAbsenceTypeCode().trim().toUpperCase())
                .collect(Collectors.toSet());
    }

    static PayPeriod period(int year, int month, int startDay) {
        YearMonth ym = YearMonth.of(year, month);
        if (startDay <= 1) return new PayPeriod(ym.atDay(1), ym.atEndOfMonth(), 1, null);
        int endDay = startDay - 1;
        return new PayPeriod(ym.minusMonths(1).atDay(startDay), ym.atDay(endDay), startDay, endDay);
    }

    // ── Admin screen ────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public SettingsView view(Long paysId) {
        requirePays(paysId);
        PayrollPeriodSetting s = periodRepo.findById(paysId).orElse(null);
        int start = s != null ? s.getPeriodStartDay() : 1;
        LocalDate today = LocalDate.now();
        PayPeriod example = period(today.getYear(), today.getMonthValue(), start);

        Map<String, PayrollLeaveDeductionRule> rules = new HashMap<>();
        ruleRepo.findByPaysId(paysId).forEach(r -> rules.put(r.getAbsenceTypeCode().trim().toUpperCase(), r));

        Optional<List<HrPayrollInputsClient.AbsenceTypeItem>> catalogue = hrClient.absenceTypes();
        List<LeaveTypeRule> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (HrPayrollInputsClient.AbsenceTypeItem t : catalogue.orElse(List.of())) {
            String code = t.code().trim().toUpperCase();
            seen.add(code);
            PayrollLeaveDeductionRule r = rules.get(code);
            rows.add(new LeaveTypeRule(t.code(), t.labelFr(), t.labelEn(), t.active(), t.tracksBalance(),
                    t.balanceField(), r != null && Boolean.TRUE.equals(r.getDeductsSalary()), true,
                    r != null ? r.getUpdatedBy() : null, r != null ? r.getUpdatedAt() : null));
        }
        // Rules whose type is no longer in the RH catalogue: shown so they can be switched off.
        rules.forEach((code, r) -> {
            if (!seen.contains(code)) {
                rows.add(new LeaveTypeRule(r.getAbsenceTypeCode(), r.getAbsenceTypeCode(), r.getAbsenceTypeCode(),
                        false, false, null, Boolean.TRUE.equals(r.getDeductsSalary()), false,
                        r.getUpdatedBy(), r.getUpdatedAt()));
            }
        });

        return new SettingsView(paysId, start, start <= 1 ? null : start - 1,
                example.from(), example.to(), catalogue.isPresent(), rows);
    }

    @Transactional
    public SettingsView savePeriod(Long paysId, Integer startDay, Integer endDay, Long caller) {
        requirePays(paysId);
        if (startDay == null || startDay < 1 || startDay > 28) {
            throw new IllegalArgumentException("Le jour de début doit être compris entre 1 et 28.");
        }
        Integer end = null;
        if (startDay > 1) {
            if (endDay == null || endDay != startDay - 1) {
                throw new IllegalArgumentException("Le jour de fin doit être la veille du jour de début ("
                        + (startDay - 1) + ") : sinon deux mois se chevaucheraient ou laisseraient des jours "
                        + "sans paie.");
            }
            end = endDay;
        }
        PayrollPeriodSetting s = periodRepo.findById(paysId).orElseGet(() -> {
            PayrollPeriodSetting n = new PayrollPeriodSetting();
            n.setPaysId(paysId);
            return n;
        });
        s.setPeriodStartDay(startDay);
        s.setPeriodEndDay(end);
        s.setUpdatedBy(caller);
        periodRepo.save(s);
        return view(paysId);
    }

    @Transactional
    public SettingsView saveRule(Long paysId, String code, boolean deductsSalary, Long caller) {
        requirePays(paysId);
        if (code == null || code.isBlank() || code.length() > 64) {
            throw new IllegalArgumentException("Type de congé invalide.");
        }
        String normalized = code.trim();
        // A type RH does not know is refused — unless RH cannot be reached (then the admin is
        // trusted) or it is an existing rule being switched off.
        Optional<List<HrPayrollInputsClient.AbsenceTypeItem>> catalogue = hrClient.absenceTypes();
        Optional<PayrollLeaveDeductionRule> existing = ruleRepo.findByPaysIdAndAbsenceTypeCode(paysId, normalized);
        if (catalogue.isPresent() && existing.isEmpty()
                && catalogue.get().stream().noneMatch(t -> t.code().trim().equalsIgnoreCase(normalized))) {
            throw new IllegalArgumentException("Type de congé inconnu dans RH : " + normalized);
        }
        PayrollLeaveDeductionRule r = existing.orElseGet(() -> {
            PayrollLeaveDeductionRule n = new PayrollLeaveDeductionRule();
            n.setPaysId(paysId);
            n.setAbsenceTypeCode(normalized);
            return n;
        });
        r.setDeductsSalary(deductsSalary);
        r.setUpdatedBy(caller);
        ruleRepo.save(r);
        return view(paysId);
    }

    private static void requirePays(Long paysId) {
        if (paysId == null || paysId <= 0) throw new IllegalArgumentException("Pays obligatoire.");
    }

    // ── Types ───────────────────────────────────────────────────────────────────────────────

    /** From / to inclusive; endDay null = calendar month. */
    public record PayPeriod(LocalDate from, LocalDate to, int startDay, Integer endDay) {
        public boolean calendarMonth() { return startDay <= 1; }
    }

    public record SettingsView(
            Long paysId,
            int periodStartDay,
            Integer periodEndDay,
            /** The current month's period with these settings — shown as an example. */
            LocalDate examplePeriodFrom,
            LocalDate examplePeriodTo,
            /** False = RH could not be reached: only the saved rules are listed. */
            boolean catalogueRead,
            List<LeaveTypeRule> leaveTypes
    ) {}

    public record LeaveTypeRule(
            String code,
            String labelFr,
            String labelEn,
            boolean active,
            boolean tracksBalance,
            String balanceField,
            boolean deductsSalary,
            /** False = the rule's type is not (or no longer) in the RH catalogue. */
            boolean inCatalogue,
            Long updatedBy,
            java.time.OffsetDateTime updatedAt
    ) {}
}
