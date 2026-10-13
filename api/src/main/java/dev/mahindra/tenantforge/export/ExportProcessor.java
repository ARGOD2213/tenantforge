package dev.mahindra.tenantforge.export;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import dev.mahindra.tenantforge.aws.TenantFileStorage;
import dev.mahindra.tenantforge.invoice.Invoice;
import dev.mahindra.tenantforge.invoice.InvoiceRepository;
import dev.mahindra.tenantforge.tenancy.TenantContext;

/**
 * Builds a tenant's invoice CSV and stores it under the tenant's S3 prefix. Runs on a worker with the tenant bound
 * from the queue message, so the same RLS rules apply as in the API: the query has no tenant filter and still only
 * ever sees this tenant's rows.
 */
@Component
public class ExportProcessor {

    private final ExportRepository exports;
    private final InvoiceRepository invoices;
    private final TenantFileStorage storage;

    public ExportProcessor(ExportRepository exports, InvoiceRepository invoices, TenantFileStorage storage) {
        this.exports = exports;
        this.invoices = invoices;
        this.storage = storage;
    }

    @Transactional
    public void run(UUID exportId) {
        ExportJob job = exports.findById(exportId)
                .orElseThrow(() -> new IllegalStateException("Export " + exportId + " is not visible to this tenant"));
        if ("done".equals(job.getStatus())) {
            return; // SQS is at-least-once: a redelivered message is a no-op
        }
        job.running();
        StringBuilder csv = new StringBuilder("number,customer,amount,currency,status,issued_on,due_on\n");
        AtomicInteger rows = new AtomicInteger();
        try (Stream<Invoice> stream = invoices.streamAllByOrderByIssuedOnAscNumberAsc()) {
            stream.forEach(i -> {
                csv.append(cell(i.getNumber())).append(',')
                        .append(cell(i.getCustomer())).append(',')
                        .append(String.format("%d.%02d", i.getAmountCents() / 100, i.getAmountCents() % 100)).append(',')
                        .append(i.getCurrency()).append(',')
                        .append(i.getStatus()).append(',')
                        .append(i.getIssuedOn()).append(',')
                        .append(i.getDueOn() == null ? "" : i.getDueOn().toString()).append('\n');
                rows.incrementAndGet();
            });
        }
        String key = TenantFileStorage.exportKey(TenantContext.require(), exportId);
        storage.put(key, csv.toString().getBytes(StandardCharsets.UTF_8), "text/csv");
        job.done(key, rows.get());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID exportId, String reason) {
        exports.findById(exportId).ifPresent(job -> job.failed(reason.length() > 500 ? reason.substring(0, 500) : reason));
    }

    private static String cell(String value) {
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
