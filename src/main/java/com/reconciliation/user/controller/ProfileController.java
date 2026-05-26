package com.reconciliation.user.controller;

import com.reconciliation.merchant.entity.Merchant;
import com.reconciliation.merchant.service.MerchantService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
public class ProfileController {

    private final MerchantService merchantService;

    @GetMapping
    public ResponseEntity<?> me(HttpServletRequest request) {
        String merchantId = merchantId(request);
        if (merchantId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Merchant authentication required"));
        }
        return ResponseEntity.ok(toProfile(merchantService.getByMerchantId(merchantId)));
    }

    @PatchMapping
    public ResponseEntity<?> update(
            @RequestBody UpdateProfileRequest update,
            HttpServletRequest request) {
        String merchantId = merchantId(request);
        if (merchantId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Merchant authentication required"));
        }
        Merchant merchant = merchantService.updateProfile(merchantId, update.name(), update.email());
        return ResponseEntity.ok(toProfile(merchant));
    }

    private String merchantId(HttpServletRequest request) {
        return (String) request.getAttribute("merchantId");
    }

    private Map<String, Object> toProfile(Merchant merchant) {
        return Map.of(
                "name", merchant.getName(),
                "email", merchant.getEmail(),
                "role", "Admin");
    }

    public record UpdateProfileRequest(String name, String email) {}
}
