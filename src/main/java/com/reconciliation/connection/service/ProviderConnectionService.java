package com.reconciliation.connection.service;

import com.reconciliation.common.enums.ConnectionStatus;
import com.reconciliation.common.util.EncryptionService;
import com.reconciliation.connection.entity.ProviderConnection;
import com.reconciliation.connection.repository.ProviderConnectionRepository;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProviderConnectionService {

    private final ProviderConnectionRepository connectionRepository;
    private final EncryptionService encryptionService;
    private final ProviderCredentialVerifier credentialVerifier;

    @Transactional(readOnly = true)
    public Map<String, Object> list(String merchantId) {
        List<Map<String, Object>> items = connectionRepository.findByMerchantIdOrderByProviderAsc(merchantId)
                .stream()
                .map(this::toResponse)
                .toList();
        return Map.of("items", items);
    }

    @Transactional
    public Map<String, Object> upsert(String merchantId, String provider, String apiKey, String secret) {
        String normalizedProvider = normalizeProvider(provider);
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey is required");
        }
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("secret is required");
        }

        credentialVerifier.verify(normalizedProvider, apiKey.trim(), secret.trim());

        ProviderConnection connection = connectionRepository
                .findByMerchantIdAndProvider(merchantId, normalizedProvider)
                .orElseGet(() -> ProviderConnection.builder()
                        .merchantId(merchantId)
                        .provider(normalizedProvider)
                        .build());

        connection.setApiKeyEncrypted(encryptionService.encrypt(apiKey.trim()));
        connection.setSecretEncrypted(encryptionService.encrypt(secret.trim()));
        connection.setApiKeyMasked(mask(apiKey.trim()));
        connection.setStatus(ConnectionStatus.ACTIVE);

        return toResponse(connectionRepository.save(connection));
    }

    private String normalizeProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("provider is required");
        }
        String normalized = provider.trim().toLowerCase();
        if (!List.of("razorpay", "stripe").contains(normalized)) {
            throw new IllegalArgumentException("Unsupported provider: " + provider);
        }
        return normalized;
    }

    private String mask(String value) {
        if (value.length() <= 8) {
            return "****";
        }
        return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> testConnection(String merchantId, String provider) {
        String normalizedProvider = normalizeProvider(provider);
        ProviderConnection connection = connectionRepository
                .findByMerchantIdAndProvider(merchantId, normalizedProvider)
                .filter(c -> c.getStatus() == ConnectionStatus.ACTIVE)
                .orElseThrow(() -> new IllegalArgumentException("No active connection found for provider: " + normalizedProvider));

        credentialVerifier.verify(
                normalizedProvider,
                encryptionService.decrypt(connection.getApiKeyEncrypted()),
                encryptionService.decrypt(connection.getSecretEncrypted()));

        return Map.of("provider", normalizedProvider, "status", "OK");
    }

    @Transactional(readOnly = true)
    public java.util.Optional<ProviderConnection> findActiveConnection(String merchantId, String provider) {
        return connectionRepository.findByMerchantIdAndProvider(merchantId, provider)
                .filter(c -> c.getStatus() == ConnectionStatus.ACTIVE);
    }

    @Transactional(readOnly = true)
    public List<ProviderConnection> findAllActiveByProvider(String provider) {
        return connectionRepository.findByProviderAndStatus(provider, ConnectionStatus.ACTIVE);
    }

    public String decryptApiKey(ProviderConnection connection) {
        return encryptionService.decrypt(connection.getApiKeyEncrypted());
    }

    public String decryptSecret(ProviderConnection connection) {
        return encryptionService.decrypt(connection.getSecretEncrypted());
    }

    private Map<String, Object> toResponse(ProviderConnection connection) {
        return Map.of(
                "id", connection.getId(),
                "provider", connection.getProvider(),
                "apiKey", connection.getApiKeyMasked(),
                "secretStored", connection.getSecretEncrypted() != null && !connection.getSecretEncrypted().isBlank(),
                "status", connection.getStatus().name(),
                "createdAt", connection.getCreatedAt(),
                "updatedAt", connection.getUpdatedAt());
    }
}
