package com.reconciliation.webhook.controller;

import com.reconciliation.webhook.service.StripeSignatureService;
import com.reconciliation.webhook.service.WebhookIngestionService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/webhooks")
@RequiredArgsConstructor
@Slf4j
public class StripeWebhookController {

    private final StripeSignatureService signatureService;
    private final WebhookIngestionService ingestionService;

    @PostMapping("/stripe")
    public ResponseEntity<String> handleStripeWebhook(
            HttpServletRequest request,
            @RequestHeader(value = "Stripe-Signature", required = false) String signature)
            throws IOException {
        byte[] rawBody = request.getInputStream().readAllBytes();

        if (signature == null || !signatureService.verify(rawBody, signature)) {
            log.warn("Stripe signature verification failed from IP: {}", request.getRemoteAddr());
            return ResponseEntity.badRequest().body("Invalid signature");
        }

        ingestionService.ingestAsync(rawBody, "stripe", "webhook");
        return ResponseEntity.ok("received");
    }
}
