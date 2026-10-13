package dev.mahindra.tenantforge.db;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import dev.mahindra.tenantforge.TenantForgeProperties;
import dev.mahindra.tenantforge.tenancy.Tenant;

/**
 * One small connection pool per silo tenant, created the first time that tenant is served.
 * The tenant schema is migrated before the pool is handed out, so a newly onboarded silo just works.
 */
public class SiloDataSources implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(SiloDataSources.class);

    private final TenantForgeProperties.Db db;
    private final TenantMigrations migrations;
    private final Map<UUID, HikariDataSource> pools = new ConcurrentHashMap<>();

    public SiloDataSources(TenantForgeProperties props, TenantMigrations migrations) {
        this.db = props.db();
        this.migrations = migrations;
    }

    public DataSource forTenant(Tenant tenant) {
        return pools.computeIfAbsent(tenant.id(), id -> create(tenant));
    }

    private HikariDataSource create(Tenant tenant) {
        String url = migrations.siloUrl(tenant.dbHost(), tenant.dbPort(), tenant.dbName());
        migrations.migrateTenantSchema(url);
        HikariConfig config = new HikariConfig();
        config.setPoolName("silo-" + tenant.slug());
        config.setJdbcUrl(url);
        config.setUsername(db.appUsername());
        config.setPassword(db.appPassword());
        config.setMaximumPoolSize(db.siloPoolSize());
        config.setMinimumIdle(0);
        log.info("Opened a dedicated pool for silo tenant {}", tenant.slug());
        return new HikariDataSource(config);
    }

    @Override
    public void destroy() {
        pools.values().forEach(HikariDataSource::close);
    }
}
