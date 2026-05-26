package com.reconciliation.merchant.service;

import com.reconciliation.config.JwtConfig;
import com.reconciliation.merchant.entity.Merchant;
import com.reconciliation.merchant.repository.MerchantRepository;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class MerchantService {

    private final MerchantRepository merchantRepository;
    private final JwtConfig jwtConfig;
    private final BCryptPasswordEncoder passwordEncoder;

    @Transactional
    public Map<String, String> register(String merchantId, String name, String email) {
        if (merchantRepository.existsByMerchantId(merchantId)) {
            throw new IllegalArgumentException("Merchant ID already registered: " + merchantId);
        }
        if (merchantRepository.existsByEmail(email)) {
            throw new IllegalArgumentException("Email already registered: " + email);
        }

        String rawApiKey = generateApiKey();
        String hashedKey = passwordEncoder.encode(rawApiKey);
        String webhookSecret = generateApiKey();

        merchantRepository.save(Merchant.builder()
                .merchantId(merchantId)
                .name(name)
                .email(email)
                .apiKeyHash(hashedKey)
                .webhookSecret(webhookSecret)
                .status("ACTIVE")
                .build());

        log.info("Merchant registered: merchantId={}", merchantId);
        return Map.of(
                "merchantId", merchantId,
                "apiKey", rawApiKey,
                "webhookSecret", webhookSecret,
                "note", "Store this API key securely — it will not be shown again."
        );
    }

    @Transactional(readOnly = true)
    public String authenticate(String merchantId, String rawApiKey) {
        Merchant merchant = merchantRepository.findByMerchantId(merchantId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown merchant: " + merchantId));

        if (!"ACTIVE".equals(merchant.getStatus())) {
            throw new IllegalStateException("Merchant account is not active: " + merchantId);
        }

        if (!passwordEncoder.matches(rawApiKey, merchant.getApiKeyHash())) {
            throw new IllegalArgumentException("Invalid API key for merchant: " + merchantId);
        }

        return jwtConfig.generateMerchantToken(merchantId);
    }

    @Transactional(readOnly = true)
    public Merchant getByMerchantId(String merchantId) {
        return merchantRepository.findByMerchantId(merchantId)
                .orElseThrow(() -> new IllegalArgumentException("Merchant not found: " + merchantId));
    }

    @Transactional
    public Merchant updateProfile(String merchantId, String name, String email) {
        Merchant merchant = getByMerchantId(merchantId);
        if (name != null && !name.isBlank()) {
            merchant.setName(name.trim());
        }
        if (email != null && !email.isBlank() && !email.equalsIgnoreCase(merchant.getEmail())) {
            merchantRepository.findByEmail(email)
                    .filter(existing -> !existing.getMerchantId().equals(merchantId))
                    .ifPresent(existing -> {
                        throw new IllegalArgumentException("Email already registered: " + email);
                    });
            merchant.setEmail(email.trim().toLowerCase());
        }
        return merchantRepository.save(merchant);
    }

    @Transactional
    public Map<String, String> resetApiKey(String merchantId) {
        Merchant merchant = merchantRepository.findByMerchantId(merchantId)
                .orElseThrow(() -> new IllegalArgumentException("Merchant not found: " + merchantId));

        String rawApiKey = generateApiKey();
        merchant.setApiKeyHash(passwordEncoder.encode(rawApiKey));
        merchantRepository.save(merchant);

        log.info("API key reset for merchant: {}", merchantId);
        return Map.of(
                "merchantId", merchantId,
                "apiKey", rawApiKey,
                "note", "Store this API key securely — it will not be shown again."
        );
    }

    private String generateApiKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
