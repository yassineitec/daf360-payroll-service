package com.daf360.payroll.modules.payroll.variable;

import com.daf360.payroll.modules.employeeconfig.repository.EmployeePayrollBonusRepository;
import com.daf360.payroll.modules.payroll.calculator.ExecutionContext;
import com.daf360.payroll.modules.payroll.dto.RubriqueResultItem;
import com.daf360.payroll.modules.payroll.leavesettings.OvertimeSettingsService;
import com.daf360.payroll.modules.payroll.leavesettings.PayrollLeaveSettingsService;
import com.daf360.payroll.modules.payroll.orchestrator.SystemVariableAggregator;
import com.daf360.payroll.modules.ref.service.FxEntryConversionService;
import com.daf360.payroll.modules.salaryadvance.repository.SalaryAdvanceInstallmentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The month's variable elements: weekend overtime from approved timesheet hours (country of
 * work's weekend, 8 h per day at most), unpaid absences by type flag, autorisations above the
 * monthly quota, and the RETENUE sign handling of the aggregator.
 */
@ExtendWith(MockitoExtension.class)
class PayrollVariableInputsServiceTest {

    @Mock private HrPayrollInputsClient hrClient;
    @Mock private TimesheetHoursClient timesheetClient;
    @Mock private EmployeePayrollBonusRepository bonusRepo;
    @Mock private SalaryAdvanceInstallmentRepository installmentRepo;
    @Mock private FxEntryConversionService fx;
    @Mock private PayrollLeaveSettingsService leaveSettings;
    @Mock private OvertimeSettingsService overtimeSettings;

    private PayrollVariableInputsService service() {
        return new PayrollVariableInputsService(hrClient, timesheetClient, bonusRepo, installmentRepo, fx,
                leaveSettings, overtimeSettings);
    }

    private static OvertimeSettingsService.OvertimeRules rules(Map<DayOfWeek, BigDecimal> perDay, String defaultPct,
                                                               String cap, OvertimeSettingsService.CapSource source) {
        return new OvertimeSettingsService.OvertimeRules(perDay, defaultPct == null ? null : new BigDecimal(defaultPct),
                new BigDecimal(cap), source);
    }

    private static HrPayrollInputsClient.HrPayrollInputs hr48(LocalDate from, LocalDate to,
                                                              List<HrPayrollInputsClient.AbsenceLine> absences,
                                                              List<HrPayrollInputsClient.BalanceLine> balances) {
        return new HrPayrollInputsClient.HrPayrollInputs(
                1L, 178L, 2026, 11, from, to, List.of("SATURDAY", "SUNDAY"), List.of(), 20,
                LocalDate.of(2020, 1, 1), null, 20, "R48", BigDecimal.valueOf(48), 6,
                absences, 300, 240, balances, 3L, "Collaborateur");
    }

    private static final LocalDate SAT = LocalDate.of(2026, 11, 7);
    private static final LocalDate SUN = LocalDate.of(2026, 11, 8);
    private static final LocalDate MON = LocalDate.of(2026, 11, 9);
    private static final LocalDate FROM = LocalDate.of(2026, 10, 21);
    private static final LocalDate TO = LocalDate.of(2026, 11, 20);

    @Test
    void weekendOvertimeIsCappedPerDayAndDeductionsFollowCountryRulesAndQuota() {
        // Pay period 21/10 → 20/11. 48 h/week → 208 h/month; gross 2080 → 10 / hour. 20 working days → 104 / day.
        when(leaveSettings.periodFor(178L, 2026, 11))
                .thenReturn(new PayrollLeaveSettingsService.PayPeriod(FROM, TO, 21, 20));
        when(leaveSettings.deductibleCodes(178L)).thenReturn(Set.of("SANS_SOLDE"));
        when(hrClient.fetch(1L, 2026, 11, FROM, TO)).thenReturn(Optional.of(hr48(FROM, TO,
                List.of(
                        new HrPayrollInputsClient.AbsenceLine("SANS_SOLDE", "Congé sans solde", "Unpaid", "MULTIPLE_DAYS",
                                LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 3), BigDecimal.valueOf(2), null),
                        new HrPayrollInputsClient.AbsenceLine("CONGE", "Congé", "Leave", "FULL_DAY",
                                LocalDate.of(2026, 11, 4), LocalDate.of(2026, 11, 4), BigDecimal.ONE, "CONGE")),
                List.of(new HrPayrollInputsClient.BalanceLine("CONGE", BigDecimal.valueOf(15), BigDecimal.valueOf(14),
                        BigDecimal.ONE, BigDecimal.valueOf(12))))));
        Map<String, Object> params = Map.of(PayrollVariableInputsService.PARAM_MAJORATION, 75);
        when(overtimeSettings.rulesFor(178L, 3L, 1L, params))
                .thenReturn(rules(Map.of(), "75", "8", OvertimeSettingsService.CapSource.DEFAULT));
        when(timesheetClient.isConfigured()).thenReturn(true);
        when(timesheetClient.approvedDailyHours(any(), any(), any())).thenReturn(Optional.of(List.of(
                new TimesheetHoursClient.DailyHours(SAT, BigDecimal.valueOf(10)),   // capped to 8
                new TimesheetHoursClient.DailyHours(SUN, BigDecimal.valueOf(3)),
                new TimesheetHoursClient.DailyHours(MON, BigDecimal.valueOf(9))))); // weekday: not overtime

        PayrollVariableInputs in = service().gather(1L, 178L, 2026, 11, BigDecimal.valueOf(2080), params, "TND");

        verify(timesheetClient).approvedDailyHours(1L, FROM, TO);
        assertThat(in.periodFrom()).isEqualTo(FROM);
        assertThat(in.periodTo()).isEqualTo(TO);
        assertThat(in.balances()).singleElement().satisfies(b -> {
            assertThat(b.atPeriodStart()).isEqualByComparingTo("15");
            assertThat(b.atPeriodEnd()).isEqualByComparingTo("14");
        });
        assertThat(in.absences().items()).filteredOn(PayrollVariableInputs.AbsenceItem::deductsSalary)
                .extracting(PayrollVariableInputs.AbsenceItem::typeCode).containsExactly("SANS_SOLDE");
        assertThat(in.hourlyRate()).isEqualByComparingTo("10");
        assertThat(in.dailyRate()).isEqualByComparingTo("104");
        assertThat(in.overtime().days()).hasSize(2);
        assertThat(in.overtime().paidHours()).isEqualByComparingTo("11");
        assertThat(in.overtime().cappedOutHours()).isEqualByComparingTo("2");
        assertThat(in.overtime().amount()).isEqualByComparingTo("192.50");   // 11 × 10 × 1.75
        assertThat(in.absences().unpaidDays()).isEqualByComparingTo("2");
        assertThat(in.absences().paidDays()).isEqualByComparingTo("1");
        assertThat(in.absences().amount()).isEqualByComparingTo("208.00");
        assertThat(in.autorisations().deductedMinutes()).isEqualTo(60);
        assertThat(in.autorisations().amount()).isEqualByComparingTo("10.00");
        assertThat(in.nonEmployedDays()).isZero();
        assertThat(in.warnings()).isEmpty();
    }

    @Test
    void missingSourcesAreReportedNotInvented() {
        LocalDate from = LocalDate.of(2026, 11, 1), to = LocalDate.of(2026, 11, 30);
        when(leaveSettings.periodFor(178L, 2026, 11))
                .thenReturn(new PayrollLeaveSettingsService.PayPeriod(from, to, 1, null));
        when(hrClient.fetch(1L, 2026, 11, from, to)).thenReturn(Optional.empty());
        when(overtimeSettings.rulesFor(178L, null, 1L, Map.of()))
                .thenReturn(rules(Map.of(), null, "8", OvertimeSettingsService.CapSource.DEFAULT));
        when(timesheetClient.isConfigured()).thenReturn(false);

        PayrollVariableInputs in = service().gather(1L, 178L, 2026, 11, BigDecimal.valueOf(2080), Map.of(), "TND");

        assertThat(in.overtime().majorationPct()).isNull();
        assertThat(in.hrRead()).isFalse();
        assertThat(in.timesheetRead()).isFalse();
        // RH, régime, timesheet — no majoration warning without overtime hours to pay.
        assertThat(in.warnings()).hasSize(3);
    }

    @Test
    void eachWeekendDayIsPricedAtItsOwnMajorationWithTheEmployeeCap() {
        LocalDate from = LocalDate.of(2026, 11, 1), to = LocalDate.of(2026, 11, 30);
        when(leaveSettings.periodFor(178L, 2026, 11))
                .thenReturn(new PayrollLeaveSettingsService.PayPeriod(from, to, 1, null));
        when(hrClient.fetch(1L, 2026, 11, from, to)).thenReturn(Optional.of(hr48(from, to, List.of(), List.of())));
        // Saturday 50 %, Sunday 100 %, employee cap 6 h.
        when(overtimeSettings.rulesFor(178L, 3L, 1L, Map.of())).thenReturn(rules(
                Map.of(DayOfWeek.SATURDAY, new BigDecimal("50"), DayOfWeek.SUNDAY, new BigDecimal("100")),
                "75", "6", OvertimeSettingsService.CapSource.EMPLOYEE));
        when(timesheetClient.isConfigured()).thenReturn(true);
        when(timesheetClient.approvedDailyHours(any(), any(), any())).thenReturn(Optional.of(List.of(
                new TimesheetHoursClient.DailyHours(SAT, BigDecimal.valueOf(10)),   // capped to 6
                new TimesheetHoursClient.DailyHours(SUN, BigDecimal.valueOf(3)))));

        PayrollVariableInputs in = service().gather(1L, 178L, 2026, 11, BigDecimal.valueOf(2080), Map.of(), "TND");

        // 6 × 10 × 1.5 = 90 ; 3 × 10 × 2 = 60 → 150. Weighted average: 150 / (9 × 10) − 1 = 66.67 %.
        assertThat(in.overtime().dailyCapHours()).isEqualByComparingTo("6");
        assertThat(in.overtime().capSource()).isEqualTo("EMPLOYEE");
        assertThat(in.overtime().paidHours()).isEqualByComparingTo("9");
        assertThat(in.overtime().cappedOutHours()).isEqualByComparingTo("4");
        assertThat(in.overtime().amount()).isEqualByComparingTo("150.00");
        assertThat(in.overtime().days()).extracting(PayrollVariableInputs.OvertimeDay::majorationPct)
                .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactly(new BigDecimal("50"), new BigDecimal("100"));
        assertThat(in.overtime().majorationPct()).isEqualByComparingTo("66.6667");
        assertThat(in.warnings()).isEmpty();
    }

    @Test
    void retenueLowersItsStrateAndStrate5RetenueIsWithheldFromTheNet() {
        ExecutionContext ctx = new ExecutionContext(1L, 7L, 178L, 2026, 11, "CDI",
                BigDecimal.valueOf(22), Map.of());
        List<RubriqueResultItem> items = List.of(
                item("SALAIRE_BASE", 1, "GAIN", "2080"),
                item("HS_WEEKEND", 1, "GAIN", "192.50"),
                item("ABS_NON_PAYEE", 1, "RETENUE", "208"),
                item("IRPP", 5, "TAXE", "100"),
                item("AVANCE_SALAIRE", 5, "RETENUE", "150"));

        new SystemVariableAggregator().aggregate(items, ctx);

        assertThat(ctx.getVariable("BRUT")).isEqualByComparingTo("2064.50");
        assertThat(ctx.getVariable("STRATE_5")).isEqualByComparingTo("100");
        assertThat(ctx.getVariable(SystemVariableAggregator.RETENUES_NET)).isEqualByComparingTo("150");
    }

    @Test
    void variableElementRubriquesAreRecognisedByTheirFormula() {
        assertThat(PayrollVariableInputsService.readsVariableElement(
                "HS_WEEKEND_HEURES * TAUX_HORAIRE * (1.0 + TAUX_MAJORATION_HS / 100.0)", null)).isTrue();
        assertThat(PayrollVariableInputsService.readsVariableElement("AVANCES_MOIS", null)).isTrue();
        assertThat(PayrollVariableInputsService.readsVariableElement("BRUT * 0.0968", "BRUT")).isFalse();
    }

    private static RubriqueResultItem item(String code, int strate, String nature, String amount) {
        return RubriqueResultItem.builder()
                .rubriqueCode(code).strate(strate).nature(nature)
                .amount(new BigDecimal(amount)).build();
    }
}
