package dev.mahindra.tenantforge.tenancy;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.mahindra.tenantforge.TenantForgeProperties;

/**
 * The tenant catalog: one row per tenant saying which plan they're on and where their data lives.
 *
 * <p>Read on every request, so it is cached twice: briefly in memory on each instance, and as a shared
 * "hot config" copy in Redis under {@code tf:{tenantId}:config}. The database stays the source of truth.
 * Writes (plan changes from Stripe) evict both copies.
 */
@Component
public class TenantCatalog {

    private static final Logger log = LoggerFactory.getLogger(TenantCatalog.class);
    private static final String COLUMNS =
            "id, slug, name, plan, tier, db_host, db_port, db_name, stripe_customer_id, status";

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final TenantForgeProperties.Cache ttl;
    private final Clock clock;

    private final Map<UUID, Cached> local = new ConcurrentHashMap<>();
    private volatile Map<String, Plan> plans = Map.of();
    private volatile Instant plansLoadedAt = Instant.EPOCH;

    public TenantCatalog(@Qualifier("catalogJdbc") JdbcTemplate jdbc, StringRedisTemplate redis, ObjectMapper json,
            TenantForgeProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.json = json;
        this.ttl = props.cache();
        this.clock = clock;
    }

    public Optional<Tenant> find(UUID id) {
        Instant now = clock.instant();
        Cached hit = local.get(id);
        if (hit != null && hit.expiresAt().isAfter(now)) {
            return Optional.of(hit.tenant());
        }
        Optional<Tenant> tenant = fromRedis(id).or(() -> fromDatabase(id));
        tenant.ifPresent(t -> local.put(id, new Cached(t, now.plus(ttl.localTtl()))));
        return tenant;
    }

    public Optional<Tenant> findBySlug(String slug) {
        return jdbc.query("select " + COLUMNS + " from catalog.tenants where slug = ?", this::map, slug).stream().findFirst();
    }

    public Optional<Tenant> findByStripeCustomer(String customerId) {
        return jdbc.query("select " + COLUMNS + " from catalog.tenants where stripe_customer_id = ?", this::map, customerId)
                .stream().findFirst();
    }

    public List<Tenant> silos() {
        return jdbc.query("select " + COLUMNS + " from catalog.tenants where tier = 'SILO'", this::map);
    }

    public Plan plan(String code) {
        if (plansLoadedAt.plusSeconds(60).isBefore(clock.instant()) || !plans.containsKey(code)) {
            plans = jdbc.query("select code, rate_per_second, burst, exports_enabled from catalog.plans",
                            (rs, i) -> new Plan(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getBoolean(4)))
                    .stream().collect(Collectors.toUnmodifiableMap(Plan::code, Function.identity()));
            plansLoadedAt = clock.instant();
        }
        Plan plan = plans.get(code);
        if (plan == null) {
            throw new IllegalStateException("Unknown plan " + code);
        }
        return plan;
    }

    /** Called inside the webhook transaction; the caller evicts after commit. */
    public int updatePlan(UUID tenantId, String plan, String stripeCustomerId) {
        return jdbc.update("""
                update catalog.tenants
                   set plan = ?, stripe_customer_id = coalesce(?, stripe_customer_id), updated_at = now()
                 where id = ?""", plan, stripeCustomerId, tenantId);
    }

    /** Inserts or updates a tenant (onboarding, demo data, tests). */
    public void upsert(Tenant t) {
        jdbc.update("""
                insert into catalog.tenants (id, slug, name, plan, tier, db_host, db_port, db_name, stripe_customer_id, status)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (id) do update set
                    slug = excluded.slug, name = excluded.name, plan = excluded.plan, tier = excluded.tier,
                    db_host = excluded.db_host, db_port = excluded.db_port, db_name = excluded.db_name,
                    stripe_customer_id = excluded.stripe_customer_id, status = excluded.status, updated_at = now()""",
                t.id(), t.slug(), t.name(), t.plan(), t.tier().name(), t.dbHost(), t.dbPort(), t.dbName(),
                t.stripeCustomerId(), t.status());
        evict(t.id());
    }

    /** Onboarding / demo data: creates the tenant only if it doesn't exist (never overwrites a Stripe plan). */
    public boolean insertIfAbsent(Tenant t) {
        return jdbc.update("""
                insert into catalog.tenants (id, slug, name, plan, tier, db_host, db_port, db_name, stripe_customer_id, status)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict do nothing""",
                t.id(), t.slug(), t.name(), t.plan(), t.tier().name(), t.dbHost(), t.dbPort(), t.dbName(),
                t.stripeCustomerId(), t.status()) == 1;
    }

    public void evict(UUID tenantId) {
        local.remove(tenantId);
        try {
            redis.delete(key(tenantId));
        } catch (DataAccessException e) {
            log.warn("Could not evict tenant {} from Redis: {}", tenantId, e.getMessage());
        }
    }

    private Optional<Tenant> fromRedis(UUID id) {
        try {
            String cached = redis.opsForValue().get(key(id));
            return cached == null ? Optional.empty() : Optional.of(json.readValue(cached, Tenant.class));
        } catch (DataAccessException | JsonProcessingException e) {
            log.debug("Tenant cache miss for {} ({})", id, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<Tenant> fromDatabase(UUID id) {
        Optional<Tenant> tenant = jdbc.query("select " + COLUMNS + " from catalog.tenants where id = ?", this::map, id)
                .stream().findFirst();
        tenant.ifPresent(t -> {
            try {
                redis.opsForValue().set(key(id), json.writeValueAsString(t), ttl.redisTtl());
            } catch (DataAccessException | JsonProcessingException e) {
                log.debug("Could not cache tenant {} in Redis ({})", id, e.getMessage());
            }
        });
        return tenant;
    }

    private Tenant map(ResultSet rs, int row) throws SQLException {
        int rawPort = rs.getInt("db_port");
        Integer port = rs.wasNull() ? null : rawPort;
        return new Tenant(
                rs.getObject("id", UUID.class),
                rs.getString("slug"),
                rs.getString("name"),
                rs.getString("plan"),
                Tenant.Tier.valueOf(rs.getString("tier")),
                rs.getString("db_host"),
                port,
                rs.getString("db_name"),
                rs.getString("stripe_customer_id"),
                rs.getString("status"));
    }

    static String key(UUID tenantId) {
        return "tf:" + tenantId + ":config";
    }

    private record Cached(Tenant tenant, Instant expiresAt) {
    }
}
