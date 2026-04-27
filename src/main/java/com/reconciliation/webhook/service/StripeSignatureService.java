package com.reconciliation.webhook.service;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.net.Webhook;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class StripeSignatureService {

    private final String webhookSecret;

    public StripeSignatureService(
            @Value("${app.stripe.webhook-secret}") String webhookSecret) {
        this.webhookSecret = webhookSecret;
    }

    public boolean verify(byte[] rawBody, String signature) {
        try {
            Webhook.constructEvent(new String(rawBody, StandardCharsets.UTF_8), signature, webhookSecret);
            return true;
        } catch (SignatureVerificationException e) {
            return false;
        }
    }
}
