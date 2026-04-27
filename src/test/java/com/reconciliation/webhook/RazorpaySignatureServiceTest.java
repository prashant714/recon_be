package com.reconciliation.webhook;

import com.reconciliation.webhook.service.RazorpaySignatureService;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RazorpaySignatureServiceTest {

    @Test
    void verifiesValidSignature() throws Exception {
        byte[] payload = "{\"event\":\"payment.captured\"}".getBytes(StandardCharsets.UTF_8);
        String secret = "test_secret";
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = HexFormat.of().formatHex(mac.doFinal(payload));

        RazorpaySignatureService service = new RazorpaySignatureService(secret);

        assertThat(service.verify(payload, signature)).isTrue();
    }
}
