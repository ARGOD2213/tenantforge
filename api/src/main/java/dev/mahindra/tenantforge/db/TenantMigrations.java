package dev.mahindra.tenantforge.db;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import dev.mahindra.tenantforge.TenantForgeProperties;

/**
 * Migration tooling that runs per database.
 *
 * <ul>
 *   <li>{@code db/catalog}: the tenant catalog, pooled database only.</li>
 *   <li>{@code db/tenant}: tenant tables and their RLS policies, on the pooled database <em>and</em> every silo.</li>
 * </ul>
 *
 * Runs at startup as the owner role (before JPA starts) and again whenever a new silo database is first used.
 */
public class TenantMigrations implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(TenantMigrations.class);
    private static final Pattern JDBC_URL = Pattern.compile("^jdbc:postgresql://([^/]+)/([^?]*)(\\?.*)?$");

    private final TenantForgeProperties.Db db;

    public TenantMigrations(TenantForgeProperties props) {
        this.db = props.db();
    }

    @Override
    public void afterPropertiesSet() {
        flyway(db.url(), "catalog", "classpath:db/catalog").migrate();
        migrateTenantSchema(db.url());
        JdbcTemplate owner = new JdbcTemplate(new DriverManagerDataSource(db.url(), db.ownerUsername(), db.ownerPassword()));
        List<String> siloUrls = owner.query("select db_host, db_port, db_name from catalog.tenants where tier = 'SILO'",
                (rs, i) -> {
                    String host = rs.getString("db_host");
                    int rawPort = rs.getInt("db_port");
                    Integer port = rs.wasNull() ? null : rawPort;
                    return siloUrl(host, port, rs.getString("db_name"));
                });
        siloUrls.forEach(this::migrateTenantSchema);
        log.info("Migrations done for the pooled database and {} silo database(s)", siloUrls.size());
    }

    public void migrateTenantSchema(String url) {
        var result = flyway(url, "app", "classpath:db/tenant").migrate();
        log.info("Tenant schema at {} is at version {}", redact(url), result.targetSchemaVersion);
    }

    /** JDBC URL of a silo database; host/port default to the pooled database's server. */
    public String siloUrl(String host, Integer port, String dbName) {
        Matcher m = JDBC_URL.matcher(db.url());
        if (!m.matches()) {
            throw new IllegalArgumentException("Unsupported JDBC URL: " + db.url());
        }
        String hostPort = host == null ? m.group(1) : host + ":" + (port == null ? 5432 : port);
        String params = m.group(3) == null ? "" : m.group(3);
        return "jdbc:postgresql://" + hostPort + "/" + dbName + params;
    }

    private Flyway flyway(String url, String schema, String location) {
        return Flyway.configure()
                // session-level advisory lock instead of one held in an open transaction: an open transaction would
                // make CREATE INDEX CONCURRENTLY wait for it forever
                .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
                .dataSource(url, db.ownerUsername(), db.ownerPassword())
                .schemas(schema)
                .table("flyway_history")
                .locations(location)
                .placeholders(Map.of("appRole", db.appUsername()))
                .load();
    }

    private static String redact(String url) {
        return url.replaceAll("password=[^&]*", "password=***");
    }
}
