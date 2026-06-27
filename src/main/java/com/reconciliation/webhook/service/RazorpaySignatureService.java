package com.reconciliation.webhook.service;

import com.reconciliation.merchant.entity.Merchant;
import com.reconciliation.merchant.repository.MerchantRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class RazorpaySignatureService {

    private final MerchantRepository merchantRepository;

    public RazorpaySignatureService(MerchantRepository merchantRepository) {
        this.merchantRepository = merchantRepository;
    }

    public boolean verify(byte[] rawBody, String signature) {
        return resolveMerchantId(rawBody, signature).isPresent();
    }

    public Optional<String> resolveMerchantId(byte[] rawBody, String signature) {
        if (signature == null || signature.isBlank()) {
            return Optional.empty();
        }

        for (Merchant merchant : merchantRepository.findByStatus("ACTIVE")) {
            String secret = merchant.getWebhookSecret();
            if (secret != null && !secret.isBlank() && verifyWithSecret(rawBody, signature, secret)) {
                return Optional.of(merchant.getMerchantId());
            }
        }

        log.warn("Webhook signature did not match any active merchant — rejecting");
        return Optional.empty();
    }

    private boolean verifyWithSecret(byte[] rawBody, String signature, String webhookSecret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = HexFormat.of().formatHex(mac.doFinal(rawBody));
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }
}
