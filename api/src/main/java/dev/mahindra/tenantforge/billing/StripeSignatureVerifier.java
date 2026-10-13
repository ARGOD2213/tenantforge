package dev.mahindra.tenantforge.billing;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Verifies Stripe's {@code Stripe-Signature} header: {@code t=<unix seconds>,v1=<hex HMAC-SHA256 of "t.payload">}.
 * Rejects bad signatures and timestamps outside the tolerance (replay protection).
 */
public class StripeSignatureVerifier {

    private final String secret;
    private final Duration tolerance;
    private final Clock clock;

    public StripeSignatureVerifier(String secret, Duration tolerance, Clock clock) {
        this.secret = secret;
        this.tolerance = tolerance;
        this.clock = clock;
    }

    public void verify(String payload, String header) {
        if (secret == null || secret.isBlank()) {
            throw new InvalidSignatureException("Webhook secret is not configured");
        }
        if (header == null || header.isBlank()) {
            throw new InvalidSignatureException("Missing Stripe-Signature header");
        }
        long timestamp = -1;
        List<String> signatures = new ArrayList<>();
        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            switch (kv[0]) {
                case "t" -> timestamp = parse(kv[1]);
                case "v1" -> signatures.add(kv[1]);
                default -> {
                    // other schemes (v0) are ignored
                }
            }
        }
        if (timestamp < 0 || signatures.isEmpty()) {
            throw new InvalidSignatureException("Malformed Stripe-Signature header");
        }
        long age = Math.abs(clock.instant().getEpochSecond() - timestamp);
        if (age > tolerance.toSeconds()) {
            throw new InvalidSignatureException("Timestamp outside the tolerance window");
        }
        byte[] expected = sign(secret, timestamp, payload).getBytes(StandardCharsets.UTF_8);
        for (String candidate : signatures) {
            if (MessageDigest.isEqual(expected, candidate.getBytes(StandardCharsets.UTF_8))) {
                return;
            }
        }
        throw new InvalidSignatureException("Signature does not match");
    }

    /** Computes the v1 signature (also used by tests and the local webhook script). */
    public static String sign(String secret, long timestamp, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static long parse(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static class InvalidSignatureException extends RuntimeException {
        public InvalidSignatureException(String message) {
            super(message);
        }
    }
}
