package dev.mahindra.tenantforge.security;

import java.nio.charset.StandardCharsets;

import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jwt.JWTParser;

import dev.mahindra.tenantforge.TenantForgeProperties;
import dev.mahindra.tenantforge.ratelimit.TokenBucketRateLimiter;
import dev.mahindra.tenantforge.tenancy.TenantCatalog;
import dev.mahindra.tenantforge.web.TenantRateLimitFilter;
import dev.mahindra.tenantforge.web.TenantResolutionFilter;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;

/**
 * Request pipeline for {@code /api/**}:
 * verify the JWT → resolve the tenant from it (and check the catalog) → spend one token from that tenant's
 * bucket → controller. Roles come from the token's {@code roles} claim.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
@EnableMethodSecurity
public class SecurityConfig {

    public static final String DEV_ISSUER = "tenantforge-dev";

    @Bean
    SecurityFilterChain api(HttpSecurity http, TenantCatalog catalog, TokenBucketRateLimiter limiter,
            ObjectProvider<Tracer> tracer, MeterRegistry meters) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers("/webhooks/stripe", "/dev/token", "/error").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(authenticationConverter())))
                .addFilterAfter(new TenantResolutionFilter(catalog, tracer), BearerTokenAuthenticationFilter.class)
                .addFilterAfter(new TenantRateLimitFilter(catalog, limiter, meters), TenantResolutionFilter.class);
        return http.build();
    }

    /**
     * Keycloak tokens (RS256, verified against the realm's published keys and issuer). In the {@code dev} profile,
     * HS256 tokens from {@code /dev/token} are accepted as well, so the API is usable without running Keycloak.
     */
    @Bean
    JwtDecoder jwtDecoder(TenantForgeProperties props) {
        TenantForgeProperties.Security security = props.security();
        NimbusJwtDecoder keycloak = NimbusJwtDecoder.withJwkSetUri(security.jwkSetUri()).build();
        keycloak.setJwtValidator(JwtValidators.createDefaultWithIssuer(security.issuer()));
        if (!StringUtils.hasText(security.devHmacSecret())) {
            return keycloak;
        }
        var key = new SecretKeySpec(security.devHmacSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        NimbusJwtDecoder dev = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        dev.setJwtValidator(JwtValidators.createDefaultWithIssuer(DEV_ISSUER));
        return token -> isHs256(token) ? dev.decode(token) : keycloak.decode(token);
    }

    private static boolean isHs256(String token) {
        try {
            return JWSAlgorithm.HS256.equals(JWTParser.parse(token).getHeader().getAlgorithm());
        } catch (java.text.ParseException e) {
            throw new BadJwtException("Malformed token", e);
        }
    }

    static JwtAuthenticationConverter authenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> TenantClaims.roles(jwt).stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .map(a -> (org.springframework.security.core.GrantedAuthority) a)
                .toList());
        return converter;
    }
}
