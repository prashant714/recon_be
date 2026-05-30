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
        if (merchantId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Merchant authentication required"));
        }
        return ResponseEntity.ok(connectionService.list(merchantId));
    }

    @PostMapping
    public ResponseEntity<?> upsert(
            @RequestBody ConnectionRequest connection,
            HttpServletRequest request) {
        String merchantId = merchantId(request);
        if (merchantId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Merchant authentication required"));
        }
        return ResponseEntity.ok(connectionService.upsert(
                merchantId, connection.provider(), connection.apiKey(), connection.secret()));
    }

    @PostMapping("/test")
    public ResponseEntity<?> test(
            @RequestParam String provider,
            HttpServletRequest request) {
        String merchantId = merchantId(request);
        if (merchantId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Merchant authentication required"));
        }
        return ResponseEntity.ok(connectionService.testConnection(merchantId, provider));
    }

    private String merchantId(HttpServletRequest request) {
        return (String) request.getAttribute("merchantId");
    }

    public record ConnectionRequest(String provider, String apiKey, String secret) {}
}
