package com.reconciliation.oms.zoho;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconciliation.common.util.EncryptionService;
import com.reconciliation.connection.entity.ProviderConnection;
import com.reconciliation.oms.connector.OmsConnector;
import com.reconciliation.oms.connector.OmsOrder;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class ZohoOmsConnector implements OmsConnector {

    private static final String PROVIDER = "zoho_inventory";
    private static final DateTimeFormatter ZOHO_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private final ZohoApiClient apiClient;
    private final ZohoTokenManager tokenManager;
    private final EncryptionService encryptionService;
    private final ObjectMapper objectMapper;

    @Override
    public String getProvider() {
        return PROVIDER;
    }

    @Override
    public List<OmsOrder> fetchOrders(ProviderConnection connection,
                                       OffsetDateTime from, OffsetDateTime to) {
        List<JsonNode> salesOrders = apiClient.fetchSalesOrders(connection, from, to);
        List<OmsOrder> result = new ArrayList<>();

        for (JsonNode node : salesOrders) {
            try {
                result.add(mapToOmsOrder(node));
            } catch (Exception e) {
                log.warn("Failed to map Zoho sales order: {}", e.getMessage());
            }
        }

        log.info("Zoho connector fetched {} orders for connection={}", result.size(), connection.getId());
        return result;
    }

    @Override
    public void testConnection(ProviderConnection connection) {
        tokenManager.verifyCredentials(
                encryptionService.decrypt(connection.getApiKeyEncrypted()),
                encryptionService.decrypt(connection.getSecretEncrypted()),
                encryptionService.decrypt(connection.getRefreshTokenEncrypted()),
                connection.getOrganizationId());
    }

    private OmsOrder mapToOmsOrder(JsonNode node) {
        String salesorderNumber = node.path("salesorder_number").asText();
        String salesorderId = node.path("salesorder_id").asText();
        String status = node.path("status").asText();
        String currencyCode = node.path("currency_code").asText("INR");

        BigDecimal total = new BigDecimal(node.path("total").asText("0"));
        long amountInPaisa = total.multiply(BigDecimal.valueOf(100)).longValue();

        OffsetDateTime orderDate = parseDate(node.path("date").asText(null));

        @SuppressWarnings("unchecked")
        Map<String, Object> rawPayload = objectMapper.convertValue(node, Map.class);

        Map<String, Object> metadata = new HashMap<>();
        if (node.has("customer_name")) {
            metadata.put("customer_name", node.get("customer_name").asText());
        }
        if (node.has("reference_number") && !node.get("reference_number").asText().isBlank()) {
            metadata.put("reference_number", node.get("reference_number").asText());
        }

        // Zoho matching strategy: merchant puts salesorder_number in Razorpay notes.order_id at
        // payment-link creation time. Transaction.orderId then matches Order.orderId (SO number).
        // providerOrderId uses "zoho:{id}" as a stable internal deduplication key — not for payment matching.
        return new OmsOrder(
                salesorderNumber,
                "zoho:" + salesorderId,
                amountInPaisa,
                currencyCode.toUpperCase(),
                status,
                orderDate,
                rawPayload,
                metadata
        );
    }

    private OffsetDateTime parseDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) return null;
        try {
            return OffsetDateTime.parse(dateStr, ZOHO_DATE_FORMAT);
        } catch (Exception e) {
            try {
                return OffsetDateTime.parse(dateStr);
            } catch (Exception e2) {
                return null;
            }
        }
    }
}
