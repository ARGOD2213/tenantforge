package dev.mahindra.tenantforge.aws;

import java.net.URL;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import dev.mahindra.tenantforge.TenantForgeProperties;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/**
 * Tenant files in S3. Every key starts with {@code tenants/{tenantId}/}, so an IAM policy or a lifecycle rule can
 * target one tenant, and objects are encrypted with KMS in AWS.
 */
@Component
public class TenantFileStorage {

    private final S3Client s3;
    private final S3Presigner presigner;
    private final TenantForgeProperties.Aws aws;

    public TenantFileStorage(S3Client s3, S3Presigner presigner, TenantForgeProperties props) {
        this.s3 = s3;
        this.presigner = presigner;
        this.aws = props.aws();
    }

    public static String exportKey(UUID tenantId, UUID exportId) {
        return "tenants/" + tenantId + "/exports/" + exportId + ".csv";
    }

    public void put(String key, byte[] content, String contentType) {
        PutObjectRequest.Builder request = PutObjectRequest.builder()
                .bucket(aws.bucket())
                .key(key)
                .contentType(contentType);
        switch (aws.s3Sse()) {
            case "aws:kms" -> {
                request.serverSideEncryption(ServerSideEncryption.AWS_KMS);
                if (StringUtils.hasText(aws.kmsKeyId())) {
                    request.ssekmsKeyId(aws.kmsKeyId());
                }
            }
            case "AES256" -> request.serverSideEncryption(ServerSideEncryption.AES256);
            default -> {
                // "none": LocalStack or a bucket with default encryption
            }
        }
        s3.putObject(request.build(), RequestBody.fromBytes(content));
    }

    public URL downloadUrl(String key) {
        return presigner.presignGetObject(GetObjectPresignRequest.builder()
                        .signatureDuration(aws.presignTtl())
                        .getObjectRequest(r -> r.bucket(aws.bucket()).key(key))
                        .build())
                .url();
    }
}
