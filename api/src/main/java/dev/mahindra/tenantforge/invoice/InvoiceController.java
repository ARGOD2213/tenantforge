package dev.mahindra.tenantforge.invoice;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/invoices")
class InvoiceController {

    private final InvoiceRepository invoices;

    InvoiceController(InvoiceRepository invoices) {
        this.invoices = invoices;
    }

    @GetMapping
    @Transactional(readOnly = true)
    List<InvoiceView> list() {
        return invoices.findTop100ByOrderByCreatedAtDesc().stream().map(InvoiceView::of).toList();
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    InvoiceView get(@PathVariable UUID id) {
        // Another tenant's id simply doesn't exist from here: RLS hides the row, so this is a 404, not a 403.
        return invoices.findById(id).map(InvoiceView::of)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invoice not found"));
    }

    @PostMapping
    @Transactional
    ResponseEntity<InvoiceView> create(@Valid @RequestBody NewInvoice body) {
        try {
            Invoice saved = invoices.saveAndFlush(new Invoice(body.number(), body.customer(), body.amountCents(),
                    body.currency() == null ? "INR" : body.currency()));
            return ResponseEntity.created(URI.create("/api/invoices/" + saved.getId())).body(InvoiceView.of(saved));
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Invoice number already used");
        }
    }

    record NewInvoice(
            @NotBlank @Size(max = 40) String number,
            @NotBlank @Size(max = 120) String customer,
            @Min(0) long amountCents,
            @Pattern(regexp = "[A-Z]{3}") String currency) {
    }

    record InvoiceView(UUID id, String number, String customer, long amountCents, String currency, String status,
            LocalDate issuedOn, Instant createdAt) {
        static InvoiceView of(Invoice i) {
            return new InvoiceView(i.getId(), i.getNumber(), i.getCustomer(), i.getAmountCents(), i.getCurrency(),
                    i.getStatus(), i.getIssuedOn(), i.getCreatedAt());
        }
    }
}
