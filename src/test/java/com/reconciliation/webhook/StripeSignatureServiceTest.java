package com.reconciliation.webhook;

import com.reconciliation.webhook.service.StripeSignatureService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class StripeSignatureServiceTest {

    @Test
    void rejectsInvalidSignature() {
        StripeSignatureService service = new StripeSignatureService("whsec_test");
        assertThat(service.verify("{}".getBytes(StandardCharsets.UTF_8), "bad-signature")).isFalse();
    }

    @Test
    void verifiesGeneratedSignature() throws Exception {
        String secret = "whsec_test";
        String payload = "{\"id\":\"evt_123\",\"type\":\"payment_intent.succeeded\",\"created\":1710000000}";
        long timestamp = Instant.now().getEpochSecond();
        String signedPayload = timestamp + "." + payload;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = HexFormat.of().formatHex(mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8)));
        String header = "t=" + timestamp + ",v1=" + signature;

        StripeSignatureService service = new StripeSignatureService(secret);

        assertThat(service.verify(payload.getBytes(StandardCharsets.UTF_8), header)).isTrue();
    }
}
