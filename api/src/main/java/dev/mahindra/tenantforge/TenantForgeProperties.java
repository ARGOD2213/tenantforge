package dev.mahindra.tenantforge;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Everything TenantForge-specific lives under {@code tenantforge.*}.
 */
@ConfigurationProperties("tenantforge")
public record TenantForgeProperties(
        Db db,
        Security security,
        Aws aws,
        Stripe stripe,
        Worker worker,
        Cache cache,
        @DefaultValue("false") boolean demoData) {

    /**
     * @param url           JDBC URL of the pooled database (shared by pooled tenants, also holds the catalog)
     * @param appUsername   runtime role: no BYPASSRLS, does not own the tables, so RLS always applies
     * @param ownerUsername migration role: owns schemas and tables, used only by Flyway
     */
    public record Db(
            String url,
            String appUsername,
            String appPassword,
            String ownerUsername,
            String ownerPassword,
            @DefaultValue("10") int poolSize,
            @DefaultValue("4") int siloPoolSize) {
    }

    /**
     * @param issuer        expected {@code iss} of access tokens (Keycloak realm URL as the browser sees it)
     * @param jwkSetUri     where to fetch signing keys (Keycloak certs URL as the API can reach it)
     * @param devHmacSecret when set, the API trusts HS256 tokens minted by {@code /dev/token} instead of Keycloak
     */
    public record Security(String issuer, String jwkSetUri, String devHmacSecret) {
    }

    /**
     * @param endpoint  optional endpoint override (LocalStack); empty in AWS
     * @param s3Sse     server-side encryption for tenant files: aws:kms, AES256 or none
     * @param kmsKeyId  KMS key for aws:kms (optional: AWS default key when empty)
     */
    public record Aws(
            @DefaultValue("ap-south-1") String region,
            String endpoint,
            String exportQueueUrl,
            String bucket,
            @DefaultValue("aws:kms") String s3Sse,
            String kmsKeyId,
            @DefaultValue("15m") Duration presignTtl) {
    }

    /**
     * @param webhookSecret the {@code whsec_...} signing secret of the webhook endpoint
     * @param pricePlans    Stripe price lookup_key → TenantForge plan code
     */
    public record Stripe(String webhookSecret, @DefaultValue("300s") Duration tolerance, Map<String, String> pricePlans) {
        public Stripe {
            pricePlans = pricePlans == null ? Map.of() : Map.copyOf(pricePlans);
        }
    }

    public record Worker(@DefaultValue("false") boolean enabled, @DefaultValue("3") int maxReceives) {
    }

    /**
     * @param localTtl how long an instance keeps a tenant's config in memory
     * @param redisTtl how long the shared hot copy lives in Redis
     */
    public record Cache(@DefaultValue("30s") Duration localTtl, @DefaultValue("5m") Duration redisTtl) {
    }
}
