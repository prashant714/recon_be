package com.reconciliation.webhook.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class RazorpaySignatureService {

    private final byte[] webhookSecretBytes;

    public RazorpaySignatureService(
            @Value("${app.razorpay.webhook-secret}") String webhookSecret) {
        this.webhookSecretBytes = webhookSecret.getBytes(StandardCharsets.UTF_8);
    }

    public boolean verify(byte[] rawBody, String signature) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecretBytes, "HmacSHA256"));
            String expected = HexFormat.of().formatHex(mac.doFinal(rawBody));
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }
}
