package com.daf360.payroll.modules.payroll.leavesettings;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** The per-country pay period (V42): calendar month by default, else start/M−1 → (start−1)/M. */
class PayPeriodTest {

    @Test
    void startDayOneIsTheCalendarMonth() {
        var p = PayrollLeaveSettingsService.period(2026, 2, 1);
        assertThat(p.from()).isEqualTo(LocalDate.of(2026, 2, 1));
        assertThat(p.to()).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(p.calendarMonth()).isTrue();
    }

    @Test
    void consecutiveMonthsAreContiguous() {
        var nov = PayrollLeaveSettingsService.period(2026, 11, 21);
        var dec = PayrollLeaveSettingsService.period(2026, 12, 21);
        assertThat(nov.from()).isEqualTo(LocalDate.of(2026, 10, 21));
        assertThat(nov.to()).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(dec.from()).isEqualTo(nov.to().plusDays(1));
        assertThat(dec.to()).isEqualTo(LocalDate.of(2026, 12, 20));
    }

    @Test
    void januaryStartsInDecemberOfThePreviousYear() {
        var jan = PayrollLeaveSettingsService.period(2027, 1, 21);
        assertThat(jan.from()).isEqualTo(LocalDate.of(2026, 12, 21));
        assertThat(jan.to()).isEqualTo(LocalDate.of(2027, 1, 20));
    }
}
