package dev.mahindra.tenantforge;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import dev.mahindra.tenantforge.tenancy.Tenant;
import dev.mahindra.tenantforge.tenancy.TenantCatalog;
import dev.mahindra.tenantforge.tenancy.TenantContext;

/**
 * Real Postgres 16 and Redis 7 in containers, shared by every integration test.
 *
 * <p>Tenants: acme (pooled, free), zenith (pooled, pro), orbit (SILO in database silo_orbit, enterprise).
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class IntegrationTest {

    public static final UUID ACME = UUID.fromString("11111111-1111-1111-1111-111111111111");
    public static final UUID ZENITH = UUID.fromString("22222222-2222-2222-2222-222222222222");
    public static final UUID ORBIT = UUID.fromString("33333333-3333-3333-3333-333333333333");

    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("tenantforge")
            .withUsername("test")
            .withPassword("test")
            .withInitScript("db/test-init.sql");

    protected static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        registry.add("tenantforge.db.url", POSTGRES::getJdbcUrl);
        registry.add("tenantforge.db.app-username", () -> "tf_app");
        registry.add("tenantforge.db.app-password", () -> "app");
        registry.add("tenantforge.db.owner-username", () -> "tf_owner");
        registry.add("tenantforge.db.owner-password", () -> "owner");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("tenantforge.stripe.webhook-secret", () -> "whsec_test_secret");
    }

    @Autowired
    protected TenantCatalog catalog;

    @Autowired
    protected StringRedisTemplate redis;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected PlatformTransactionManager transactionManager;

    @BeforeEach
    void resetTenants() {
        redis.execute((RedisCallback<Void>) (RedisConnection c) -> {
            c.serverCommands().flushAll();
            return null;
        });
        catalog.upsert(new Tenant(ACME, "acme", "Acme Corp", "free", Tenant.Tier.POOLED, null, null, null, null, "active"));
        catalog.upsert(new Tenant(ZENITH, "zenith", "Zenith Labs", "pro", Tenant.Tier.POOLED, null, null, null, null, "active"));
        catalog.upsert(new Tenant(ORBIT, "orbit", "Orbit Logistics", "enterprise", Tenant.Tier.SILO, null, null, "silo_orbit", null, "active"));
        for (UUID tenant : List.of(ACME, ZENITH, ORBIT)) {
            asTenant(tenant, () -> {
                jdbc.update("delete from app.exports");
                return jdbc.update("delete from app.invoices");
            });
        }
    }

    /** Runs {@code work} in a transaction with {@code tenant} bound, exactly like a request would. */
    protected <T> T asTenant(UUID tenant, Supplier<T> work) {
        return TenantContext.callAs(tenant, () -> new TransactionTemplate(transactionManager).execute(s -> work.get()));
    }

    /** A verified-JWT stand-in for MockMvc: tenant_id and roles claims plus matching authorities. */
    protected static RequestPostProcessor user(UUID tenant, String role) {
        return SecurityMockMvcRequestPostProcessors.jwt()
                .jwt(j -> j.subject(role + "@" + tenant).claim("tenant_id", tenant.toString()).claim("roles", List.of(role)))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
