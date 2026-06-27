package com.reconciliation.oms.zoho;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
public class ZohoApiClient {

    private static final int PAGE_SIZE = 200;
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 1000;
    private static final DateTimeFormatter ZOHO_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private final ZohoTokenManager tokenManager;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String apiBaseUrl;

    public ZohoApiClient(
            ZohoTokenManager tokenManager,
            @Qualifier("omsRestTemplate") RestTemplate restTemplate,
            ObjectMapper objectMapper,
            @Value("${app.oms.zoho.api-base-url:https://www.zohoapis.com}") String apiBaseUrl) {
        this.tokenManager = tokenManager;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.apiBaseUrl = apiBaseUrl;
    }

    public List<JsonNode> fetchSalesOrders(ProviderConnection connection,
                                            OffsetDateTime from, OffsetDateTime to) {
        List<JsonNode> allOrders = new ArrayList<>();
        int page = 1;
        boolean hasMore = true;

        while (hasMore) {
            JsonNode response = fetchPage(connection, from, to, page);
            if (response == null) break;

            JsonNode salesOrders = response.path("salesorders");
            if (salesOrders.isArray()) {
                for (JsonNode order : salesOrders) {
                    allOrders.add(order);
                }
            }

            JsonNode pageContext = response.path("page_context");
            hasMore = pageContext.path("has_more_page").asBoolean(false);
            page++;
        }

        return allOrders;
    }

    private JsonNode fetchPage(ProviderConnection connection,
                                OffsetDateTime from, OffsetDateTime to, int page) {
        String orgId = connection.getOrganizationId();

        String url = UriComponentsBuilder.fromHttpUrl(apiBaseUrl + "/inventory/v1/salesorders")
                .queryParam("organization_id", orgId)
                .queryParam("last_modified_time_start", from.format(ZOHO_DATE_FORMAT))
                .queryParam("last_modified_time_end", to.format(ZOHO_DATE_FORMAT))
                .queryParam("page", page)
                .queryParam("per_page", PAGE_SIZE)
                .queryParam("sort_column", "last_modified_time")
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
                log.warn("Zoho 401 — refreshing token (attempt {})", attempt);
                tokenManager.refreshAccessToken(connection);
            } catch (HttpClientErrorException.TooManyRequests e) {
                log.warn("Zoho rate limited — backing off (attempt {})", attempt);
                sleep(RETRY_DELAY_MS * attempt * 2);
            } catch (Exception e) {
                if (attempt == MAX_RETRIES) {
                    log.error("Zoho API call failed after {} retries: {}", MAX_RETRIES, e.getMessage());
                    return null;
                }
                log.warn("Zoho API call failed (attempt {}): {}", attempt, e.getMessage());
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
