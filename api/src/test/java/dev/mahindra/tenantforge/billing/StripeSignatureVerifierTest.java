package dev.mahindra.tenantforge.billing;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class StripeSignatureVerifierTest {

    private static final String SECRET = "whsec_unit";
    private static final Instant NOW = Instant.parse("2026-10-13T06:00:00Z");
    private final StripeSignatureVerifier verifier =
            new StripeSignatureVerifier(SECRET, Duration.ofMinutes(5), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void acceptsAValidSignatureAmongSeveral() {
        long t = NOW.getEpochSecond();
        String payload = "{\"id\":\"evt_1\"}";
        String header = "t=" + t + ",v1=deadbeef,v1=" + StripeSignatureVerifier.sign(SECRET, t, payload) + ",v0=ignored";
        assertThatCode(() -> verifier.verify(payload, header)).doesNotThrowAnyException();
    }

    @Test
    void rejectsATamperedPayload() {
        long t = NOW.getEpochSecond();
        String header = "t=" + t + ",v1=" + StripeSignatureVerifier.sign(SECRET, t, "{\"amount\":1}");
        assertThatThrownBy(() -> verifier.verify("{\"amount\":1000}", header))
                .isInstanceOf(StripeSignatureVerifier.InvalidSignatureException.class);
    }

    @Test
    void rejectsReplaysOutsideTheTolerance() {
        long t = NOW.minus(Duration.ofMinutes(6)).getEpochSecond();
        String header = "t=" + t + ",v1=" + StripeSignatureVerifier.sign(SECRET, t, "{}");
        assertThatThrownBy(() -> verifier.verify("{}", header)).hasMessageContaining("tolerance");
    }

    @Test
    void rejectsMissingOrMalformedHeaders() {
        assertThatThrownBy(() -> verifier.verify("{}", null)).hasMessageContaining("Missing");
        assertThatThrownBy(() -> verifier.verify("{}", "garbage")).hasMessageContaining("Malformed");
    }
}
