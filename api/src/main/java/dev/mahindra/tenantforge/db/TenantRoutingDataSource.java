package dev.mahindra.tenantforge.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.AbstractDataSource;

import dev.mahindra.tenantforge.tenancy.Tenant;
import dev.mahindra.tenantforge.tenancy.TenantCatalog;
import dev.mahindra.tenantforge.tenancy.TenantContext;

/**
 * Pool-then-silo routing. Pooled tenants share one database; tenants marked SILO in the catalog get their own.
 * The application code is identical for both: only the connection's destination changes.
 */
public class TenantRoutingDataSource extends AbstractDataSource {

    private final DataSource pooled;
    private final TenantCatalog catalog;
    private final SiloDataSources silos;

    public TenantRoutingDataSource(DataSource pooled, TenantCatalog catalog, SiloDataSources silos) {
        this.pooled = pooled;
        this.catalog = catalog;
        this.silos = silos;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return target().getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return target().getConnection(username, password);
    }

    DataSource target() {
        Optional<UUID> id = TenantContext.current();
        if (id.isEmpty()) {
            return pooled;
        }
        Tenant tenant = catalog.find(id.get())
                .orElseThrow(() -> new IllegalStateException("Tenant " + id.get() + " is not in the catalog"));
        return tenant.silo() ? silos.forTenant(tenant) : pooled;
    }
}
