package com.stocksafe.core.tenant;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Wraps the pool. On EVERY checkout it writes the tenant id into the DB session
 * (or an empty value). So a pooled connection can never carry a stale tenant.
 */
public class TenantDataSource extends DelegatingDataSource {

    public TenantDataSource(DataSource target) {
        super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return applyTenant(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return applyTenant(super.getConnection(username, password));
    }

    private Connection applyTenant(Connection conn) throws SQLException {
        UUID tenant = TenantContext.get();
        try (PreparedStatement ps = conn.prepareStatement("select set_config('app.tenant_id', ?, false)")) {
            ps.setString(1, tenant == null ? "" : tenant.toString());
            ps.execute();
        } catch (SQLException e) {
            conn.close();
            throw e;
        }
        return conn;
    }
}
