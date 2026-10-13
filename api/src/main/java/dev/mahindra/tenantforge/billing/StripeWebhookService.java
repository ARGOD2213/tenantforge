package dev.mahindra.tenantforge.billing;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;

import dev.mahindra.tenantforge.TenantForgeProperties;
import dev.mahindra.tenantforge.tenancy.Tenant;
import dev.mahindra.tenantforge.tenancy.TenantCatalog;

/**
 * Stripe is the billing source of truth; this keeps each tenant's plan (and so its limits and entitlements) in step.
 *
 * <p>Idempotent: the event id is recorded in {@code catalog.processed_stripe_events} in the same transaction as the
 * plan change. A redelivered event finds its id already there and is acknowledged without being applied twice.
 */
@Service
public class StripeWebhookService {

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookService.class);
    private static final Set<String> PAID_STATUSES = Set.of("active", "trialing", "past_due");

    private final JdbcTemplate catalogJdbc;
    private final TransactionTemplate catalogTx;
    private final TenantCatalog catalog;
    private final TenantForgeProperties.Stripe stripe;

    public StripeWebhookService(@Qualifier("catalogJdbc") JdbcTemplate catalogJdbc,
            @Qualifier("catalogTx") TransactionTemplate catalogTx, TenantCatalog catalog, TenantForgeProperties props) {
        this.catalogJdbc = catalogJdbc;
        this.catalogTx = catalogTx;
        this.catalog = catalog;
        this.stripe = props.stripe();
    }

    public Outcome handle(JsonNode event) {
        String eventId = event.path("id").asText();
        String type = event.path("type").asText();
        Outcome outcome = catalogTx.execute(tx -> {
            int fresh = catalogJdbc.update(
                    "insert into catalog.processed_stripe_events (event_id, type) values (?, ?) on conflict (event_id) do nothing",
                    eventId, type);
            if (fresh == 0) {
                return Outcome.duplicate();
            }
            return switch (type) {
                case "customer.subscription.created", "customer.subscription.updated" ->
                        applySubscription(event.path("data").path("object"), false);
                case "customer.subscription.deleted" -> applySubscription(event.path("data").path("object"), true);
                default -> Outcome.ignored();
            };
        });
        if (outcome.tenantId() != null) {
            catalog.evict(outcome.tenantId()); // after commit: next request reads the new plan
        }
        log.info("Stripe event {} ({}) → {}", eventId, type, outcome.status());
        return outcome;
    }

    private Outcome applySubscription(JsonNode subscription, boolean deleted) {
        String customer = text(subscription.path("customer"));
        Optional<UUID> tenantId = parseUuid(text(subscription.path("metadata").path("tenant_id")))
                .or(() -> Optional.ofNullable(customer).flatMap(catalog::findByStripeCustomer).map(Tenant::id));
        if (tenantId.isEmpty()) {
            return Outcome.ignored();
        }
        String plan;
        if (deleted || !PAID_STATUSES.contains(subscription.path("status").asText())) {
            plan = "free";
        } else {
            String lookupKey = text(subscription.path("items").path("data").path(0).path("price").path("lookup_key"));
            plan = lookupKey == null ? null : stripe.pricePlans().get(lookupKey);
            if (plan == null) {
                log.warn("Stripe price lookup_key {} is not mapped to a plan; leaving tenant unchanged", lookupKey);
                return Outcome.ignored();
            }
        }
        catalog.updatePlan(tenantId.get(), plan, customer);
        return new Outcome("processed", tenantId.get(), plan);
    }

    private static String text(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }

    private static Optional<UUID> parseUuid(String value) {
        try {
            return value == null ? Optional.empty() : Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public record Outcome(String status, UUID tenantId, String plan) {
        static Outcome duplicate() {
            return new Outcome("duplicate", null, null);
        }

        static Outcome ignored() {
            return new Outcome("ignored", null, null);
        }
    }
}
