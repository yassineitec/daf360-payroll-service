package com.daf360.payroll.config;

import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.sql.DataSource;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Wraps the real DataSource so every borrowed connection has SESSION_CONTEXT('app_user')
 * set before any statement runs on it -- read by the generic audit triggers
 * (tools/generate-audit-triggers.ps1 in daf360-sql). Writes made outside the app
 * (ad-hoc SQL scripts) simply leave this NULL.
 */
public class SessionContextDataSource extends DelegatingDataSource {

    public SessionContextDataSource(DataSource targetDataSource) {
        super(targetDataSource);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return withSessionContext(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return withSessionContext(super.getConnection(username, password));
    }

    private Connection withSessionContext(Connection connection) throws SQLException {
        try (CallableStatement cs = connection.prepareCall("{call sys.sp_set_session_context(?, ?)}")) {
            cs.setString(1, "app_user");
            cs.setString(2, currentUsername());
            cs.execute();
        }
        return connection;
    }

    private String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (auth != null && auth.isAuthenticated()) ? auth.getName() : null;
    }
}
