package com.reconciliation.oms.zohobooks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconciliation.connection.entity.ProviderConnection;
import com.reconciliation.oms.zoho.ZohoTokenManager;
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
public class ZohoBooksApiClient {

    private static final int PAGE_SIZE = 200;
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 1000;
    // Zoho Books date_start / date_end parameters use yyyy-MM-dd
    private static final DateTimeFormatter DATE_PARAM_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final ZohoTokenManager tokenManager;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String apiBaseUrl;

    public ZohoBooksApiClient(
            ZohoTokenManager tokenManager,
            @Qualifier("omsRestTemplate") RestTemplate restTemplate,
            ObjectMapper objectMapper,
            @Value("${app.oms.zoho.api-base-url:https://www.zohoapis.com}") String apiBaseUrl) {
        this.tokenManager = tokenManager;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.apiBaseUrl = apiBaseUrl;
    }

    public List<JsonNode> fetchCustomerPayments(ProviderConnection connection,
                                                 OffsetDateTime from, OffsetDateTime to) {
        List<JsonNode> all = new ArrayList<>();
        int page = 1;
        boolean hasMore = true;

        while (hasMore) {
            JsonNode response = fetchPage(connection, from, to, page);
            if (response == null) break;

            JsonNode payments = response.path("customerpayments");
            if (payments.isArray()) {
                for (JsonNode p : payments) {
                    all.add(p);
                }
            }

            hasMore = response.path("page_context").path("has_more_page").asBoolean(false);
            page++;
        }

        return all;
    }

    private JsonNode fetchPage(ProviderConnection connection,
                                OffsetDateTime from, OffsetDateTime to, int page) {
        String orgId = connection.getOrganizationId();

        String url = UriComponentsBuilder.fromHttpUrl(apiBaseUrl + "/books/v3/customerpayments")
                .queryParam("organization_id", orgId)
                .queryParam("date_start", from.format(DATE_PARAM_FORMAT))
                .queryParam("date_end", to.format(DATE_PARAM_FORMAT))
                .queryParam("page", page)
                .queryParam("per_page", PAGE_SIZE)
                .queryParam("sort_column", "date")
                .queryParam("sort_order", "ascending")
                .build()
                .toUriString();

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                String accessToken = tokenManager.getAccessToken(connection);
                HttpHeaders headers = new HttpHeaders();
                headers.set("Authorization", "Zoho-oauthtoken " + accessToken);

                ResponseEntity<String> response = restTemplate.exchange(
                        url, HttpMethod.GET, new HttpEntity<>(headers), String.class);

                return objectMapper.readTree(response.getBody());

            } catch (HttpClientErrorException.Unauthorized e) {
                log.warn("Zoho Books 401 — refreshing token (attempt {})", attempt);
                tokenManager.refreshAccessToken(connection);
            } catch (HttpClientErrorException.TooManyRequests e) {
                log.warn("Zoho Books rate limited — backing off (attempt {})", attempt);
                sleep(RETRY_DELAY_MS * attempt * 2);
            } catch (Exception e) {
                if (attempt == MAX_RETRIES) {
                    log.error("Zoho Books API call failed after {} retries: {}", MAX_RETRIES, e.getMessage());
                    return null;
                }
                log.warn("Zoho Books API call failed (attempt {}): {}", attempt, e.getMessage());
                sleep(RETRY_DELAY_MS * attempt);
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
