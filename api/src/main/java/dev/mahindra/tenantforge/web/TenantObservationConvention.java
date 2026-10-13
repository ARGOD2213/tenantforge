package dev.mahindra.tenantforge.web;

import org.springframework.http.server.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.stereotype.Component;

import io.micrometer.common.KeyValues;

/**
 * Adds a {@code tenant} tag (the tenant slug, or {@code none}) to {@code http.server.requests}, so request rate,
 * errors and latency can be charted per tenant. Slugs are bounded by the number of tenants, which keeps the tag's
 * cardinality in the hundreds, not per user or per request.
 */
@Component
class TenantObservationConvention extends DefaultServerRequestObservationConvention {

    @Override
    public KeyValues getLowCardinalityKeyValues(ServerRequestObservationContext context) {
        Object tenant = context.getCarrier().getAttribute(TenantResolutionFilter.TENANT_ATTRIBUTE);
        return super.getLowCardinalityKeyValues(context).and("tenant", tenant == null ? "none" : tenant.toString());
    }
}
