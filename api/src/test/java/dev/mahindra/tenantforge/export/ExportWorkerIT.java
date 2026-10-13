package dev.mahindra.tenantforge.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;

import com.jayway.jsonpath.JsonPath;

import dev.mahindra.tenantforge.IntegrationTest;
import dev.mahindra.tenantforge.aws.TenantFileStorage;
import dev.mahindra.tenantforge.invoice.Invoice;
import dev.mahindra.tenantforge.invoice.InvoiceRepository;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * The request only queues the export (202); a worker on SQS builds the CSV with the tenant bound and writes it under
 * the tenant's S3 prefix. LocalStack stands in for SQS and S3.
 */
@TestPropertySource(properties = {"tenantforge.worker.enabled=true", "tenantforge.aws.s3-sse=none"})
class ExportWorkerIT extends IntegrationTest {

    static final LocalStackContainer LOCALSTACK = new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.8"))
            .withServices(LocalStackContainer.Service.SQS, LocalStackContainer.Service.S3);
    static final String BUCKET = "tenantforge-files";
    static String queueUrl;

    static {
        LOCALSTACK.start();
        System.setProperty("aws.accessKeyId", LOCALSTACK.getAccessKey());
        System.setProperty("aws.secretAccessKey", LOCALSTACK.getSecretKey());
        try (SqsClient sqs = sqs(); S3Client s3 = s3()) {
            queueUrl = sqs.createQueue(r -> r.queueName("tenantforge-exports")).queueUrl();
            s3.createBucket(r -> r.bucket(BUCKET));
        }
    }

    @DynamicPropertySource
    static void aws(DynamicPropertyRegistry registry) {
        registry.add("tenantforge.aws.endpoint", () -> LOCALSTACK.getEndpoint().toString());
        registry.add("tenantforge.aws.region", LOCALSTACK::getRegion);
        registry.add("tenantforge.aws.export-queue-url", () -> queueUrl);
        registry.add("tenantforge.aws.bucket", () -> BUCKET);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    InvoiceRepository invoices;

    @Autowired
    ExportRepository exports;

    @Test
    void anExportRunsOffTheRequestPathAndContainsOnlyThatTenantsRows() throws Exception {
        asTenant(ZENITH, () -> invoices.saveAll(List.of(
                new Invoice("ZEN-1", "Harbor Analytics", 42_000, "USD"),
                new Invoice("ZEN-2", "Comma, Quoted \"Inc\"", 1_050, "USD"))));
        asTenant(ACME, () -> invoices.save(new Invoice("ACME-1", "Sunrise Bakery", 125_000, "INR")));

        String body = mvc.perform(post("/api/exports").with(user(ZENITH, "tenant_admin")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("queued"))
                .andReturn().getResponse().getContentAsString();
        UUID exportId = UUID.fromString(JsonPath.read(body, "$.id"));

        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(500))
                .until(() -> "done".equals(asTenant(ZENITH, () -> exports.findById(exportId).orElseThrow().getStatus())));

        String key = TenantFileStorage.exportKey(ZENITH, exportId);
        assertThat(key).startsWith("tenants/" + ZENITH + "/");
        String csv;
        try (S3Client s3 = s3()) {
            csv = s3.getObjectAsBytes(r -> r.bucket(BUCKET).key(key)).asString(StandardCharsets.UTF_8);
        }
        assertThat(csv).contains("ZEN-1,Harbor Analytics,420.00,USD")
                .contains("\"Comma, Quoted \"\"Inc\"\"\"")
                .doesNotContain("ACME-1");

        mvc.perform(get("/api/exports/" + exportId).with(user(ZENITH, "tenant_member")))
                .andExpect(jsonPath("$.status").value("done"))
                .andExpect(jsonPath("$.rows").value(2))
                .andExpect(jsonPath("$.downloadUrl").isString());
        // and acme can't even see that the export exists
        mvc.perform(get("/api/exports/" + exportId).with(user(ACME, "tenant_admin"))).andExpect(status().isNotFound());
    }

    static SqsClient sqs() {
        return SqsClient.builder().endpointOverride(URI.create(LOCALSTACK.getEndpoint().toString()))
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .build();
    }

    static S3Client s3() {
        return S3Client.builder().endpointOverride(URI.create(LOCALSTACK.getEndpoint().toString())).forcePathStyle(true)
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .build();
    }
}
