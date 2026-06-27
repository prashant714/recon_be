package com.reconciliation.oms.shopify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconciliation.common.enums.ConnectionStatus;
import com.reconciliation.common.enums.ProviderType;
import com.reconciliation.common.util.EncryptionService;
import com.reconciliation.connection.entity.ProviderConnection;
import com.reconciliation.connection.repository.ProviderConnectionRepository;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

@Service
@Slf4j
public class ShopifyOAuthService {

    private static final long STATE_EXPIRY_MS = 10 * 60 * 1000; // 10 minutes

    private final ProviderConnectionRepository connectionRepository;
    private final EncryptionService encryptionService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;
    private final String scopes;

    // state → {merchantId, shopDomain, expiresAt}
    private final ConcurrentHashMap<String, PendingOAuth> pendingStates = new ConcurrentHashMap<>();

    public ShopifyOAuthService(
            ProviderConnectionRepository connectionRepository,
            EncryptionService encryptionService,
            @Qualifier("omsRestTemplate") RestTemplate restTemplate,
            ObjectMapper objectMapper,
            @Value("${app.oms.shopify.client-id}") String clientId,
            @Value("${app.oms.shopify.client-secret}") String clientSecret,
            @Value("${app.oms.shopify.redirect-uri}") String redirectUri,
            @Value("${app.oms.shopify.scopes:read_orders,read_transactions}") String scopes) {
        this.connectionRepository = connectionRepository;
        this.encryptionService = encryptionService;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.redirectUri = redirectUri;
        this.scopes = scopes;
    }

    /** Returns the Shopify authorization URL to redirect the merchant to. */
    public String initiateOAuth(String merchantId, String shop) {
        String normalizedShop = normalizeShopDomain(shop);
        String state = UUID.randomUUID().toString().replace("-", "");
        pendingStates.put(state, new PendingOAuth(merchantId, normalizedShop,
                System.currentTimeMillis() + STATE_EXPIRY_MS));

        return "https://" + normalizedShop + "/admin/oauth/authorize"
                + "?client_id=" + clientId
                + "&scope=" + scopes
                + "&redirect_uri=" + redirectUri
                + "&state=" + state;
    }

    /** JWT-secured manual exchange — skips state/HMAC check, used for initial setup. */
    @Transactional
    public void exchangeAndSave(String merchantId, String shop, String code) {
        String normalizedShop = normalizeShopDomain(shop);
        String accessToken = exchangeCodeForToken(normalizedShop, code);
        saveConnection(merchantId, normalizedShop, accessToken);
        log.info("Shopify manual exchange completed for merchant={} shop={}", merchantId, normalizedShop);
    }

    /** Called by the OAuth callback — verifies HMAC + state, exchanges code, saves connection. */
    @Transactional
    public String handleCallback(String shop, String code, String state, String hmac,
                                  Map<String, String> allParams) {
        cleanExpiredStates();

        PendingOAuth pending = pendingStates.remove(state);
        if (pending == null || pending.isExpired()) {
            throw new IllegalArgumentException("Invalid or expired OAuth state");
        }

        String normalizedShop = normalizeShopDomain(shop);
        if (!normalizedShop.equals(pending.shopDomain())) {
            throw new IllegalArgumentException("Shop domain mismatch in OAuth callback");
        }

        if (!verifyCallbackHmac(hmac, allParams)) {
            throw new SecurityException("Shopify OAuth callback HMAC verification failed");
        }

        String accessToken = exchangeCodeForToken(normalizedShop, code);
        saveConnection(pending.merchantId(), normalizedShop, accessToken);

        log.info("Shopify OAuth completed for merchant={} shop={}", pending.merchantId(), normalizedShop);
        return pending.merchantId();
    }

    private String exchangeCodeForToken(String shop, String code) {
        String url = "https://" + shop + "/admin/oauth/access_token";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, String> body = Map.of(
                "client_id", clientId,
                "client_secret", clientSecret,
                "code", code);

        try {
            String response = restTemplate.postForObject(
                    url, new HttpEntity<>(body, headers), String.class);
            JsonNode json = objectMapper.readTree(response);
            String token = json.path("access_token").asText(null);
            if (token == null || token.isBlank()) {
                throw new RuntimeException("No access_token in Shopify OAuth response");
            }
            return token;
        } catch (Exception e) {
            log.error("Shopify token exchange failed for shop={}: {}", shop, e.getMessage());
            throw new RuntimeException("Shopify OAuth token exchange failed: " + e.getMessage());
        }
    }

    private void saveConnection(String merchantId, String shopDomain, String accessToken) {
        ProviderConnection connection = connectionRepository
                .findByMerchantIdAndProvider(merchantId, "shopify")
                .orElseGet(() -> ProviderConnection.builder()
                        .merchantId(merchantId)
                        .provider("shopify")
                        .providerType(ProviderType.OMS)
                        .build());

        connection.setApiKeyEncrypted(encryptionService.encrypt(shopDomain));
        connection.setSecretEncrypted(encryptionService.encrypt(accessToken));
        connection.setApiKeyMasked(shopDomain);
        connection.setOrganizationId(shopDomain);
        connection.setStatus(ConnectionStatus.ACTIVE);
        connection.setProviderType(ProviderType.OMS);
        connectionRepository.save(connection);
    }

    // Shopify signs callback params with HMAC-SHA256(clientSecret) as hex — excludes the hmac param itself
    private boolean verifyCallbackHmac(String hmac, Map<String, String> allParams) {
        if (hmac == null || hmac.isBlank()) return false;
        try {
            TreeMap<String, String> sorted = new TreeMap<>(allParams);
            sorted.remove("hmac");

            StringBuilder message = new StringBuilder();
            sorted.forEach((k, v) -> {
                if (message.length() > 0) message.append('&');
                message.append(k).append('=').append(v);
            });

            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(clientSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(message.toString().getBytes(StandardCharsets.UTF_8));

            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));

            return hex.toString().equals(hmac);
        } catch (Exception e) {
            log.error("HMAC verification error: {}", e.getMessage());
            return false;
        }
    }

    private String normalizeShopDomain(String shop) {
        if (shop == null) throw new IllegalArgumentException("shop is required");
        return shop.trim().toLowerCase()
                .replace("https://", "")
                .replace("http://", "")
                .replaceAll("/$", "");
    }

    private void cleanExpiredStates() {
        long now = System.currentTimeMillis();
        pendingStates.entrySet().removeIf(e -> e.getValue().expiresAt() < now);
    }

    private record PendingOAuth(String merchantId, String shopDomain, long expiresAt) {
        boolean isExpired() { return System.currentTimeMillis() > expiresAt; }
    }
}
