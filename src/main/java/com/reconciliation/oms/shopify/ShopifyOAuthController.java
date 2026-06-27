package com.reconciliation.oms.shopify;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;

@RestController
@RequestMapping("/api/v1/connections/shopify/oauth")
@RequiredArgsConstructor
@Slf4j
public class ShopifyOAuthController {

    private final ShopifyOAuthService oAuthService;

    /**
     * Step 1 — merchant clicks "Connect Shopify" in the dashboard.
     * Frontend calls this with the merchant's JWT; backend redirects to Shopify.
     * merchantId is extracted from the JWT filter attribute set by JwtFilter.
     */
    @GetMapping("/init")
    public RedirectView init(
            @RequestParam String shop,
            HttpServletRequest request) {
        String merchantId = (String) request.getAttribute("merchantId");
        if (merchantId == null) {
            throw new IllegalStateException("Merchant not authenticated");
        }
        String authUrl = oAuthService.initiateOAuth(merchantId, shop);
        log.info("Shopify OAuth initiated for merchant={} shop={}", merchantId, shop);
        return new RedirectView(authUrl);
    }

    /**
     * Step 2 — Shopify redirects the merchant's browser here after approval.
     * No JWT required — secured by state + HMAC from Shopify.
     */
    @GetMapping("/callback")
    public ResponseEntity<Map<String, String>> callback(
            @RequestParam String shop,
            @RequestParam String code,
            @RequestParam String state,
            @RequestParam(required = false) String hmac,
            HttpServletRequest request) {
        Map<String, String> allParams = new HashMap<>();
        request.getParameterMap().forEach((k, v) -> allParams.put(k, v[0]));

        try {
            String merchantId = oAuthService.handleCallback(shop, code, state, hmac, allParams);
            return ResponseEntity.ok(Map.of(
                    "status", "connected",
                    "shop", shop,
                    "merchantId", merchantId));
        } catch (SecurityException e) {
            log.warn("Shopify OAuth callback HMAC failed for shop={}", shop);
            return ResponseEntity.status(401).body(Map.of("error", "Invalid signature"));
        } catch (Exception e) {
            // State not found — return the code so merchant can call /exchange with JWT
            log.warn("Shopify OAuth callback state error for shop={}: {} — returning code for manual exchange", shop, e.getMessage());
            return ResponseEntity.ok(Map.of(
                    "status", "code_received",
                    "shop", shop,
                    "code", code != null ? code : "",
                    "next_step", "Call GET /api/v1/connections/shopify/oauth/exchange?shop=" + shop + "&code=" + code + " with your JWT to complete connection"));
        }
    }

    /**
     * Manual code exchange — JWT secured.
     * Use this when you have the authorization code from Shopify but want to
     * exchange it directly without going through the browser OAuth flow.
     * Open the Shopify auth URL in browser, copy the 'code' param from the
     * redirect, then call this endpoint.
     */
    @GetMapping("/exchange")
    public ResponseEntity<Map<String, String>> exchange(
            @RequestParam String shop,
            @RequestParam String code,
            HttpServletRequest request) {
        String merchantId = (String) request.getAttribute("merchantId");
        if (merchantId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Merchant not authenticated"));
        }
        try {
            oAuthService.exchangeAndSave(merchantId, shop, code);
            return ResponseEntity.ok(Map.of(
                    "status", "connected",
                    "shop", shop,
                    "merchantId", merchantId));
        } catch (Exception e) {
            log.error("Shopify manual exchange failed shop={}: {}", shop, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
