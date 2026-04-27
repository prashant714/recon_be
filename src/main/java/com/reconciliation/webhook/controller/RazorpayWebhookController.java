package com.reconciliation.webhook.controller;

import com.reconciliation.webhook.service.RazorpaySignatureService;
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
public class RazorpayWebhookController {

    private final RazorpaySignatureService signatureService;
    private final WebhookIngestionService ingestionService;

    @PostMapping("/razorpay")
    public ResponseEntity<String> handleRazorpayWebhook(
            HttpServletRequest request,
            @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature)
            throws IOException {
        byte[] rawBody = request.getInputStream().readAllBytes();

        if (signature == null || !signatureService.verify(rawBody, signature)) {
            log.warn("Razorpay signature verification failed from IP: {}", request.getRemoteAddr());
            return ResponseEntity.badRequest().body("Invalid signature");
        }

        ingestionService.ingestAsync(rawBody, "razorpay", "webhook");
        return ResponseEntity.ok("received");
    }
}
