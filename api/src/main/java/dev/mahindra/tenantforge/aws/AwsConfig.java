package dev.mahindra.tenantforge.aws;

import java.net.URI;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import dev.mahindra.tenantforge.TenantForgeProperties;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * AWS clients. In EKS credentials come from the pod's IAM role (IRSA) through the default provider chain;
 * locally an endpoint override points everything at LocalStack.
 */
@Configuration(proxyBeanMethods = false)
public class AwsConfig {

    @Bean(destroyMethod = "close")
    SqsClient sqsClient(TenantForgeProperties props) {
        var aws = props.aws();
        var builder = SqsClient.builder().region(Region.of(aws.region()));
        if (StringUtils.hasText(aws.endpoint())) {
            builder.endpointOverride(URI.create(aws.endpoint()));
        }
        return builder.build();
    }

    @Bean(destroyMethod = "close")
    S3Client s3Client(TenantForgeProperties props) {
        var aws = props.aws();
        var builder = S3Client.builder().region(Region.of(aws.region()));
        if (StringUtils.hasText(aws.endpoint())) {
            builder.endpointOverride(URI.create(aws.endpoint())).forcePathStyle(true);
        }
        return builder.build();
    }

    @Bean(destroyMethod = "close")
    S3Presigner s3Presigner(TenantForgeProperties props) {
        var aws = props.aws();
        var builder = S3Presigner.builder().region(Region.of(aws.region()));
        if (StringUtils.hasText(aws.endpoint())) {
            builder.endpointOverride(URI.create(aws.endpoint()))
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
        }
        return builder.build();
    }
}
