package dev.mahindra.tenantforge.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.mahindra.tenantforge.IntegrationTest;

/**
 * End to end with a real Keycloak and the realm shipped in deploy/keycloak: a user logs in, the access token carries
 * tenant_id and roles, and the API (verifying with Keycloak's keys) scopes the request to that tenant.
 */
class KeycloakRealmIT extends IntegrationTest {

    static final GenericContainer<?> KEYCLOAK = new GenericContainer<>("quay.io/keycloak/keycloak:26.0")
            .withExposedPorts(8080)
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
            .withCopyFileToContainer(MountableFile.forHostPath("../deploy/keycloak/tenantforge-realm.json"),
                    "/opt/keycloak/data/import/tenantforge-realm.json")
            .withCommand("start-dev", "--import-realm")
            .waitingFor(Wait.forHttp("/realms/tenantforge").forPort(8080).withStartupTimeout(Duration.ofMinutes(4)));

    static {
        KEYCLOAK.start();
    }

    static String realmUrl() {
        return "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080) + "/realms/tenantforge";
    }

    @DynamicPropertySource
    static void keycloak(DynamicPropertyRegistry registry) {
        registry.add("tenantforge.security.issuer", KeycloakRealmIT::realmUrl);
        registry.add("tenantforge.security.jwk-set-uri", () -> realmUrl() + "/protocol/openid-connect/certs");
    }

    @Autowired
    MockMvc mvc;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void aKeycloakTokenCarriesTenantAndRolesAndTheApiScopesToIt() throws Exception {
        String token = login("alice", "demo");
        JsonNode claims = json.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        assertThat(claims.path("tenant_id").toString()).contains(ACME.toString());
        assertThat(claims.path("roles").toString()).contains("tenant_admin");

        mvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant.slug").value("acme"))
                .andExpect(jsonPath("$.roles[0]").value("tenant_admin"));
    }

    @Test
    void aMemberTokenCannotUseAdminOnlyEndpoints() throws Exception {
        String token = login("bob", "demo");
        mvc.perform(post("/api/exports").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        mvc.perform(get("/api/invoices").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
    }

    @Test
    void aTamperedTokenIsRejected() throws Exception {
        String[] parts = login("alice", "demo").split("\\.");
        String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"tenant_id\":[\"" + ZENITH + "\"],\"roles\":" + json.writeValueAsString(List.of("tenant_admin")) + "}")
                        .getBytes(StandardCharsets.UTF_8));
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + parts[0] + "." + forgedPayload + "." + parts[2]))
                .andExpect(status().isUnauthorized());
    }

    private String login(String username, String password) throws Exception {
        String form = form(Map.of("grant_type", "password", "client_id", "tenantforge-cli",
                "username", username, "password", password, "scope", "openid"));
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(realmUrl() + "/protocol/openid-connect/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body()).path("access_token").asText();
    }

    private static String form(Map<String, String> values) {
        return values.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .reduce((a, b) -> a + "&" + b).orElse("");
    }
}
