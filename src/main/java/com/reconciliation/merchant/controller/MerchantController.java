package com.reconciliation.merchant.controller;

import com.reconciliation.merchant.entity.Merchant;
import com.reconciliation.merchant.service.MerchantService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/merchants")
@RequiredArgsConstructor
public class MerchantController {

    private final MerchantService merchantService;

    public record RegisterRequest(
            @NotBlank String merchantId,
            @NotBlank String name,
            @NotBlank @Email String email) {}

    public record AuthRequest(
            @NotBlank String merchantId,
            @NotBlank String apiKey) {}

    /** Register a new merchant. Returns a one-time API key. */
    @PostMapping("/register")
    public ResponseEntity<Map<String, String>> register(@Valid @RequestBody RegisterRequest req) {
        Map<String, String> result = merchantService.register(req.merchantId(), req.name(), req.email());
        return ResponseEntity.ok(result);
    }

    /** Reset API key for a merchant. Returns a new one-time API key. */
    @PostMapping("/reset-key")
    public ResponseEntity<Map<String, String>> resetKey(@RequestBody Map<String, String> req) {
        String merchantId = req.get("merchantId");
        if (merchantId == null || merchantId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "merchantId is required"));
        }
        return ResponseEntity.ok(merchantService.resetApiKey(merchantId));
    }

    /** Exchange merchantId + apiKey for a JWT. */
    @PostMapping("/auth")
    public ResponseEntity<Map<String, String>> auth(@Valid @RequestBody AuthRequest req) {
        String token = merchantService.authenticate(req.merchantId(), req.apiKey());
        return ResponseEntity.ok(Map.of("token", token, "type", "Bearer"));
    }

    /** Return the current merchant's profile (reads merchantId from JWT). */
    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(jakarta.servlet.http.HttpServletRequest request) {
        String merchantId = (String) request.getAttribute("merchantId");
        if (merchantId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Merchant authentication required"));
        }
        Merchant m = merchantService.getByMerchantId(merchantId);
        return ResponseEntity.ok(Map.of(
                "merchantId", m.getMerchantId(),
                "name", m.getName(),
                "email", m.getEmail(),
                "webhookConfigured", m.getWebhookSecret() != null && !m.getWebhookSecret().isBlank(),
                "status", m.getStatus(),
                "createdAt", m.getCreatedAt()
        ));
    }
}
