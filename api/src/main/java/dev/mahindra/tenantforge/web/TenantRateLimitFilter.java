package dev.mahindra.tenantforge.web;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import org.springframework.web.filter.OncePerRequestFilter;

import dev.mahindra.tenantforge.ratelimit.TokenBucketRateLimiter;
import dev.mahindra.tenantforge.tenancy.Plan;
import dev.mahindra.tenantforge.tenancy.Tenant;
import dev.mahindra.tenantforge.tenancy.TenantCatalog;
import dev.mahindra.tenantforge.tenancy.TenantContext;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Spends one token from the tenant's bucket per API request. An empty bucket answers
 * {@code 429 Too Many Requests} with {@code Retry-After}; other tenants are unaffected.
 */
public class TenantRateLimitFilter extends OncePerRequestFilter {

    private final TenantCatalog catalog;
    private final TokenBucketRateLimiter limiter;
    private final MeterRegistry meters;

    public TenantRateLimitFilter(TenantCatalog catalog, TokenBucketRateLimiter limiter, MeterRegistry meters) {
        this.catalog = catalog;
        this.limiter = limiter;
        this.meters = meters;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<UUID> tenantId = TenantContext.current();
        Optional<Tenant> tenant = tenantId.flatMap(catalog::find);
        if (tenant.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        Plan plan = catalog.plan(tenant.get().plan());
        TokenBucketRateLimiter.Decision decision = limiter.tryAcquire(tenant.get().id(), plan);
        response.setHeader("X-RateLimit-Limit", String.valueOf(plan.burst()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remaining()));
        if (!decision.allowed()) {
            meters.counter("tenantforge.ratelimit.rejected", "tenant", tenant.get().slug()).increment();
            response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
            Problems.write(response, 429, "rate_limited",
                    "Plan '" + plan.code() + "' allows " + plan.ratePerSecond() + " requests/second (burst " + plan.burst() + ")");
            return;
        }
        chain.doFilter(request, response);
    }
}
