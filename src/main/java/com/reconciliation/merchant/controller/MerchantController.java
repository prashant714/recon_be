package com.reconciliation.merchant.controller;

import com.reconciliation.merchant.entity.Merchant;
import com.reconciliation.merchant.service.MerchantService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
            @NotBlank @Email String email,
            @Size(min = 8, max = 128) String password) {}

    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password) {}

    public record AuthRequest(
            @NotBlank String merchantId,
            @NotBlank String apiKey) {}

    public record SetPasswordRequest(
            @NotBlank @Size(min = 8, max = 128) String password) {}

    /** Register a new merchant. Password is optional but recommended for frontend login. */
    @PostMapping("/register")
    public ResponseEntity<Map<String, String>> register(@Valid @RequestBody RegisterRequest req) {
        Map<String, String> result = merchantService.register(
                req.merchantId(), req.name(), req.email(), req.password());
        return ResponseEntity.ok(result);
    }

    /** Frontend login: email + password -> JWT. */
    @PostMapping("/login")
    public ResponseEntity<Map<String, String>> login(@Valid @RequestBody LoginRequest req) {
        String token = merchantService.loginByEmail(req.email(), req.password());
        return ResponseEntity.ok(Map.of("token", token, "type", "Bearer"));
    }

    /** Refresh: valid JWT -> new JWT with fresh expiry. */
    @PostMapping("/refresh")
    public ResponseEntity<Map<String, String>> refresh(HttpServletRequest request) {
        String merchantId = (String) request.getAttribute("merchantId");
        if (merchantId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Merchant authentication required"));
        }
        String token = merchantService.refreshToken(merchantId);
        return ResponseEntity.ok(Map.of("token", token, "type", "Bearer"));
    }

    /** Set or update password for the current merchant. Requires valid JWT. */
    @PostMapping("/set-password")
    public ResponseEntity<Map<String, String>> setPassword(
            @Valid @RequestBody SetPasswordRequest req,
            HttpServletRequest request) {
        String merchantId = (String) request.getAttribute("merchantId");
        if (merchantId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Merchant authentication required"));
        }
        merchantService.setPassword(merchantId, req.password());
        return ResponseEntity.ok(Map.of("message", "Password set successfully"));
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

    /** Server-to-server auth: merchantId + apiKey -> JWT. */
    @PostMapping("/auth")
    public ResponseEntity<Map<String, String>> auth(@Valid @RequestBody AuthRequest req) {
        String token = merchantService.authenticate(req.merchantId(), req.apiKey());
        return ResponseEntity.ok(Map.of("token", token, "type", "Bearer"));
    }

    /** Return the current merchant's profile. */
    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(HttpServletRequest request) {
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
                "passwordConfigured", m.getPasswordHash() != null,
                "status", m.getStatus(),
                "createdAt", m.getCreatedAt()
        ));
    }
}
