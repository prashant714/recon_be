package com.reconciliation.webhook;

import com.reconciliation.merchant.entity.Merchant;
import com.reconciliation.merchant.repository.MerchantRepository;
import com.reconciliation.webhook.service.RazorpaySignatureService;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RazorpaySignatureServiceTest {

    @Test
    void verifiesValidSignature() throws Exception {
        byte[] payload = "{\"event\":\"payment.captured\"}".getBytes(StandardCharsets.UTF_8);
        String secret = "test_secret";
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = HexFormat.of().formatHex(mac.doFinal(payload));

        MerchantRepository repository = mock(MerchantRepository.class);
        when(repository.findByStatus("ACTIVE")).thenReturn(List.of());
        RazorpaySignatureService service =
                new RazorpaySignatureService(repository, secret, "merchant_001");

        assertThat(service.verify(payload, signature)).isTrue();
        assertThat(service.resolveMerchantId(payload, signature)).contains("merchant_001");
    }

    @Test
    void resolvesMerchantByMerchantWebhookSecret() throws Exception {
        byte[] payload = "{\"event\":\"payment.captured\"}".getBytes(StandardCharsets.UTF_8);
        String secret = "merchant_secret";
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = HexFormat.of().formatHex(mac.doFinal(payload));

        MerchantRepository repository = mock(MerchantRepository.class);
        when(repository.findByStatus("ACTIVE")).thenReturn(List.of(Merchant.builder()
                .merchantId("merchant_live")
                .webhookSecret(secret)
                .status("ACTIVE")
                .build()));
        RazorpaySignatureService service =
                new RazorpaySignatureService(repository, "default_secret", "merchant_001");

        assertThat(service.resolveMerchantId(payload, signature)).contains("merchant_live");
    }
}
