package dev.mahindra.tenantforge.security;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Reads tenant and roles from an access token.
 *
 * <p>Keycloak puts the tenant in {@code tenant_id} through a group-membership mapper: every user belongs to
 * exactly one group under {@code /tenants}, named after the tenant's id, so the claim arrives as a one-element
 * list. Dev tokens use a plain string. Both are accepted; anything else (no tenant, two tenants) is rejected.
 */
public final class TenantClaims {

    public static final String TENANT = "tenant_id";
    public static final String ROLES = "roles";

    private TenantClaims() {
    }

    public static Optional<UUID> tenantId(Jwt jwt) {
        Object value = jwt.getClaims().get(TENANT);
        Object single = value instanceof Collection<?> c ? (c.size() == 1 ? c.iterator().next() : null) : value;
        if (single == null) {
            return Optional.empty();
        }
        String text = single.toString();
        // group-membership mappers may emit a path like "/tenants/<uuid>"
        text = text.substring(text.lastIndexOf('/') + 1);
        try {
            return Optional.of(UUID.fromString(text));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public static List<String> roles(Jwt jwt) {
        Object value = jwt.getClaims().get(ROLES);
        if (value instanceof Collection<?> c) {
            return c.stream().map(Object::toString).toList();
        }
        return value == null ? List.of() : List.of(value.toString());
    }
}
