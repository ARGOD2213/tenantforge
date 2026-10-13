package dev.mahindra.tenantforge.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import dev.mahindra.tenantforge.IntegrationTest;

/**
 * The tenant schema is migrated per database: the pooled database and every silo end at the same version, and the
 * concurrently built index is valid on both.
 */
class MigrationsIT extends IntegrationTest {

    @Test
    void pooledAndSiloDatabasesRunTheSameTenantMigrations() {
        // the base class has already touched orbit, which migrated its silo database on first use
        for (String url : List.of(POSTGRES.getJdbcUrl(), POSTGRES.getJdbcUrl().replace("/tenantforge", "/silo_orbit"))) {
            JdbcTemplate db = new JdbcTemplate(new DriverManagerDataSource(url, "test", "test"));
            assertThat(db.queryForObject("select max(version::int) from app.flyway_history where success", Integer.class))
                    .as("tenant schema version at %s", url)
                    .isEqualTo(3);
            assertThat(db.queryForObject(
                    "select i.indisvalid from pg_index i where i.indexrelid = 'app.invoices_tenant_due_idx'::regclass",
                    Boolean.class))
                    .as("concurrent index valid at %s", url)
                    .isTrue();
        }
    }

    @Test
    void expandStepKeepsOldWritersWorking() {
        // a writer from the previous release does not know due_on: the nullable column accepts its inserts
        asTenant(ACME, () -> jdbc.update(
                "insert into app.invoices (number, customer, amount_cents) values ('OLD-1', 'Legacy writer', 100)"));
        assertThat(asTenant(ACME, () -> jdbc.queryForObject(
                "select due_on from app.invoices where number = 'OLD-1'", LocalDate.class))).isNull();
    }
}
