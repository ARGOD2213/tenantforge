package dev.mahindra.tenantforge.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import dev.mahindra.tenantforge.IntegrationTest;
import dev.mahindra.tenantforge.tenancy.Tenant;

class ApiIT extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void usersOnlySeeTheirTenantsInvoices() throws Exception {
        create(ACME, "ACME-1");
        create(ZENITH, "ZEN-1");

        mvc.perform(get("/api/invoices").with(user(ACME, "tenant_member")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].number").value("ACME-1"));
    }

    @Test
    void anotherTenantsInvoiceIdIsA404() throws Exception {
        String body = create(ZENITH, "ZEN-2");
        String id = com.jayway.jsonpath.JsonPath.read(body, "$.id");
        mvc.perform(get("/api/invoices/" + id).with(user(ACME, "tenant_admin"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/invoices/" + id).with(user(ZENITH, "tenant_admin"))).andExpect(status().isOk());
    }

    @Test
    void duplicateInvoiceNumbersConflictOnlyWithinATenant() throws Exception {
        create(ACME, "SAME-1");
        create(ZENITH, "SAME-1"); // fine: different tenant
        mvc.perform(post("/api/invoices").with(user(ACME, "tenant_member")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"number\":\"SAME-1\",\"customer\":\"x\",\"amountCents\":1}"))
                .andExpect(status().isConflict());
    }

    @Test
    void meReportsTenantPlanAndLimits() throws Exception {
        mvc.perform(get("/api/me").with(user(ZENITH, "tenant_admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant.slug").value("zenith"))
                .andExpect(jsonPath("$.tenant.plan").value("pro"))
                .andExpect(jsonPath("$.limits.burst").value(100))
                .andExpect(jsonPath("$.entitlements.exports").value(true));
    }

    @Test
    void requestsWithoutATokenAreUnauthorized() throws Exception {
        mvc.perform(get("/api/invoices")).andExpect(status().isUnauthorized());
    }

    @Test
    void aTokenWithoutExactlyOneTenantIsForbidden() throws Exception {
        mvc.perform(get("/api/invoices").with(jwt().jwt(j -> j.claim("roles", List.of("tenant_admin")))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("no_tenant"));
        mvc.perform(get("/api/invoices").with(jwt().jwt(j -> j.claim("tenant_id", List.of(ACME.toString(), ZENITH.toString())))))
                .andExpect(status().isForbidden());
    }

    @Test
    void suspendedOrUnknownTenantsAreForbidden() throws Exception {
        catalog.upsert(new Tenant(ACME, "acme", "Acme Corp", "free", Tenant.Tier.POOLED, null, null, null, null, "suspended"));
        mvc.perform(get("/api/invoices").with(user(ACME, "tenant_admin")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("tenant_unavailable"));
        mvc.perform(get("/api/invoices").with(user(UUID.randomUUID(), "tenant_admin"))).andExpect(status().isForbidden());
    }

    @Test
    void exportsNeedTheAdminRoleAndAPlanThatIncludesThem() throws Exception {
        mvc.perform(post("/api/exports").with(user(ZENITH, "tenant_member"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/exports").with(user(ACME, "tenant_admin")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("'free' plan")));
    }

    @Test
    void responsesCarryRateLimitHeaders() throws Exception {
        mvc.perform(get("/api/invoices").with(user(ZENITH, "tenant_member")))
                .andExpect(header().string("X-RateLimit-Limit", "100"))
                .andExpect(header().exists("X-RateLimit-Remaining"));
    }

    private String create(UUID tenant, String number) throws Exception {
        return mvc.perform(post("/api/invoices").with(user(tenant, "tenant_member")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"number\":\"" + number + "\",\"customer\":\"Customer\",\"amountCents\":15000,\"currency\":\"INR\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }
}
