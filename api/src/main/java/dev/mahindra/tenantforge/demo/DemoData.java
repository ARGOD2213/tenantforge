package dev.mahindra.tenantforge.demo;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import dev.mahindra.tenantforge.invoice.Invoice;
import dev.mahindra.tenantforge.invoice.InvoiceRepository;
import dev.mahindra.tenantforge.tenancy.Tenant;
import dev.mahindra.tenantforge.tenancy.TenantCatalog;
import dev.mahindra.tenantforge.tenancy.TenantContext;

/**
 * Three demo tenants for local runs ({@code tenantforge.demo-data=true}):
 * <ul>
 *   <li>acme: pooled, free plan</li>
 *   <li>zenith: pooled, pro plan</li>
 *   <li>orbit: enterprise, SILO in its own database {@code silo_orbit}</li>
 * </ul>
 * Invoices are inserted through the normal tenant-bound repository, so orbit's rows land in the silo database.
 */
@Component
@ConditionalOnProperty(name = "tenantforge.demo-data", havingValue = "true")
class DemoData implements ApplicationRunner {

    static final UUID ACME = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID ZENITH = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID ORBIT = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static final Logger log = LoggerFactory.getLogger(DemoData.class);

    private final TenantCatalog catalog;
    private final InvoiceRepository invoices;
    private final TransactionTemplate tx;

    DemoData(TenantCatalog catalog, InvoiceRepository invoices, PlatformTransactionManager transactionManager) {
        this.catalog = catalog;
        this.invoices = invoices;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        catalog.insertIfAbsent(new Tenant(ACME, "acme", "Acme Corp", "free", Tenant.Tier.POOLED, null, null, null, null, "active"));
        catalog.insertIfAbsent(new Tenant(ZENITH, "zenith", "Zenith Labs", "pro", Tenant.Tier.POOLED, null, null, null, null, "active"));
        catalog.insertIfAbsent(new Tenant(ORBIT, "orbit", "Orbit Logistics", "enterprise", Tenant.Tier.SILO, null, null,
                "silo_orbit", null, "active"));

        seed(ACME, List.of(new Invoice("ACME-001", "Sunrise Bakery", 125_000, "INR"),
                new Invoice("ACME-002", "Blue Lotus Clinic", 89_900, "INR")));
        seed(ZENITH, List.of(new Invoice("ZEN-001", "Harbor Analytics", 42_000, "USD")));
        seed(ORBIT, List.of(new Invoice("ORB-001", "Northgate Freight", 9_900_000, "USD"),
                new Invoice("ORB-002", "Kestrel Ports", 2_500_000, "USD")));
        log.info("Demo tenants ready: acme (pooled/free), zenith (pooled/pro), orbit (silo/enterprise)");
    }

    private void seed(UUID tenant, List<Invoice> rows) {
        TenantContext.runAs(tenant, () -> tx.executeWithoutResult(s -> {
            if (invoices.count() == 0) {
                invoices.saveAll(rows);
            }
        }));
    }
}
