package dev.mahindra.tenantforge.db;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import dev.mahindra.tenantforge.tenancy.TenantContext;

/**
 * Binds every connection to the current tenant before anyone can use it.
 *
 * <p>On checkout it runs {@code set_config('app.tenant_id', <tenant>, false)}; the Row-Level Security policies on
 * every tenant table compare {@code tenant_id} against that setting. With no tenant bound the setting is empty and
 * the policies match nothing, so the default is "see no rows" rather than "see all rows".
 *
 * <p>On close the setting is cleared again before the connection goes back to the pool. Checkout always overwrites
 * it anyway, so a connection can never carry one tenant's identity into another tenant's request.
 */
public class TenantAwareDataSource extends DelegatingDataSource {

    static final String SET_TENANT = "select set_config('app.tenant_id', ?, false)";

    public TenantAwareDataSource(DataSource target) {
        super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return bind(obtainTargetDataSource().getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return bind(obtainTargetDataSource().getConnection(username, password));
    }

    private Connection bind(Connection connection) throws SQLException {
        String tenant = TenantContext.current().map(UUID::toString).orElse("");
        try {
            setTenant(connection, tenant);
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
        return (Connection) Proxy.newProxyInstance(
                TenantAwareDataSource.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "close" -> {
                        resetQuietly(connection);
                        connection.close();
                        yield null;
                    }
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "TenantBoundConnection[" + tenant + "] " + connection;
                    default -> {
                        try {
                            yield method.invoke(connection, args);
                        } catch (InvocationTargetException e) {
                            throw e.getTargetException();
                        }
                    }
                });
    }

    static void setTenant(Connection connection, String tenant) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(SET_TENANT)) {
            ps.setString(1, tenant);
            ps.execute();
        }
    }

    private static void resetQuietly(Connection connection) {
        try {
            // Only outside a transaction: inside one, the reset could be rolled back. The next checkout
            // overwrites the setting regardless.
            if (!connection.isClosed() && connection.getAutoCommit()) {
                setTenant(connection, "");
            }
        } catch (SQLException ignored) {
            // the pool validates connections; a broken one is evicted there
        }
    }
}
