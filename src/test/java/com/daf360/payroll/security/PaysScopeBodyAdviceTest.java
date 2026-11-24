package com.daf360.payroll.security;

import com.daf360.payroll.modules.ref.service.UserContextService;
import com.daf360.payroll.modules.simulation.dto.CohortFilterRequest;
import com.daf360.payroll.modules.simulation.dto.CohortSimulationRequest;
import com.daf360.payroll.modules.simulation.dto.SimulationRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A1 — a {@code paysId} in the request body is held to the caller's country scope. */
class PaysScopeBodyAdviceTest {

    private static final long TN = 179L, EG = 53L;

    private UserContextService userContext;
    private PaysScopeBodyAdvice advice;

    @BeforeEach
    void setUp() {
        userContext = mock(UserContextService.class);
        advice = new PaysScopeBodyAdvice(new PaysScopeGuard(userContext));
        login("PAYROLL_RUN_SIMULATION");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static void login(String... codes) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "1", null, Arrays.stream(codes).map(SimpleGrantedAuthority::new).toList()));
    }

    private static CohortFilterRequest aggregate(long paysId) {
        return new CohortFilterRequest(paysId, null, null, null, null, null, null);
    }

    private Object read(Object body) {
        return advice.afterBodyRead(body, null, null, body.getClass(), null);
    }

    @Test
    void egyptScopedCaller_askingForTunisia_isRefused_onEveryBodyShape() {
        when(userContext.getCurrentUserPaysScope()).thenReturn(PaysScopeContext.PaysScope.of(Set.of(EG)));

        assertThatThrownBy(() -> read(aggregate(TN))).isInstanceOf(AccessDeniedException.class)
                .hasMessage(PaysScopeGuard.OUT_OF_SCOPE);
        assertThatThrownBy(() -> read(new CohortSimulationRequest(TN, 2026, "ZZ_x", null, List.of())))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> read(new SimulationRequest(TN, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null))).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void egyptScopedCaller_askingForEgypt_passes() {
        when(userContext.getCurrentUserPaysScope()).thenReturn(PaysScopeContext.PaysScope.of(Set.of(EG)));
        CohortFilterRequest body = aggregate(EG);
        assertThat(read(body)).isSameAs(body);
    }

    @Test
    void unknownScope_isRefused() {
        when(userContext.getCurrentUserPaysScope()).thenReturn(null);
        assertThatThrownBy(() -> read(aggregate(EG))).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void superAdmin_bypasses() {
        login(PermissionCatalog.SUPER_ADMIN);
        when(userContext.getCurrentUserPaysScope()).thenReturn(PaysScopeContext.PaysScope.of(Set.of(EG)));
        assertThat(read(aggregate(TN))).isNotNull();
    }

    @Test
    void bodyWithoutPaysId_isUntouched() {
        record NoCountry(String name) {}
        assertThat(advice.paysIdOf(new NoCountry("x"))).isNull();
        assertThat(read(new NoCountry("x"))).isNotNull();
    }

    @Test
    void beanGetter_isReadToo() {
        class Bean { public Long getPaysId() { return TN; } }
        assertThat(advice.paysIdOf(new Bean())).isEqualTo(TN);
    }
}
