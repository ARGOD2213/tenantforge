package dev.mahindra.tenantforge.invoice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import dev.mahindra.tenantforge.IntegrationTest;

/**
 * The isolation guarantee, tested against real Postgres: Row-Level Security, not application code, keeps tenants
 * apart. Repository methods have no tenant filter on purpose.
 */
class TenantIsolationIT extends IntegrationTest {

    @Autowired
    InvoiceRepository invoices;

    @Test
    void queriesWithNoTenantFilterStillOnlySeeTheirOwnTenant() {
        asTenant(ACME, () -> invoices.saveAll(List.of(invoice("A-1"), invoice("A-2"))));
        asTenant(ZENITH, () -> invoices.save(invoice("Z-1")));

        assertThat(asTenant(ACME, () -> invoices.findAll()))
                .extracting(Invoice::getNumber).containsExactlyInAnyOrder("A-1", "A-2");
        // plain SQL with no WHERE clause, the classic leak, returns only zenith's row
        assertThat(asTenant(ZENITH, () -> jdbc.queryForObject("select count(*) from app.invoices", Long.class))).isEqualTo(1);
    }

    @Test
    void anotherTenantsRowIsInvisibleNotForbidden() {
        Invoice zenithInvoice = asTenant(ZENITH, () -> invoices.save(invoice("Z-9")));
        assertThat(asTenant(ACME, () -> invoices.findById(zenithInvoice.getId()))).isEmpty();
    }

    @Test
    void withNoTenantBoundNothingIsVisible() {
        asTenant(ACME, () -> invoices.save(invoice("A-3")));
        assertThat(jdbc.queryForObject("select count(*) from app.invoices", Long.class)).isZero();
    }

    @Test
    void theDatabaseRejectsARowForgedForAnotherTenant() {
        assertThatThrownBy(() -> asTenant(ACME, () -> {
            Invoice forged = invoice("X-1");
            forged.forceTenantId(ZENITH);
            return invoices.saveAndFlush(forged);
        })).hasStackTraceContaining("row-level security");
        assertThat(asTenant(ZENITH, () -> invoices.count())).isZero();
    }

    @Test
    void theRuntimeRoleCannotSwitchRowLevelSecurityOff() {
        asTenant(ACME, () -> invoices.save(invoice("A-4")));
        assertThatThrownBy(() -> asTenant(ACME, () -> jdbc.execute((ConnectionCallback<Long>) c -> {
            try (Statement st = c.createStatement()) {
                st.execute("set row_security = off");
                try (ResultSet rs = st.executeQuery("select count(*) from app.invoices")) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        }))).hasStackTraceContaining("row-level security");
    }

    @Test
    void connectionsAreUnboundWhenTheyGoBackToThePool() {
        String inside = asTenant(ACME, () -> jdbc.queryForObject("select current_setting('app.tenant_id', true)", String.class));
        String outside = jdbc.queryForObject("select current_setting('app.tenant_id', true)", String.class);
        assertThat(inside).isEqualTo(ACME.toString());
        assertThat(outside).isEmpty();
    }

    @Test
    void siloTenantRowsLiveInTheirOwnDatabase() {
        asTenant(ORBIT, () -> invoices.save(invoice("O-1")));

        // superuser connections bypass RLS: used here only to inspect where the row physically landed
        JdbcTemplate pooledDb = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "test", "test"));
        JdbcTemplate siloDb = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl().replace("/tenantforge", "/silo_orbit"), "test", "test"));

        assertThat(siloDb.queryForObject("select count(*) from app.invoices where number = 'O-1'", Long.class)).isEqualTo(1);
        assertThat(pooledDb.queryForObject("select count(*) from app.invoices where number = 'O-1'", Long.class)).isZero();
        assertThat(asTenant(ORBIT, () -> invoices.findAll())).extracting(Invoice::getNumber).containsExactly("O-1");
    }

    private static Invoice invoice(String number) {
        return new Invoice(number, "Customer " + number, 10_000, "INR");
    }
}
