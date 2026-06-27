package com.reconciliation.connection.service;

import com.reconciliation.common.enums.ConnectionStatus;
import com.reconciliation.common.enums.ProviderType;
import com.reconciliation.common.util.EncryptionService;
import com.reconciliation.connection.entity.ProviderConnection;
import com.reconciliation.connection.repository.ProviderConnectionRepository;
import com.reconciliation.oms.connector.OmsConnector;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProviderConnectionService {

    private static final Set<String> PAYMENT_PROVIDERS = Set.of("razorpay", "stripe");

    private final ProviderConnectionRepository connectionRepository;
    private final EncryptionService encryptionService;
    private final ProviderCredentialVerifier credentialVerifier;
    private final Map<String, OmsConnector> omsConnectorRegistry;

    public ProviderConnectionService(
            ProviderConnectionRepository connectionRepository,
            EncryptionService encryptionService,
            ProviderCredentialVerifier credentialVerifier,
            List<OmsConnector> omsConnectors) {
        this.connectionRepository = connectionRepository;
        this.encryptionService = encryptionService;
        this.credentialVerifier = credentialVerifier;
        this.omsConnectorRegistry = omsConnectors.stream()
                .collect(Collectors.toMap(OmsConnector::getProvider, Function.identity()));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> list(String merchantId) {
        List<Map<String, Object>> items = connectionRepository.findByMerchantIdOrderByProviderAsc(merchantId)
                .stream()
                .map(this::toResponse)
                .toList();
        return Map.of("items", items);
    }

    /** Payment gateway connections (Razorpay, Stripe) — key/secret auth. */
    @Transactional
    public Map<String, Object> upsert(String merchantId, String provider, String apiKey, String secret) {
        String normalized = normalizePaymentProvider(provider);
        if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("apiKey is required");
        if (secret == null || secret.isBlank()) throw new IllegalArgumentException("secret is required");

        credentialVerifier.verify(normalized, apiKey.trim(), secret.trim());

        ProviderConnection connection = connectionRepository
                .findByMerchantIdAndProvider(merchantId, normalized)
                .orElseGet(() -> ProviderConnection.builder()
                        .merchantId(merchantId)
                        .provider(normalized)
                        .providerType(ProviderType.PAYMENT)
                        .build());

        connection.setApiKeyEncrypted(encryptionService.encrypt(apiKey.trim()));
        connection.setSecretEncrypted(encryptionService.encrypt(secret.trim()));
        connection.setApiKeyMasked(mask(apiKey.trim()));
        connection.setStatus(ConnectionStatus.ACTIVE);

        return toResponse(connectionRepository.save(connection));
    }

    /**
     * OAuth-based OMS connections (e.g. Zoho Inventory).
     * Verification is delegated to the registered OmsConnector.
     */
    @Transactional
    public Map<String, Object> upsertOAuth(String merchantId, String provider,
                                            String clientId, String clientSecret,
                                            String refreshToken, String organizationId) {
        String normalized = normalizeOmsProvider(provider);
        if (clientId == null || clientId.isBlank()) throw new IllegalArgumentException("clientId is required");
        if (clientSecret == null || clientSecret.isBlank()) throw new IllegalArgumentException("clientSecret is required");
        if (refreshToken == null || refreshToken.isBlank()) throw new IllegalArgumentException("refreshToken is required");

        ProviderConnection temp = ProviderConnection.builder()
                .merchantId(merchantId)
                .provider(normalized)
                .providerType(ProviderType.OMS)
                .apiKeyEncrypted(encryptionService.encrypt(clientId.trim()))
                .secretEncrypted(encryptionService.encrypt(clientSecret.trim()))
                .refreshTokenEncrypted(encryptionService.encrypt(refreshToken.trim()))
                .organizationId(organizationId != null ? organizationId.trim() : null)
                .build();
        omsConnectorRegistry.get(normalized).testConnection(temp);

        ProviderConnection connection = connectionRepository
                .findByMerchantIdAndProvider(merchantId, normalized)
                .orElseGet(() -> ProviderConnection.builder()
                        .merchantId(merchantId)
                        .provider(normalized)
                        .providerType(ProviderType.OMS)
                        .build());

        connection.setApiKeyEncrypted(encryptionService.encrypt(clientId.trim()));
        connection.setSecretEncrypted(encryptionService.encrypt(clientSecret.trim()));
        connection.setRefreshTokenEncrypted(encryptionService.encrypt(refreshToken.trim()));
        connection.setOrganizationId(organizationId != null ? organizationId.trim() : null);
        connection.setApiKeyMasked(mask(clientId.trim()));
        connection.setStatus(ConnectionStatus.ACTIVE);
        connection.setProviderType(ProviderType.OMS);

        return toResponse(connectionRepository.save(connection));
    }

    /**
     * Simple-token OMS connections (e.g. Shopify: shopDomain + accessToken).
     * Verification is delegated to the registered OmsConnector — no central switch needed.
     * To add a new OMS provider, implement OmsConnector and annotate with @Component.
     */
    @Transactional
    public Map<String, Object> upsertOmsToken(String merchantId, String provider,
                                               String shopDomain, String accessToken) {
        String normalized = normalizeOmsProvider(provider);
        if (shopDomain == null || shopDomain.isBlank()) throw new IllegalArgumentException("shopDomain is required");
        if (accessToken == null || accessToken.isBlank()) throw new IllegalArgumentException("accessToken is required");

        ProviderConnection temp = ProviderConnection.builder()
                .merchantId(merchantId)
                .provider(normalized)
                .providerType(ProviderType.OMS)
                .apiKeyEncrypted(encryptionService.encrypt(shopDomain.trim()))
                .secretEncrypted(encryptionService.encrypt(accessToken.trim()))
                .organizationId(shopDomain.trim())
                .build();
        omsConnectorRegistry.get(normalized).testConnection(temp);

        ProviderConnection connection = connectionRepository
                .findByMerchantIdAndProvider(merchantId, normalized)
                .orElseGet(() -> ProviderConnection.builder()
                        .merchantId(merchantId)
                        .provider(normalized)
                        .providerType(ProviderType.OMS)
                        .build());

        connection.setApiKeyEncrypted(encryptionService.encrypt(shopDomain.trim()));
        connection.setSecretEncrypted(encryptionService.encrypt(accessToken.trim()));
        connection.setApiKeyMasked(mask(shopDomain.trim()));
        connection.setOrganizationId(shopDomain.trim());
        connection.setStatus(ConnectionStatus.ACTIVE);
        connection.setProviderType(ProviderType.OMS);

        return toResponse(connectionRepository.save(connection));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> testConnection(String merchantId, String provider) {
        String normalized = normalizePaymentProvider(provider);
        ProviderConnection connection = connectionRepository
                .findByMerchantIdAndProvider(merchantId, normalized)
                .filter(c -> c.getStatus() == ConnectionStatus.ACTIVE)
                .orElseThrow(() -> new IllegalArgumentException("No active connection: " + normalized));

        credentialVerifier.verify(
                normalized,
                encryptionService.decrypt(connection.getApiKeyEncrypted()),
                encryptionService.decrypt(connection.getSecretEncrypted()));

        return Map.of("provider", normalized, "status", "OK");
    }

    @Transactional(readOnly = true)
    public List<ProviderConnection> findAllActiveByProviderType(ProviderType providerType) {
        return connectionRepository.findByProviderTypeAndStatus(providerType, ConnectionStatus.ACTIVE);
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

    private String normalizePaymentProvider(String provider) {
        if (provider == null || provider.isBlank()) throw new IllegalArgumentException("provider is required");
        String normalized = provider.trim().toLowerCase();
        if (!PAYMENT_PROVIDERS.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported payment provider: " + provider);
        }
        return normalized;
    }

    private String normalizeOmsProvider(String provider) {
        if (provider == null || provider.isBlank()) throw new IllegalArgumentException("provider is required");
        String normalized = provider.trim().toLowerCase();
        if (!omsConnectorRegistry.containsKey(normalized)) {
            throw new IllegalArgumentException("Unsupported OMS provider: " + provider
                    + ". Supported: " + omsConnectorRegistry.keySet());
        }
        return normalized;
    }

    private String mask(String value) {
        if (value.length() <= 8) return "****";
        return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
    }

    private Map<String, Object> toResponse(ProviderConnection connection) {
        return Map.of(
                "id", connection.getId(),
                "provider", connection.getProvider(),
                "providerType", connection.getProviderType().name(),
                "apiKey", connection.getApiKeyMasked() != null ? connection.getApiKeyMasked() : "",
                "secretStored", connection.getSecretEncrypted() != null && !connection.getSecretEncrypted().isBlank(),
                "status", connection.getStatus().name(),
                "createdAt", connection.getCreatedAt(),
                "updatedAt", connection.getUpdatedAt());
    }
}
