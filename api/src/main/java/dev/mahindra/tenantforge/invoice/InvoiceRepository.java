package dev.mahindra.tenantforge.invoice;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Note what's missing: no {@code tenantId} parameter anywhere. Row-Level Security scopes every query to the
 * connection's tenant, so a forgotten filter can't leak another tenant's invoices.
 */
public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    List<Invoice> findTop100ByOrderByCreatedAtDesc();

    Stream<Invoice> streamAllByOrderByIssuedOnAscNumberAsc();
}
