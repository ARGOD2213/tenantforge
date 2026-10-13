package dev.mahindra.tenantforge.export;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.mahindra.tenantforge.TenantForgeProperties;

import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/**
 * Hands slow work to SQS so it leaves the request path. Messages are sent only after the transaction that created
 * the job commits; a worker never picks up a job whose row doesn't exist yet.
 */
@Component
public class ExportQueue {

    private final SqsClient sqs;
    private final ObjectMapper json;
    private final String queueUrl;

    public ExportQueue(SqsClient sqs, ObjectMapper json, TenantForgeProperties props) {
        this.sqs = sqs;
        this.json = json;
        this.queueUrl = props.aws().exportQueueUrl();
    }

    public void sendAfterCommit(ExportMessage message) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send(message);
                }
            });
        } else {
            send(message);
        }
    }

    void send(ExportMessage message) {
        try {
            sqs.sendMessage(SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(json.writeValueAsString(message))
                    .messageAttributes(Map.of("tenant_id", MessageAttributeValue.builder()
                            .dataType("String").stringValue(message.tenantId().toString()).build()))
                    .build());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public record ExportMessage(UUID exportId, UUID tenantId, String kind) {
    }
}
