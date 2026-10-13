package dev.mahindra.tenantforge.invoice;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import dev.mahindra.tenantforge.tenancy.TenantContext;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * An invoice belongs to exactly one tenant. The entity sets {@code tenantId} from the bound tenant, but the
 * database is what enforces it: the RLS {@code WITH CHECK} rejects a row whose tenant doesn't match the connection.
 */
@Entity
@Table(schema = "app", name = "invoices")
public class Invoice {

    public static final int DEFAULT_TERMS_DAYS = 30;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String number;

    @Column(nullable = false)
    private String customer;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false)
    private String status = "draft";

    @Column(name = "issued_on", nullable = false)
    private LocalDate issuedOn = LocalDate.now();

    /** Added by an expand/contract migration (V2): nullable until the contract step, always written from here on. */
    @Column(name = "due_on")
    private LocalDate dueOn;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Invoice() {
    }

    public Invoice(String number, String customer, long amountCents, String currency) {
        this(number, customer, amountCents, currency, null);
    }

    public Invoice(String number, String customer, long amountCents, String currency, LocalDate dueOn) {
        this.number = number;
        this.customer = customer;
        this.amountCents = amountCents;
        this.currency = currency;
        this.dueOn = dueOn == null ? issuedOn.plusDays(DEFAULT_TERMS_DAYS) : dueOn;
    }

    @PrePersist
    void bindTenant() {
        if (tenantId == null) {
            tenantId = TenantContext.require();
        }
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getNumber() { return number; }
    public String getCustomer() { return customer; }
    public long getAmountCents() { return amountCents; }
    public String getCurrency() { return currency; }
    public String getStatus() { return status; }
    public LocalDate getIssuedOn() { return issuedOn; }
    public LocalDate getDueOn() { return dueOn; }
    public Instant getCreatedAt() { return createdAt; }

    /** Only for tests that prove the database rejects a forged tenant. */
    void forceTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }
}
