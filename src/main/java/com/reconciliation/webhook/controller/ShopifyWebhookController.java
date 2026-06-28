package com.reconciliation.webhook.controller;

import com.reconciliation.webhook.service.ShopifyWebhookService;
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
public class ShopifyWebhookController {

    private final ShopifyWebhookService webhookService;

    @PostMapping("/shopify")
    public ResponseEntity<String> handleShopifyWebhook(
            HttpServletRequest request,
            @RequestHeader(value = "X-Shopify-Hmac-SHA256", required = false) String hmac,
            @RequestHeader(value = "X-Shopify-Shop-Domain", required = false) String shopDomain,
            @RequestHeader(value = "X-Shopify-Topic", required = false) String topic)
            throws IOException {

        byte[] rawBody = request.getInputStream().readAllBytes();

        log.info("Shopify webhook received topic={} shop={} hmacPresent={} bodyBytes={}",
                topic, shopDomain, hmac != null, rawBody.length);

        if (shopDomain == null || shopDomain.isBlank()) {
            log.warn("Shopify webhook missing X-Shopify-Shop-Domain header");
            return ResponseEntity.badRequest().body("Missing shop domain");
        }

        boolean accepted = webhookService.handle(rawBody, hmac, shopDomain, topic);
        if (!accepted) {
            return ResponseEntity.status(401).body("Unauthorized");
        }

        return ResponseEntity.ok("received");
    }
}
