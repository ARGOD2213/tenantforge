package dev.mahindra.tenantforge.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import dev.mahindra.tenantforge.IntegrationTest;

/** Stripe is the billing source of truth; webhooks are verified and applied exactly once. */
class StripeWebhookIT extends IntegrationTest {

    private static final String SECRET = "whsec_test_secret";

    @Autowired
    MockMvc mvc;

    @Autowired
    @Qualifier("catalogJdbc")
    JdbcTemplate catalogJdbc;

    @Test
    void anUpgradeChangesThePlanLimitsAndEntitlements() throws Exception {
        String event = subscriptionEvent("evt_" + UUID.randomUUID(), "customer.subscription.updated", ACME, "active", "pro_monthly");

        deliver(event).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("processed"));

        assertThat(catalog.find(ACME).orElseThrow().plan()).isEqualTo("pro");
        mvc.perform(get("/api/me").with(user(ACME, "tenant_admin")))
                .andExpect(jsonPath("$.tenant.plan").value("pro"))
                .andExpect(jsonPath("$.entitlements.exports").value(true));
    }

    @Test
    void aRedeliveredEventIsAcknowledgedButAppliedOnce() throws Exception {
        String eventId = "evt_" + UUID.randomUUID();
        deliver(subscriptionEvent(eventId, "customer.subscription.updated", ACME, "active", "enterprise_monthly"))
                .andExpect(jsonPath("$.status").value("processed"));

        // meanwhile someone changes the plan; the replay must not undo it
        catalogJdbc.update("update catalog.tenants set plan = 'pro' where id = ?", ACME);
        catalog.evict(ACME);

        deliver(subscriptionEvent(eventId, "customer.subscription.updated", ACME, "active", "enterprise_monthly"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("duplicate"));

        assertThat(catalog.find(ACME).orElseThrow().plan()).isEqualTo("pro");
        assertThat(catalogJdbc.queryForObject("select count(*) from catalog.processed_stripe_events where event_id = ?",
                Long.class, eventId)).isEqualTo(1);
    }

    @Test
    void aCancelledSubscriptionDropsTheTenantToFree() throws Exception {
        deliver(subscriptionEvent("evt_" + UUID.randomUUID(), "customer.subscription.deleted", ZENITH, "canceled", "pro_monthly"))
                .andExpect(jsonPath("$.status").value("processed"));
        assertThat(catalog.find(ZENITH).orElseThrow().plan()).isEqualTo("free");
    }

    @Test
    void forgedOrStaleSignaturesAreRejected() throws Exception {
        String event = subscriptionEvent("evt_" + UUID.randomUUID(), "customer.subscription.updated", ACME, "active", "enterprise_monthly");
        long now = Instant.now().getEpochSecond();

        post(event, "t=" + now + ",v1=" + StripeSignatureVerifier.sign("whsec_wrong", now, event)).andExpect(status().isBadRequest());
        long stale = now - 3600;
        post(event, "t=" + stale + ",v1=" + StripeSignatureVerifier.sign(SECRET, stale, event)).andExpect(status().isBadRequest());
        post(event, null).andExpect(status().isBadRequest());

        assertThat(catalog.find(ACME).orElseThrow().plan()).isEqualTo("free");
    }

    private ResultActions deliver(String payload) throws Exception {
        long now = Instant.now().getEpochSecond();
        return post(payload, "t=" + now + ",v1=" + StripeSignatureVerifier.sign(SECRET, now, payload));
    }

    private ResultActions post(String payload, String signature) throws Exception {
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/webhooks/stripe")
                .contentType(MediaType.APPLICATION_JSON).content(payload);
        if (signature != null) {
            request.header("Stripe-Signature", signature);
        }
        return mvc.perform(request);
    }

    static String subscriptionEvent(String id, String type, UUID tenant, String status, String lookupKey) {
        return """
                {"id":"%s","object":"event","type":"%s","data":{"object":{
                  "id":"sub_123","object":"subscription","customer":"cus_%s","status":"%s",
                  "metadata":{"tenant_id":"%s"},
                  "items":{"data":[{"price":{"id":"price_1","lookup_key":"%s"}}]}}}}
                """.formatted(id, type, tenant.toString().substring(0, 8), status, tenant, lookupKey);
    }
}
