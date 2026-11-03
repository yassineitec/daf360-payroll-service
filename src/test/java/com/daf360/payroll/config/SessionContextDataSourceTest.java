package com.daf360.payroll.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.sql.DataSource;
import java.sql.CallableStatement;
import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionContextDataSourceTest {

    @Mock DataSource target;
    @Mock Connection connection;
    @Mock CallableStatement callableStatement;

    @Test
    void getConnection_setsSessionContextToTheAuthenticatedUsername() throws Exception {
        // 3-arg constructor: Spring Security's 2-arg UsernamePasswordAuthenticationToken(principal,
        // credentials) is unauthenticated by construction (isAuthenticated() == false); only the
        // 3-arg form (with an authorities collection, even empty) marks the token authenticated.
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", null, java.util.List.of()));
        when(target.getConnection()).thenReturn(connection);
        when(connection.prepareCall("{call sys.sp_set_session_context(?, ?)}")).thenReturn(callableStatement);

        SessionContextDataSource wrapped = new SessionContextDataSource(target);
        Connection result = wrapped.getConnection();

        verify(callableStatement).setString(1, "app_user");
        verify(callableStatement).setString(2, "alice");
        verify(callableStatement).execute();
        assertSame(connection, result);

        SecurityContextHolder.clearContext();
    }

    @Test
    void getConnection_noAuthentication_setsNullUsername() throws Exception {
        SecurityContextHolder.clearContext();
        when(target.getConnection()).thenReturn(connection);
        when(connection.prepareCall("{call sys.sp_set_session_context(?, ?)}")).thenReturn(callableStatement);

        new SessionContextDataSource(target).getConnection();

        verify(callableStatement).setString(eq(2), eq((String) null));
    }
}
