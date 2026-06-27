package com.reconciliation.oms.zoho;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconciliation.common.exception.InvalidProviderCredentialsException;
import com.reconciliation.common.util.EncryptionService;
import com.reconciliation.connection.entity.ProviderConnection;
import com.reconciliation.connection.repository.ProviderConnectionRepository;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

@Service
@Slf4j
public class ZohoTokenManager {

    private final EncryptionService encryptionService;
    private final ProviderConnectionRepository connectionRepository;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String tokenUrl;
    private final ConcurrentHashMap<Long, ReentrantLock> locks = new ConcurrentHashMap<>();

    public ZohoTokenManager(
            EncryptionService encryptionService,
            ProviderConnectionRepository connectionRepository,
            @Qualifier("omsRestTemplate") RestTemplate restTemplate,
            ObjectMapper objectMapper,
            @Value("${app.oms.zoho.token-url:https://accounts.zoho.com/oauth/v2/token}") String tokenUrl) {
        this.encryptionService = encryptionService;
        this.connectionRepository = connectionRepository;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.tokenUrl = tokenUrl;
    }

    public String getAccessToken(ProviderConnection connection) {
        if (connection.getAccessTokenEncrypted() != null
                && connection.getTokenExpiresAt() != null
                && connection.getTokenExpiresAt().isAfter(OffsetDateTime.now().plusMinutes(5))) {
            return encryptionService.decrypt(connection.getAccessTokenEncrypted());
        }

        ReentrantLock lock = locks.computeIfAbsent(connection.getId(), k -> new ReentrantLock());
        lock.lock();
        try {
            ProviderConnection fresh = connectionRepository.findById(connection.getId()).orElse(connection);
            if (fresh.getAccessTokenEncrypted() != null
                    && fresh.getTokenExpiresAt() != null
                    && fresh.getTokenExpiresAt().isAfter(OffsetDateTime.now().plusMinutes(5))) {
                connection.setAccessTokenEncrypted(fresh.getAccessTokenEncrypted());
                connection.setTokenExpiresAt(fresh.getTokenExpiresAt());
                return encryptionService.decrypt(fresh.getAccessTokenEncrypted());
            }
            return refreshAccessToken(connection);
        } finally {
            lock.unlock();
        }
    }

    @Transactional
    public String refreshAccessToken(ProviderConnection connection) {
        String clientId = encryptionService.decrypt(connection.getApiKeyEncrypted());
        String clientSecret = encryptionService.decrypt(connection.getSecretEncrypted());
        String refreshToken = encryptionService.decrypt(connection.getRefreshTokenEncrypted());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "refresh_token");
        body.add("client_id", clientId);
        body.add("client_secret", clientSecret);
        body.add("refresh_token", refreshToken);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    tokenUrl, new HttpEntity<>(body, headers), String.class);

            JsonNode json = objectMapper.readTree(response.getBody());
            if (json.has("error")) {
                log.error("Zoho token refresh failed: {}", json.get("error").asText());
                throw new InvalidProviderCredentialsException("zoho_inventory");
            }

            String accessToken = json.get("access_token").asText();
            long expiresIn = json.get("expires_in").asLong(3600);

            connection.setAccessTokenEncrypted(encryptionService.encrypt(accessToken));
            connection.setTokenExpiresAt(OffsetDateTime.now().plusSeconds(expiresIn));
            connectionRepository.save(connection);

            log.debug("Zoho access token refreshed for connection={}", connection.getId());
            return accessToken;
        } catch (InvalidProviderCredentialsException e) {
            throw e;
        } catch (Exception e) {
            log.error("Zoho token refresh failed: {}", e.getMessage(), e);
            throw new InvalidProviderCredentialsException("zoho_inventory");
        }
    }

    public void verifyCredentials(String clientId, String clientSecret,
                                   String refreshToken, String organizationId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "refresh_token");
        body.add("client_id", clientId);
        body.add("client_secret", clientSecret);
        body.add("refresh_token", refreshToken);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    tokenUrl, new HttpEntity<>(body, headers), String.class);

            JsonNode json = objectMapper.readTree(response.getBody());
            if (json.has("error")) {
                throw new InvalidProviderCredentialsException("zoho_inventory");
            }
        } catch (InvalidProviderCredentialsException e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidProviderCredentialsException("zoho_inventory");
        }
    }
}
