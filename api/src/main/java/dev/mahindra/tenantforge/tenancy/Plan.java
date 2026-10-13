package dev.mahindra.tenantforge.tenancy;

/**
 * A row of {@code catalog.plans}. Limits and entitlements follow the plan; the plan follows Stripe.
 *
 * @param ratePerSecond sustained API requests per second for one tenant
 * @param burst         how many requests a tenant may make at once before being throttled
 */
public record Plan(String code, int ratePerSecond, int burst, boolean exportsEnabled) {
}
