package com.daf360.payroll.modules.payroll.leavesettings;

import com.daf360.payroll.modules.payroll.repository.PayrollCountryRepository;
import com.daf360.payroll.modules.payroll.repository.PayrollParamSetRepository;
import com.daf360.payroll.modules.payroll.variable.HrPayrollInputsClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** V43: majoration per weekend day (default = engine parameter), cap employee → role → country → 8 h. */
@ExtendWith(MockitoExtension.class)
class OvertimeSettingsServiceTest {

    @Mock private OvertimeDayRateRepository dayRateRepo;
    @Mock private OvertimeCapRepository capRepo;
    @Mock private PayrollCountryRepository countryRepo;
    @Mock private PayrollParamSetRepository paramSetRepo;
    @Mock private HrPayrollInputsClient hrClient;

    private OvertimeSettingsService service() {
        return new OvertimeSettingsService(dayRateRepo, capRepo, countryRepo, paramSetRepo, hrClient, new ObjectMapper());
    }

    private static OvertimeDayRate day(String d, String pct) {
        OvertimeDayRate r = new OvertimeDayRate();
        r.setPaysId(178L);
        r.setDayOfWeek(d);
        r.setMajorationPct(new BigDecimal(pct));
        return r;
    }

    private static OvertimeCap cap(String scope, String hours) {
        OvertimeCap c = new OvertimeCap();
        c.setScope(scope);
        c.setCapHours(new BigDecimal(hours));
        return c;
    }

    @Test
    void dayRateWinsAndOtherDaysFallBackToTheParameter() {
        when(dayRateRepo.findByPaysId(178L)).thenReturn(List.of(day("SUNDAY", "100")));
        when(capRepo.findByScopeAndProfileUserId(OvertimeCap.EMPLOYEE, 1L)).thenReturn(Optional.empty());
        when(capRepo.findByScopeAndPaysIdAndRoleId(OvertimeCap.ROLE, 178L, 3L)).thenReturn(Optional.empty());

        var r = service().rulesFor(178L, 3L, 1L, Map.of("TAUX_MAJORATION_HS_WEEKEND", 50, "PLAFOND_HS_JOUR", 7));

        assertThat(r.pctFor(DayOfWeek.SUNDAY)).isEqualByComparingTo("100");
        assertThat(r.pctFor(DayOfWeek.SATURDAY)).isEqualByComparingTo("50");   // capitals, as the screen stores them
        assertThat(r.dailyCap()).isEqualByComparingTo("7");
        assertThat(r.capSource()).isEqualTo(OvertimeSettingsService.CapSource.COUNTRY);
    }

    @Test
    void employeeCapBeatsRoleCapWhichBeatsTheCountry() {
        when(dayRateRepo.findByPaysId(178L)).thenReturn(List.of());
        when(capRepo.findByScopeAndProfileUserId(OvertimeCap.EMPLOYEE, 1L)).thenReturn(Optional.of(cap("EMPLOYEE", "4")));
        assertThat(service().rulesFor(178L, 3L, 1L, Map.of("plafond_hs_jour", 7)).capSource())
                .isEqualTo(OvertimeSettingsService.CapSource.EMPLOYEE);

        when(capRepo.findByScopeAndProfileUserId(OvertimeCap.EMPLOYEE, 2L)).thenReturn(Optional.empty());
        when(capRepo.findByScopeAndPaysIdAndRoleId(OvertimeCap.ROLE, 178L, 3L)).thenReturn(Optional.of(cap("ROLE", "5")));
        var role = service().rulesFor(178L, 3L, 2L, Map.of("plafond_hs_jour", 7));
        assertThat(role.dailyCap()).isEqualByComparingTo("5");
        assertThat(role.capSource()).isEqualTo(OvertimeSettingsService.CapSource.ROLE);
    }

    @Test
    void nothingConfiguredMeansEightHoursAndNoMajoration() {
        when(dayRateRepo.findByPaysId(178L)).thenReturn(List.of());
        when(capRepo.findByScopeAndProfileUserId(OvertimeCap.EMPLOYEE, 1L)).thenReturn(Optional.empty());

        var r = service().rulesFor(178L, null, 1L, Map.of());

        assertThat(r.dailyCap()).isEqualByComparingTo("8");
        assertThat(r.capSource()).isEqualTo(OvertimeSettingsService.CapSource.DEFAULT);
        assertThat(r.pctFor(DayOfWeek.SATURDAY)).isNull();
    }
}
