package dev.mahindra.tenantforge.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import dev.mahindra.tenantforge.IntegrationTest;

/** One tenant flooding the API is throttled; other tenants keep their full allowance. */
class RateLimitIT extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void aFloodingTenantGets429WithRetryAfterAndOthersAreUnaffected() throws Exception {
        // acme is on "free": 5 requests/second, burst 10
        int allowed = 0;
        MockHttpServletResponse throttled = null;
        for (int i = 0; i < 40 && throttled == null; i++) {
            MockHttpServletResponse r = mvc.perform(get("/api/invoices").with(user(ACME, "tenant_member"))).andReturn().getResponse();
            if (r.getStatus() == 429) {
                throttled = r;
            } else {
                assertThat(r.getStatus()).isEqualTo(200);
                allowed++;
            }
        }

        assertThat(throttled).as("acme should be throttled").isNotNull();
        assertThat(allowed).isBetween(10, 20); // burst of 10 plus whatever refilled while the loop ran
        assertThat(Integer.parseInt(throttled.getHeader("Retry-After"))).isPositive();
        assertThat(throttled.getContentAsString()).contains("rate_limited");

        // zenith shares nothing with acme's bucket
        MockHttpServletResponse other = mvc.perform(get("/api/invoices").with(user(ZENITH, "tenant_member"))).andReturn().getResponse();
        assertThat(other.getStatus()).isEqualTo(200);
        assertThat(redis.hasKey(TokenBucketRateLimiter.key(ACME))).isTrue();
        assertThat(redis.hasKey(TokenBucketRateLimiter.key(ZENITH))).isTrue();
    }
}
