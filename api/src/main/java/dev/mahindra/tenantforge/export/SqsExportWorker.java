package dev.mahindra.tenantforge.export;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.mahindra.tenantforge.TenantForgeProperties;
import dev.mahindra.tenantforge.tenancy.TenantContext;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

/**
 * Long-polls the export queue. Deployed as its own Deployment (same image, {@code worker} profile) and scaled by
 * KEDA on queue depth, down to zero replicas when the queue is empty.
 *
 * <p>Delivery is at-least-once: a message is deleted only after its export is stored. A message that keeps failing is
 * marked failed after {@code tenantforge.worker.max-receives} attempts (the queue's redrive policy is the backstop).
 */
@Component
@ConditionalOnProperty(name = "tenantforge.worker.enabled", havingValue = "true")
public class SqsExportWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SqsExportWorker.class);

    private final SqsClient sqs;
    private final ObjectMapper json;
    private final ExportProcessor processor;
    private final String queueUrl;
    private final int maxReceives;

    private volatile boolean running;
    private Thread thread;

    public SqsExportWorker(SqsClient sqs, ObjectMapper json, ExportProcessor processor, TenantForgeProperties props) {
        this.sqs = sqs;
        this.json = json;
        this.processor = processor;
        this.queueUrl = props.aws().exportQueueUrl();
        this.maxReceives = props.worker().maxReceives();
    }

    @Override
    public void start() {
        running = true;
        thread = Thread.ofPlatform().name("export-worker").start(this::loop);
        log.info("Export worker polling {}", queueUrl);
    }

    @Override
    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(15_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void loop() {
        while (running) {
            try {
                List<Message> messages = sqs.receiveMessage(ReceiveMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .maxNumberOfMessages(5)
                        .waitTimeSeconds(10)
                        .messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT)
                        .build()).messages();
                messages.forEach(this::handle);
            } catch (SdkException e) {
                if (!running) {
                    return;
                }
                log.warn("Polling the export queue failed: {}", e.getMessage());
                sleep(2_000);
            }
        }
    }

    void handle(Message message) {
        ExportQueue.ExportMessage body;
        try {
            body = json.readValue(message.body(), ExportQueue.ExportMessage.class);
        } catch (Exception e) {
            log.error("Dropping malformed export message {}", message.messageId());
            delete(message);
            return;
        }
        int receives = Integer.parseInt(message.attributes().getOrDefault(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT, "1"));
        TenantContext.runAs(body.tenantId(), () -> {
            MDC.put("tenant_id", body.tenantId().toString());
            try {
                processor.run(body.exportId());
                delete(message);
            } catch (RuntimeException e) {
                log.warn("Export {} failed (attempt {}): {}", body.exportId(), receives, e.getMessage());
                if (receives >= maxReceives) {
                    processor.markFailed(body.exportId(), e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                    delete(message);
                }
                // otherwise leave it: it becomes visible again after the visibility timeout and is retried
            } finally {
                MDC.remove("tenant_id");
            }
        });
    }

    private void delete(Message message) {
        sqs.deleteMessage(r -> r.queueUrl(queueUrl).receiptHandle(message.receiptHandle()));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
