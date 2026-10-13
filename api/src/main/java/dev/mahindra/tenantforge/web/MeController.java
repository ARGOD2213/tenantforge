package dev.mahindra.tenantforge.web;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.mahindra.tenantforge.security.TenantClaims;
import dev.mahindra.tenantforge.tenancy.Plan;
import dev.mahindra.tenantforge.tenancy.Tenant;
import dev.mahindra.tenantforge.tenancy.TenantCatalog;
import dev.mahindra.tenantforge.tenancy.TenantContext;

/** Who am I, which tenant, which plan, and what that plan allows. Used by the dashboard header. */
@RestController
class MeController {

    private final TenantCatalog catalog;

    MeController(TenantCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/api/me")
    Me me(@AuthenticationPrincipal Jwt jwt) {
        Tenant t = catalog.find(TenantContext.require()).orElseThrow();
        Plan p = catalog.plan(t.plan());
        String user = jwt.getClaimAsString("preferred_username");
        return new Me(
                user == null ? jwt.getSubject() : user,
                TenantClaims.roles(jwt).stream().filter(r -> r.startsWith("tenant_")).toList(),
                new TenantView(t.id(), t.slug(), t.name(), t.plan(), t.tier().name()),
                new Limits(p.ratePerSecond(), p.burst()),
                new Entitlements(p.exportsEnabled()));
    }

    record Me(String user, List<String> roles, TenantView tenant, Limits limits, Entitlements entitlements) {
    }

    record TenantView(UUID id, String slug, String name, String plan, String tier) {
    }

    record Limits(int requestsPerSecond, int burst) {
    }

    record Entitlements(boolean exports) {
    }
}
