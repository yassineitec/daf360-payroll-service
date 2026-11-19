package com.daf360.payroll.modules.payroll;

import com.daf360.payroll.config.AppProperties;
import com.daf360.payroll.modules.payroll.controller.InternalPayrollHeadcountController;
import com.daf360.payroll.modules.payroll.repository.PayrollResultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InternalPayrollHeadcountControllerTest {

    private final PayrollResultRepository repo = mock(PayrollResultRepository.class);
    private final AppProperties props = new AppProperties();
    private final InternalPayrollHeadcountController controller = new InternalPayrollHeadcountController(repo, props);

    @BeforeEach
    void setUp() {
        props.setInternalApiKey("secret");
    }

    @Test
    void returnsDistinctEmployeesPerMonth() {
        when(repo.countEmployeesByMonth(179L, 2026)).thenReturn(List.of(
                new Object[]{11, 2L}, new Object[]{10, 5L}));

        Map<Integer, Long> result = controller.headcountByMonth(179L, 2026, "secret");

        assertThat(result).containsExactly(Map.entry(10, 5L), Map.entry(11, 2L));
    }

    @Test
    void wrongKey_isForbidden() {
        assertThatThrownBy(() -> controller.headcountByMonth(179L, 2026, "nope"))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(repo);
    }

    @Test
    void blankConfiguredKey_failsClosed() {
        props.setInternalApiKey("");
        assertThatThrownBy(() -> controller.headcountByMonth(179L, 2026, ""))
                .isInstanceOf(AccessDeniedException.class);
    }
}
