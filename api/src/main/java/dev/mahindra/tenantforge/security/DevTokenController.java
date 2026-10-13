package dev.mahindra.tenantforge.security;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.crypto.spec.SecretKeySpec;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

import dev.mahindra.tenantforge.TenantForgeProperties;
import dev.mahindra.tenantforge.tenancy.TenantCatalog;

/**
 * Local development only ({@code dev} profile): mints HS256 tokens so the API can be tried with curl on a laptop
 * that can't spare the memory for Keycloak. Never active in any other profile.
 */
@RestController
@Profile("dev")
class DevTokenController {

    private static final Set<String> ROLES = Set.of("tenant_admin", "tenant_member");

    private final TenantCatalog catalog;
    private final NimbusJwtEncoder encoder;
    private final Clock clock;

    DevTokenController(TenantCatalog catalog, TenantForgeProperties props, Clock clock) {
        this.catalog = catalog;
        this.clock = clock;
        byte[] secret = props.security().devHmacSecret().getBytes(StandardCharsets.UTF_8);
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(secret, "HmacSHA256")));
    }

    @PostMapping("/dev/token")
    Map<String, Object> token(@RequestParam String tenant, @RequestParam(defaultValue = "tenant_admin") String role) {
        if (!ROLES.contains(role)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "role must be one of " + ROLES);
        }
        var t = catalog.findBySlug(tenant)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No tenant with slug " + tenant));
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(SecurityConfig.DEV_ISSUER)
                .subject(role + "@" + t.slug())
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofHours(1)))
                .claim(TenantClaims.TENANT, t.id().toString())
                .claim(TenantClaims.ROLES, List.of(role))
                .build();
        String jwt = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return Map.of("access_token", jwt, "token_type", "Bearer", "expires_in", 3600, "tenant", t.slug());
    }
}
