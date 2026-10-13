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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;

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

    @Bean
    JwtDecoder jwtDecoder(TenantForgeProperties props) {
        TenantForgeProperties.Security security = props.security();
        if (StringUtils.hasText(security.devHmacSecret())) {
            var key = new SecretKeySpec(security.devHmacSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
            decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(DEV_ISSUER));
            return decoder;
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(security.jwkSetUri()).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(security.issuer()));
        return decoder;
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
