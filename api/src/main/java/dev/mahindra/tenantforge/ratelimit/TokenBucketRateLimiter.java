package dev.mahindra.tenantforge.ratelimit;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import dev.mahindra.tenantforge.tenancy.Plan;

/**
 * One token bucket per tenant in Redis ({@code tf:{tenantId}:ratelimit}), sized by the tenant's plan.
 * A tenant that floods the API drains only its own bucket; everyone else keeps their full allowance.
 *
 * <p>If Redis is unreachable the limiter fails open and logs: a cache outage should not take the API down.
 */
@Component
public class TokenBucketRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(TokenBucketRateLimiter.class);

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> SCRIPT = RedisScript.of(new ClassPathResource("scripts/token_bucket.lua"), List.class);

    private final StringRedisTemplate redis;

    public TokenBucketRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @SuppressWarnings("unchecked")
    public Decision tryAcquire(UUID tenantId, Plan plan) {
        try {
            List<Object> result = redis.execute(SCRIPT, List.of(key(tenantId)),
                    String.valueOf(plan.ratePerSecond()), String.valueOf(plan.burst()), "1");
            return new Decision(toLong(result.get(0)) == 1L, toLong(result.get(1)), toLong(result.get(2)));
        } catch (DataAccessException e) {
            log.warn("Rate limiter unavailable, allowing request for tenant {}: {}", tenantId, e.getMessage());
            return new Decision(true, 0, plan.burst());
        }
    }

    public static String key(UUID tenantId) {
        return "tf:" + tenantId + ":ratelimit";
    }

    private static long toLong(Object value) {
        return value instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(value));
    }

    public record Decision(boolean allowed, long retryAfterMillis, long remaining) {
        /** Retry-After is whole seconds; never tell a client "0". */
        public long retryAfterSeconds() {
            return Math.max(1, (retryAfterMillis + 999) / 1000);
        }
    }
}
