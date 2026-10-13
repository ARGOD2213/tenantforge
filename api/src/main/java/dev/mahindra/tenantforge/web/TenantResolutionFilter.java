package dev.mahindra.tenantforge.web;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import dev.mahindra.tenantforge.security.TenantClaims;
import dev.mahindra.tenantforge.tenancy.Tenant;
import dev.mahindra.tenantforge.tenancy.TenantCatalog;
import dev.mahindra.tenantforge.tenancy.TenantContext;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Binds the request to the tenant named in the verified JWT. The tenant must exist in the catalog and be active.
 * Logs, traces and request metrics are tagged with the tenant so a noisy or failing tenant is easy to spot.
 */
public class TenantResolutionFilter extends OncePerRequestFilter {

    /** Request attribute holding the tenant slug, read by {@link TenantObservationConvention} after the request. */
    public static final String TENANT_ATTRIBUTE = "tenantforge.tenant";

    private final TenantCatalog catalog;
    private final ObjectProvider<Tracer> tracer;

    public TenantResolutionFilter(TenantCatalog catalog, ObjectProvider<Tracer> tracer) {
        this.catalog = catalog;
        this.tracer = tracer;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwtAuth)) {
            chain.doFilter(request, response); // unauthenticated: the authorization filter answers 401
            return;
        }
        Optional<UUID> tenantId = TenantClaims.tenantId(jwtAuth.getToken());
        if (tenantId.isEmpty()) {
            Problems.write(response, 403, "no_tenant", "The access token does not name exactly one tenant");
            return;
        }
        Optional<Tenant> tenant = catalog.find(tenantId.get());
        if (tenant.isEmpty() || !tenant.get().active()) {
            Problems.write(response, 403, "tenant_unavailable", "Tenant is unknown or suspended");
            return;
        }
        TenantContext.set(tenantId.get());
        request.setAttribute(TENANT_ATTRIBUTE, tenant.get().slug());
        MDC.put("tenant_id", tenant.get().slug());
        Tracer t = tracer.getIfAvailable();
        Span span = t == null ? null : t.currentSpan();
        if (span != null) {
            span.tag("tenant.id", tenantId.get().toString());
            span.tag("tenant.tier", tenant.get().tier().name());
        }
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            MDC.remove("tenant_id");
        }
    }
}
