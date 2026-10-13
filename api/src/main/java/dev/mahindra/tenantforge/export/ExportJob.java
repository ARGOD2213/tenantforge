package dev.mahindra.tenantforge.export;

import java.time.Instant;
import java.util.UUID;

import dev.mahindra.tenantforge.tenancy.TenantContext;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(schema = "app", name = "exports")
public class ExportJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String kind;

    @Column(nullable = false)
    private String status = "queued";

    @Column(name = "s3_key")
    private String s3Key;

    @Column(name = "rows_written")
    private Integer rowsWritten;

    private String error;

    @Column(name = "requested_by", nullable = false)
    private String requestedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    protected ExportJob() {
    }

    public ExportJob(String kind, String requestedBy) {
        this.kind = kind;
        this.requestedBy = requestedBy;
    }

    @PrePersist
    void bindTenant() {
        if (tenantId == null) {
            tenantId = TenantContext.require();
        }
    }

    void running() {
        status = "running";
    }

    void done(String key, int rows) {
        status = "done";
        s3Key = key;
        rowsWritten = rows;
        completedAt = Instant.now();
    }

    void failed(String reason) {
        status = "failed";
        error = reason;
        completedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getKind() { return kind; }
    public String getStatus() { return status; }
    public String getS3Key() { return s3Key; }
    public Integer getRowsWritten() { return rowsWritten; }
    public String getError() { return error; }
    public String getRequestedBy() { return requestedBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }
}
