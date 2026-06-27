package com.reconciliation.oms.shopify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconciliation.common.exception.InvalidProviderCredentialsException;
import com.reconciliation.common.util.EncryptionService;
import com.reconciliation.connection.entity.ProviderConnection;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Service
@Slf4j
public class ShopifyApiClient {

    private static final int PAGE_SIZE = 250;
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 1000;
    private static final DateTimeFormatter ISO_FORMAT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final EncryptionService encryptionService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String apiVersion;

    public ShopifyApiClient(
            EncryptionService encryptionService,
            @Qualifier("omsRestTemplate") RestTemplate restTemplate,
            ObjectMapper objectMapper,
            @Value("${app.oms.shopify.api-version:2024-01}") String apiVersion) {
        this.encryptionService = encryptionService;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.apiVersion = apiVersion;
    }

    public List<JsonNode> fetchOrders(ProviderConnection connection, OffsetDateTime from, OffsetDateTime to) {
        String shopDomain = encryptionService.decrypt(connection.getApiKeyEncrypted());
        String accessToken = encryptionService.decrypt(connection.getSecretEncrypted());

        List<JsonNode> allOrders = new ArrayList<>();
        String nextUrl = UriComponentsBuilder
                .fromHttpUrl("https://" + shopDomain + "/admin/api/" + apiVersion + "/orders.json")
                .queryParam("status", "any")
                .queryParam("updated_at_min", from.format(ISO_FORMAT))
                .queryParam("updated_at_max", to.format(ISO_FORMAT))
                .queryParam("limit", PAGE_SIZE)
                .queryParam("fields", "id,name,financial_status,payment_gateway,payment_gateway_names,total_price,currency,processed_at,updated_at")
                .build()
                .toUriString();

        while (nextUrl != null) {
            ResponseEntity<String> response = fetchWithRetry(nextUrl, accessToken);
            if (response == null) break;

            try {
                JsonNode body = objectMapper.readTree(response.getBody());
                JsonNode orders = body.path("orders");
                if (orders.isArray()) {
                    for (JsonNode order : orders) {
                        allOrders.add(order);
                    }
                }
            } catch (Exception e) {
                log.error("Failed to parse Shopify orders response: {}", e.getMessage());
                break;
            }

            nextUrl = extractNextPageUrl(response.getHeaders());
        }

        return allOrders;
    }

    public List<JsonNode> fetchTransactions(ProviderConnection connection, long orderId) {
        String shopDomain = encryptionService.decrypt(connection.getApiKeyEncrypted());
        String accessToken = encryptionService.decrypt(connection.getSecretEncrypted());

        String url = "https://" + shopDomain + "/admin/api/" + apiVersion
                + "/orders/" + orderId + "/transactions.json";

        ResponseEntity<String> response = fetchWithRetry(url, accessToken);
        if (response == null) return List.of();

        try {
            JsonNode body = objectMapper.readTree(response.getBody());
            JsonNode transactions = body.path("transactions");
            List<JsonNode> result = new ArrayList<>();
            if (transactions.isArray()) {
                for (JsonNode txn : transactions) {
                    result.add(txn);
                }
            }
            return result;
        } catch (Exception e) {
            log.error("Failed to parse Shopify transactions for order={}: {}", orderId, e.getMessage());
            return List.of();
        }
    }

    // Called from ProviderCredentialVerifier with raw (unencrypted) values during initial setup
    public void verifyCredentials(String shopDomain, String accessToken) {
        String url = "https://" + shopDomain + "/admin/api/" + apiVersion + "/shop.json";
        ResponseEntity<String> response = fetchWithRetry(url, accessToken);
        if (response == null || !response.getStatusCode().is2xxSuccessful()) {
            throw new InvalidProviderCredentialsException("shopify");
        }
    }

    // Called from ShopifyOmsConnector.testConnection with a stored ProviderConnection
    public void testConnection(ProviderConnection connection) {
        String shopDomain = encryptionService.decrypt(connection.getApiKeyEncrypted());
        String accessToken = encryptionService.decrypt(connection.getSecretEncrypted());
        verifyCredentials(shopDomain, accessToken);
    }

    private ResponseEntity<String> fetchWithRetry(String url, String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Shopify-Access-Token", accessToken);
        HttpEntity<?> entity = new HttpEntity<>(headers);

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                return restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            } catch (HttpClientErrorException.TooManyRequests e) {
                String retryAfter = e.getResponseHeaders() != null
                        ? e.getResponseHeaders().getFirst("Retry-After") : null;
                long delayMs = retryAfter != null
                        ? Long.parseLong(retryAfter) * 1000L
                        : RETRY_DELAY_MS * attempt * 2;
                log.warn("Shopify rate limited — backing off {}ms (attempt {})", delayMs, attempt);
                sleep(delayMs);
            } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden e) {
                throw new InvalidProviderCredentialsException("shopify");
            } catch (Exception e) {
                if (attempt == MAX_RETRIES) {
                    log.error("Shopify API call failed after {} retries: {}", MAX_RETRIES, e.getMessage());
                    return null;
                }
                log.warn("Shopify API call failed (attempt {}): {}", attempt, e.getMessage());
                sleep(RETRY_DELAY_MS * attempt);
            }
        }
        return null;
    }

    // Shopify uses cursor-based pagination via the Link response header
    private String extractNextPageUrl(HttpHeaders headers) {
        String linkHeader = headers.getFirst(HttpHeaders.LINK);
        if (linkHeader == null) return null;
        for (String part : linkHeader.split(",")) {
            if (part.contains("rel=\"next\"")) {
                int start = part.indexOf('<');
                int end = part.indexOf('>');
                if (start >= 0 && end > start) {
                    return part.substring(start + 1, end).trim();
                }
            }
        }
        return null;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
