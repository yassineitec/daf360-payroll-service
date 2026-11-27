package com.daf360.payroll.modules.payroll.variable;

import com.daf360.payroll.modules.employeeconfig.entity.EmployeePayrollBonus;
import com.daf360.payroll.modules.employeeconfig.repository.EmployeePayrollBonusRepository;
import com.daf360.payroll.modules.payroll.calculator.ExecutionContext;
import com.daf360.payroll.modules.payroll.leavesettings.PayrollLeaveSettingsService;
import com.daf360.payroll.modules.ref.service.FxEntryConversionService;
import com.daf360.payroll.modules.salaryadvance.entity.SalaryAdvanceInstallment;
import com.daf360.payroll.modules.salaryadvance.repository.SalaryAdvanceInstallmentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gathers the month's « éléments variables » of one employee and turns them into engine
 * variables, so the country's rubriques (V41: HS_WEEKEND, PRIME_EXCEP, ABS_NON_PAYEE,
 * ABS_ENTREE_SORTIE, AUTORISATION_DED, AVANCE_SALAIRE) can price them with plain formulas.
 *
 * <h3>Pay period</h3>
 * Overtime, absences, autorisations, entry/exit and the working days of the daily rate are
 * counted inside the country's pay period (V42, {@link PayrollLeaveSettingsService#periodFor}:
 * e.g. November = 21/10 → 20/11; calendar month by default). The base salary, the bonuses and
 * the advance instalments stay on the payroll month itself.
 *
 * <h3>Sources</h3>
 * <ul>
 *   <li>RH (one internal call, for the period): country of WORK — its weekend days, holidays and
 *       working days —, régime hours, entry/exit, approved absences, approved autorisation
 *       minutes with the monthly quota, congé / maladie balances at the start and end.</li>
 *   <li>Timesheet (one internal call): APPROVED hours per day of the period.</li>
 *   <li>Payroll itself: which leave types the country deducts (V42), the month's one-off
 *       bonuses, the advance instalments of the month.</li>
 * </ul>
 *
 * <h3>Rules</h3>
 * <ul>
 *   <li><b>Weekend overtime</b> — hours worked on a weekend day of the country of work, at most
 *       {@code plafond_hs_jour} (default 8) per day; paid hourlyRate × (1 + majoration/100), the
 *       majoration being the active parameter set's {@code taux_majoration_hs_weekend} (%).
 *       Days outside the employment window are ignored.</li>
 *   <li><b>Unpaid absences</b> — days of the types the country deducts × dailyRate; working days
 *       outside the employment window (entry/exit) are withheld the same way.</li>
 *   <li><b>Autorisations</b> — approved minutes above the country's monthly quota × hourlyRate;
 *       within the quota (or no quota) they are paid.</li>
 *   <li><b>Bonuses</b> and <b>advance instalments</b> of the month, converted to the working
 *       currency when entered in another one. Advances only lower the NET (strate-5 RETENUE).</li>
 * </ul>
 * hourlyRate = monthly gross ÷ (hours per week × 52 ÷ 12); dailyRate = monthly gross ÷ working
 * days of the period. Both use the configured monthly gross, before any prorata.
 */
@Slf4j
@Service
public class PayrollVariableInputsService {

    /** Majoration of weekend overtime, in % — a parameter of the country's active parameter set. */
    public static final String PARAM_MAJORATION = "taux_majoration_hs_weekend";
    /** Most overtime hours one weekend day can count. */
    public static final String PARAM_DAILY_CAP = "plafond_hs_jour";
    static final BigDecimal DEFAULT_DAILY_CAP = BigDecimal.valueOf(8);
    static final BigDecimal FALLBACK_HOURS_PER_WEEK = BigDecimal.valueOf(40);
    static final long FALLBACK_WORKING_DAYS = 22;

    /** Engine variables this service defines — every one always set, 0 when not applicable. */
    public static final List<String> VARIABLES = List.of(
            "SALAIRE_MENSUEL", "HEURES_MENSUELLES", "TAUX_HORAIRE", "TAUX_JOURNALIER",
            "TAUX_MAJORATION_HS", "HS_WEEKEND_HEURES", "ABS_NON_PAYEES_JOURS", "JOURS_NON_EMPLOYES",
            "AUTORISATION_HEURES_DEDUITES", "PRIMES_MOIS", "AVANCES_MOIS");

    private static final Set<DayOfWeek> DEFAULT_WEEKEND = EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);

    private final HrPayrollInputsClient hrClient;
    private final TimesheetHoursClient timesheetClient;
    private final EmployeePayrollBonusRepository bonusRepo;
    private final SalaryAdvanceInstallmentRepository installmentRepo;
    private final FxEntryConversionService fx;
    private final PayrollLeaveSettingsService leaveSettings;

    public PayrollVariableInputsService(HrPayrollInputsClient hrClient,
                                        TimesheetHoursClient timesheetClient,
                                        EmployeePayrollBonusRepository bonusRepo,
                                        SalaryAdvanceInstallmentRepository installmentRepo,
                                        FxEntryConversionService fx,
                                        PayrollLeaveSettingsService leaveSettings) {
        this.hrClient = hrClient;
        this.timesheetClient = timesheetClient;
        this.bonusRepo = bonusRepo;
        this.installmentRepo = installmentRepo;
        this.fx = fx;
        this.leaveSettings = leaveSettings;
    }

    /**
     * @param monthlyGross the employee's configured monthly gross, working currency
     * @param params       the active parameter set's parameters (majoration, daily cap)
     * @param currency     the country's working currency
     */
    public PayrollVariableInputs gather(Long employeeId, Long paysId, int year, int month,
                                        BigDecimal monthlyGross, Map<String, Object> params, String currency) {
        List<String> warnings = new ArrayList<>();
        PayrollLeaveSettingsService.PayPeriod period = leaveSettings.periodFor(paysId, year, month);
        LocalDate from = period.from();
        LocalDate to = period.to();
        BigDecimal gross = monthlyGross != null ? monthlyGross : BigDecimal.ZERO;

        // ── RH ──────────────────────────────────────────────────────────────────────────────
        HrPayrollInputsClient.HrPayrollInputs hr = hrClient.fetch(employeeId, year, month, from, to).orElse(null);
        if (hr == null) {
            warnings.add("RH injoignable ou fiche absente : absences, autorisations, week-end et "
                    + "entrée/sortie non pris en compte.");
        } else if (hr.paysId() != null && !hr.paysId().equals(paysId)) {
            warnings.add("Le pays de travail de la fiche RH (" + hr.paysId() + ") diffère du pays du calcul ("
                    + paysId + ") : le week-end et les jours fériés sont ceux du pays de travail.");
        }

        Set<DayOfWeek> weekend = weekendOf(hr, warnings);
        long workingDaysInMonth = hr != null && hr.workingDaysInPeriod() > 0 ? hr.workingDaysInPeriod() : FALLBACK_WORKING_DAYS;
        long workingDaysEmployed = hr != null ? Math.min(hr.workingDaysEmployed(), workingDaysInMonth) : workingDaysInMonth;
        long nonEmployedDays = Math.max(0, workingDaysInMonth - workingDaysEmployed);
        LocalDate hireDate = hr != null ? hr.hireDate() : null;
        LocalDate exitDate = hr != null ? hr.exitDate() : null;

        BigDecimal hoursPerWeek = hr != null && hr.hoursPerWeek() != null && hr.hoursPerWeek().signum() > 0
                ? hr.hoursPerWeek() : null;
        if (hoursPerWeek == null) {
            warnings.add("Régime horaire introuvable : taux horaire calculé sur " + FALLBACK_HOURS_PER_WEEK + " h/semaine.");
            hoursPerWeek = FALLBACK_HOURS_PER_WEEK;
        }
        BigDecimal monthlyHours = hoursPerWeek.multiply(BigDecimal.valueOf(52))
                .divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);
        BigDecimal hourlyRate = gross.divide(monthlyHours, 6, RoundingMode.HALF_UP);
        BigDecimal dailyRate = gross.divide(BigDecimal.valueOf(workingDaysInMonth), 6, RoundingMode.HALF_UP);

        // ── Overtime (timesheet, weekend days of the country of work) ────────────────────────
        BigDecimal majoration = decimalParam(params, PARAM_MAJORATION);
        if (majoration == null) {
            warnings.add("Majoration des heures supplémentaires non configurée (paramètre "
                    + PARAM_MAJORATION + " du jeu de paramètres) : heures payées sans majoration.");
        }
        BigDecimal cap = decimalParam(params, PARAM_DAILY_CAP);
        if (cap == null || cap.signum() <= 0) cap = DEFAULT_DAILY_CAP;

        List<PayrollVariableInputs.OvertimeDay> otDays = new ArrayList<>();
        BigDecimal paidHours = BigDecimal.ZERO;
        BigDecimal cappedOut = BigDecimal.ZERO;
        boolean timesheetRead = false;
        if (!timesheetClient.isConfigured()) {
            warnings.add("Timesheet non configurée (TIMESHEET_API_BASE_URL) : heures supplémentaires non lues.");
        } else {
            var daily = timesheetClient.approvedDailyHours(employeeId, from, to);
            if (daily.isEmpty()) {
                warnings.add("Timesheet injoignable : heures supplémentaires non lues.");
            } else {
                timesheetRead = true;
                for (TimesheetHoursClient.DailyHours d : daily.get()) {
                    if (d.date() == null || d.hours() == null || d.hours().signum() <= 0) continue;
                    if (!weekend.contains(d.date().getDayOfWeek())) continue;
                    if (hireDate != null && d.date().isBefore(hireDate)) continue;
                    if (exitDate != null && d.date().isAfter(exitDate)) continue;
                    BigDecimal paid = d.hours().min(cap);
                    boolean capped = d.hours().compareTo(cap) > 0;
                    otDays.add(new PayrollVariableInputs.OvertimeDay(d.date(), d.date().getDayOfWeek().name(),
                            d.hours(), paid, capped));
                    paidHours = paidHours.add(paid);
                    if (capped) cappedOut = cappedOut.add(d.hours().subtract(cap));
                }
            }
        }
        BigDecimal majorationFactor = BigDecimal.ONE.add(
                (majoration != null ? majoration : BigDecimal.ZERO).divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));
        BigDecimal otAmount = paidHours.multiply(hourlyRate).multiply(majorationFactor).setScale(2, RoundingMode.HALF_UP);
        var overtime = new PayrollVariableInputs.Overtime(majoration, cap, otDays, paidHours, cappedOut, otAmount);

        // ── Absences ────────────────────────────────────────────────────────────────────────
        List<PayrollVariableInputs.AbsenceItem> absItems = new ArrayList<>();
        BigDecimal unpaidDays = BigDecimal.ZERO;
        BigDecimal paidDays = BigDecimal.ZERO;
        Set<String> deductible = leaveSettings.deductibleCodes(paysId);
        if (hr != null && hr.absences() != null) {
            for (HrPayrollInputsClient.AbsenceLine a : hr.absences()) {
                BigDecimal days = a.days() != null ? a.days() : BigDecimal.ZERO;
                boolean deducts = a.typeCode() != null && deductible.contains(a.typeCode().trim().toUpperCase());
                absItems.add(new PayrollVariableInputs.AbsenceItem(a.typeCode(), a.labelFr(), a.category(),
                        a.from(), a.to(), days, deducts));
                if (deducts) unpaidDays = unpaidDays.add(days); else paidDays = paidDays.add(days);
            }
        }
        BigDecimal absAmount = unpaidDays.multiply(dailyRate).setScale(2, RoundingMode.HALF_UP);
        var absences = new PayrollVariableInputs.Absences(absItems, unpaidDays, paidDays, absAmount);

        // ── Autorisations ───────────────────────────────────────────────────────────────────
        int approvedMinutes = hr != null ? hr.autorisationMinutes() : 0;
        Integer quota = hr != null ? hr.autorisationMonthlyQuotaMinutes() : null;
        int deductedMinutes = quota == null ? 0 : Math.max(0, approvedMinutes - quota);
        BigDecimal deductedHours = BigDecimal.valueOf(deductedMinutes).divide(BigDecimal.valueOf(60), 4, RoundingMode.HALF_UP);
        BigDecimal autAmount = deductedHours.multiply(hourlyRate).setScale(2, RoundingMode.HALF_UP);
        var autorisations = new PayrollVariableInputs.Autorisations(approvedMinutes, quota, deductedMinutes,
                deductedHours.setScale(2, RoundingMode.HALF_UP), autAmount);

        // ── Bonuses of the month ────────────────────────────────────────────────────────────
        List<PayrollVariableInputs.Bonus> bonuses = new ArrayList<>();
        BigDecimal bonusesTotal = BigDecimal.ZERO;
        for (EmployeePayrollBonus b : bonusRepo.findByProfileUserIdAndPeriodYearAndPeriodMonthOrderByIdAsc(employeeId, year, month)) {
            BigDecimal converted = toWorkingCurrency(b.getAmount(), b.getCurrency(), currency, paysId,
                    "la prime « " + b.getLabel() + " »", warnings);
            bonuses.add(new PayrollVariableInputs.Bonus(b.getId(), b.getLabel(), b.getAmount(), b.getCurrency(), converted));
            bonusesTotal = bonusesTotal.add(converted);
        }

        // ── Salary-advance instalments of the month ─────────────────────────────────────────
        List<PayrollVariableInputs.Advance> advances = new ArrayList<>();
        BigDecimal advancesTotal = BigDecimal.ZERO;
        for (Object[] row : installmentRepo.findWithheldFromPay(employeeId, year, month)) {
            SalaryAdvanceInstallment i = (SalaryAdvanceInstallment) row[0];
            String advCurrency = (String) row[1];
            BigDecimal converted = toWorkingCurrency(i.getAmount(), advCurrency, currency, paysId,
                    "l'échéance d'avance n° " + i.getSeq(), warnings);
            advances.add(new PayrollVariableInputs.Advance(i.getSalaryAdvanceId(), i.getSeq(), i.getAmount(),
                    advCurrency, converted, i.getStatus().name()));
            advancesTotal = advancesTotal.add(converted);
        }

        // ── Balances (information: congé / maladie before and after the period) ─────────────
        List<PayrollVariableInputs.Balance> balances = new ArrayList<>();
        if (hr != null && hr.balances() != null) {
            for (HrPayrollInputsClient.BalanceLine b : hr.balances()) {
                balances.add(new PayrollVariableInputs.Balance(b.field(), b.atPeriodStart(), b.atPeriodEnd(),
                        b.takenInPeriod()));
            }
        }

        return new PayrollVariableInputs(
                year, month, currency,
                from, to,
                hr != null ? hr.paysId() : null,
                weekend.stream().sorted().map(DayOfWeek::name).toList(),
                workingDaysInMonth, hireDate, exitDate, workingDaysEmployed, nonEmployedDays,
                gross, hr != null ? hr.regimeCode() : null, hoursPerWeek, monthlyHours,
                hourlyRate.setScale(4, RoundingMode.HALF_UP), dailyRate.setScale(4, RoundingMode.HALF_UP),
                overtime, absences, autorisations,
                bonuses, bonusesTotal.setScale(2, RoundingMode.HALF_UP),
                advances, advancesTotal.setScale(2, RoundingMode.HALF_UP),
                balances,
                hr != null, timesheetRead, warnings);
    }

    /** Puts the engine variables the V41 rubriques read into the context. */
    public void applyTo(ExecutionContext ctx, PayrollVariableInputs in) {
        ctx.putVariable("SALAIRE_MENSUEL", in.monthlyGross());
        ctx.putVariable("HEURES_MENSUELLES", in.monthlyHours());
        ctx.putVariable("TAUX_HORAIRE", in.hourlyRate());
        ctx.putVariable("TAUX_JOURNALIER", in.dailyRate());
        ctx.putVariable("TAUX_MAJORATION_HS", in.overtime().majorationPct() != null ? in.overtime().majorationPct() : BigDecimal.ZERO);
        ctx.putVariable("HS_WEEKEND_HEURES", in.overtime().paidHours());
        ctx.putVariable("ABS_NON_PAYEES_JOURS", in.absences().unpaidDays());
        ctx.putVariable("JOURS_NON_EMPLOYES", BigDecimal.valueOf(in.nonEmployedDays()));
        ctx.putVariable("AUTORISATION_HEURES_DEDUITES", in.autorisations().deductedHours());
        ctx.putVariable("PRIMES_MOIS", in.bonusesTotal());
        ctx.putVariable("AVANCES_MOIS", in.advancesTotal());
    }

    /**
     * True when a rubrique prices a variable element (its formula or assiette reads one of
     * {@link #VARIABLES}). Such a line is left off the payslip when it comes to 0 — a month
     * without overtime should not print « Heures supplémentaires 0,00 ».
     */
    public static boolean readsVariableElement(String formula, String assietteCode) {
        if (assietteCode != null && VARIABLES.contains(assietteCode)) return true;
        if (formula == null) return false;
        for (String v : VARIABLES) {
            if (java.util.regex.Pattern.compile("\\b" + v + "\\b").matcher(formula).find()) return true;
        }
        return false;
    }

    private Set<DayOfWeek> weekendOf(HrPayrollInputsClient.HrPayrollInputs hr, List<String> warnings) {
        if (hr == null || hr.weekendDays() == null || hr.weekendDays().isEmpty()) return DEFAULT_WEEKEND;
        Set<DayOfWeek> out = EnumSet.noneOf(DayOfWeek.class);
        for (String d : hr.weekendDays()) {
            try { out.add(DayOfWeek.valueOf(d.trim().toUpperCase())); }
            catch (Exception e) { warnings.add("Jour de week-end inconnu ignoré : " + d); }
        }
        return out.isEmpty() ? DEFAULT_WEEKEND : out;
    }

    private BigDecimal toWorkingCurrency(BigDecimal amount, String from, String to, Long paysId,
                                         String what, List<String> warnings) {
        if (amount == null) return BigDecimal.ZERO;
        if (from == null || from.isBlank() || to == null || to.isBlank() || from.equalsIgnoreCase(to)) return amount;
        try {
            return fx.convert(amount, from, to, paysId).setScale(3, RoundingMode.HALF_UP);
        } catch (Exception e) {
            warnings.add("Conversion " + from + " → " + to + " impossible pour " + what
                    + " : montant repris tel quel.");
            return amount;
        }
    }

    private static BigDecimal decimalParam(Map<String, Object> params, String key) {
        if (params == null) return null;
        Object raw = params.get(key);
        if (raw == null) return null;
        try {
            return raw instanceof BigDecimal b ? b : new BigDecimal(raw.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
