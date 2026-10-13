package dev.mahindra.tenantforge.tenancy;

import java.util.UUID;

/**
 * A row of {@code catalog.tenants}: who the tenant is, what they pay for, and where their data lives.
 *
 * @param tier    POOLED tenants share the pooled database (isolated by RLS); SILO tenants get their own database
 * @param dbHost  silo only; null means "same server as the pooled database"
 * @param dbName  silo only; the tenant's dedicated database
 */
public record Tenant(
        UUID id,
        String slug,
        String name,
        String plan,
        Tier tier,
        String dbHost,
        Integer dbPort,
        String dbName,
        String stripeCustomerId,
        String status) {

    public enum Tier { POOLED, SILO }

    public boolean active() {
        return "active".equals(status);
    }

    public boolean silo() {
        return tier == Tier.SILO;
    }
}
