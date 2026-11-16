package com.daf360.payroll.modules.payroll;

import com.daf360.payroll.config.AppProperties;
import com.daf360.payroll.modules.payroll.controller.InternalPayrollLoadedCostController;
import com.daf360.payroll.modules.payroll.controller.InternalPayrollLoadedCostController.LoadedCostRow;
import com.daf360.payroll.modules.payroll.entity.PayrollCountry;
import com.daf360.payroll.modules.payroll.entity.PayrollResult;
import com.daf360.payroll.modules.payroll.repository.PayrollCountryRepository;
import com.daf360.payroll.modules.payroll.repository.PayrollResultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class InternalPayrollLoadedCostControllerTest {

    private final PayrollResultRepository results = mock(PayrollResultRepository.class);
    private final PayrollCountryRepository countries = mock(PayrollCountryRepository.class);
    private final AppProperties props = new AppProperties();
    private final InternalPayrollLoadedCostController controller =
            new InternalPayrollLoadedCostController(results, countries, props);

    @BeforeEach
    void setUp() {
        props.setInternalApiKey("secret");
        PayrollCountry tn = new PayrollCountry();
        tn.setId(7L);
        tn.setCurrencyCode("TND");
        when(countries.findById(7L)).thenReturn(Optional.of(tn));
    }

    private PayrollResult result(long id, long employeeId, int month, String loadedCost, OffsetDateTime at) {
        PayrollResult r = new PayrollResult();
        r.setId(id);
        r.setEmployeeId(employeeId);
        r.setCountryId(7L);
        r.setPaysId(179L);
        r.setPeriodYear(2026);
        r.setPeriodMonth(month);
        r.setLoadedCost(new BigDecimal(loadedCost));
        r.setCalculatedAt(at);
        return r;
    }

    @Test
    void latestRunPerEmployeeAndMonth_onlyRequestedMonths_withCountryCurrency() {
        OffsetDateTime t1 = OffsetDateTime.parse("2026-11-10T10:00:00Z");
        OffsetDateTime t2 = OffsetDateTime.parse("2026-11-12T10:00:00Z");
        when(results.findByPaysIdAndPeriodKeyBetween(179L, 202610, 202611)).thenReturn(List.of(
                result(1, 1, 11, "2939.25", t1),
                result(2, 1, 11, "3014.25", t2),   // re-run: wins
                result(3, 56, 11, "2496.14", t1),
                result(4, 56, 10, "2400.00", t1)));

        List<LoadedCostRow> rows = controller.loadedCosts(179L, 2026, List.of(10, 11), "secret");

        assertThat(rows).containsExactlyInAnyOrder(
                new LoadedCostRow(1L, 11, new BigDecimal("3014.25"), "TND"),
                new LoadedCostRow(56L, 11, new BigDecimal("2496.14"), "TND"),
                new LoadedCostRow(56L, 10, new BigDecimal("2400.00"), "TND"));
    }

    @Test
    void monthsOutsideTheList_areIgnored() {
        when(results.findByPaysIdAndPeriodKeyBetween(179L, 202601, 202603)).thenReturn(List.of(
                result(1, 1, 2, "100", OffsetDateTime.now())));

        assertThat(controller.loadedCosts(179L, 2026, List.of(1, 3), "secret")).isEmpty();
    }

    @Test
    void wrongKey_isForbidden() {
        assertThatThrownBy(() -> controller.loadedCosts(179L, 2026, List.of(1), "nope"))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(results);
    }
}
