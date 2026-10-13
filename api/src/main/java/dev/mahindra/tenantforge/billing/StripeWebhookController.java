package dev.mahindra.tenantforge.billing;

import java.time.Clock;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.mahindra.tenantforge.TenantForgeProperties;

/**
 * Receives Stripe webhooks. The raw body is verified against {@code Stripe-Signature} before it is parsed.
 * Point the Stripe dashboard (or {@code stripe listen --forward-to}) at {@code /webhooks/stripe}.
 */
@RestController
class StripeWebhookController {

    private final StripeSignatureVerifier verifier;
    private final StripeWebhookService service;
    private final ObjectMapper json;

    StripeWebhookController(TenantForgeProperties props, Clock clock, StripeWebhookService service, ObjectMapper json) {
        this.verifier = new StripeSignatureVerifier(props.stripe().webhookSecret(), props.stripe().tolerance(), clock);
        this.service = service;
        this.json = json;
    }

    @PostMapping("/webhooks/stripe")
    ResponseEntity<Map<String, Object>> receive(@RequestBody String payload,
            @RequestHeader(name = "Stripe-Signature", required = false) String signature) {
        try {
            verifier.verify(payload, signature);
        } catch (StripeSignatureVerifier.InvalidSignatureException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_signature", "detail", e.getMessage()));
        }
        try {
            var outcome = service.handle(json.readTree(payload));
            return ResponseEntity.ok(Map.of("status", outcome.status()));
        } catch (JsonProcessingException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_json"));
        }
    }
}
