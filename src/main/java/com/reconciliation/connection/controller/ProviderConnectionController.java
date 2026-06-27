package com.reconciliation.connection.controller;

import com.reconciliation.connection.service.ProviderConnectionService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/connections")
@RequiredArgsConstructor
public class ProviderConnectionController {

    private final ProviderConnectionService connectionService;

    @GetMapping
    public ResponseEntity<?> list(HttpServletRequest request) {
        String merchantId = merchantId(request);
        if (merchantId == null) return unauthorized();
        return ResponseEntity.ok(connectionService.list(merchantId));
    }

    /** Payment gateways: Razorpay, Stripe — apiKey + secret. */
    @PostMapping
    public ResponseEntity<?> upsert(
            @RequestBody ConnectionRequest connection,
            HttpServletRequest request) {
        String merchantId = merchantId(request);
        if (merchantId == null) return unauthorized();
        return ResponseEntity.ok(connectionService.upsert(
                merchantId, connection.provider(), connection.apiKey(), connection.secret()));
    }

    @PostMapping("/test")
    public ResponseEntity<?> test(
            @RequestParam String provider,
            HttpServletRequest request) {
        String merchantId = merchantId(request);
        if (merchantId == null) return unauthorized();
        return ResponseEntity.ok(connectionService.testConnection(merchantId, provider));
    }

    /**
     * OAuth OMS connections: Zoho Inventory — clientId + clientSecret + refreshToken.
     * Verification delegated to the ZohoOmsConnector; no central switch needed.
     */
    @PostMapping("/oauth")
    public ResponseEntity<?> upsertOAuth(
            @RequestBody OAuthConnectionRequest connection,
            HttpServletRequest request) {
        String merchantId = merchantId(request);
        if (merchantId == null) return unauthorized();
        return ResponseEntity.ok(connectionService.upsertOAuth(
                merchantId, connection.provider(), connection.clientId(),
                connection.clientSecret(), connection.refreshToken(),
                connection.organizationId()));
    }

    /**
     * Simple-token OMS connections: Shopify — shopDomain + accessToken.
     * Adding a new OMS provider only requires a new OmsConnector @Component — no change here.
     */
    @PostMapping("/oms")
    public ResponseEntity<?> upsertOmsToken(
            @RequestBody OmsTokenConnectionRequest req,
            HttpServletRequest request) {
        String merchantId = merchantId(request);
        if (merchantId == null) return unauthorized();
        return ResponseEntity.ok(connectionService.upsertOmsToken(
                merchantId, req.provider(), req.shopDomain(), req.accessToken()));
    }

    private String merchantId(HttpServletRequest request) {
        return (String) request.getAttribute("merchantId");
    }

    private ResponseEntity<?> unauthorized() {
        return ResponseEntity.status(401).body(Map.of("error", "Merchant authentication required"));
    }

    public record ConnectionRequest(String provider, String apiKey, String secret) {}

    public record OAuthConnectionRequest(String provider, String clientId, String clientSecret,
                                         String refreshToken, String organizationId) {}

    /** shopDomain: e.g. mystore.myshopify.com — accessToken: Shopify Admin API token */
    public record OmsTokenConnectionRequest(String provider, String shopDomain, String accessToken) {}
}
