package dev.mahindra.tenantforge.export;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import dev.mahindra.tenantforge.aws.TenantFileStorage;
import dev.mahindra.tenantforge.tenancy.Plan;
import dev.mahindra.tenantforge.tenancy.Tenant;
import dev.mahindra.tenantforge.tenancy.TenantCatalog;
import dev.mahindra.tenantforge.tenancy.TenantContext;

/**
 * Exports are slow, so the request only records the job and queues it ({@code 202 Accepted}); a worker does the work.
 * Admins only, and only on plans that include exports.
 */
@RestController
@RequestMapping("/api/exports")
class ExportController {

    private final ExportRepository exports;
    private final ExportQueue queue;
    private final TenantCatalog catalog;
    private final TenantFileStorage storage;

    ExportController(ExportRepository exports, ExportQueue queue, TenantCatalog catalog, TenantFileStorage storage) {
        this.exports = exports;
        this.queue = queue;
        this.catalog = catalog;
        this.storage = storage;
    }

    @PostMapping
    @PreAuthorize("hasRole('tenant_admin')")
    @Transactional
    ResponseEntity<ExportView> request(@AuthenticationPrincipal Jwt jwt) {
        Tenant tenant = catalog.find(TenantContext.require()).orElseThrow();
        Plan plan = catalog.plan(tenant.plan());
        if (!plan.exportsEnabled()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Exports are not included in the '" + plan.code() + "' plan");
        }
        ExportJob job = exports.save(new ExportJob("invoices_csv", jwt.getSubject()));
        queue.sendAfterCommit(new ExportQueue.ExportMessage(job.getId(), tenant.id(), job.getKind()));
        return ResponseEntity.accepted().location(URI.create("/api/exports/" + job.getId())).body(view(job));
    }

    @GetMapping
    @Transactional(readOnly = true)
    List<ExportView> recent() {
        return exports.findTop20ByOrderByCreatedAtDesc().stream().map(this::view).toList();
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    ExportView get(@PathVariable UUID id) {
        return exports.findById(id).map(this::view)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Export not found"));
    }

    private ExportView view(ExportJob job) {
        String url = "done".equals(job.getStatus()) ? storage.downloadUrl(job.getS3Key()).toString() : null;
        return new ExportView(job.getId(), job.getKind(), job.getStatus(), job.getRowsWritten(), job.getError(),
                job.getCreatedAt(), job.getCompletedAt(), url);
    }

    record ExportView(UUID id, String kind, String status, Integer rows, String error, Instant createdAt,
            Instant completedAt, String downloadUrl) {
    }
}
