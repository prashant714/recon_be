package com.reconciliation.oms.shopify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconciliation.connection.entity.ProviderConnection;
import com.reconciliation.oms.connector.OmsConnector;
import com.reconciliation.oms.connector.OmsOrder;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
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
public class ShopifyOmsConnector implements OmsConnector {

    private static final String PROVIDER = "shopify";

    private final ShopifyApiClient apiClient;
    private final ObjectMapper objectMapper;

    @Override
    public String getProvider() {
        return PROVIDER;
    }

    @Override
    public List<OmsOrder> fetchOrders(ProviderConnection connection, OffsetDateTime from, OffsetDateTime to) {
        List<JsonNode> rawOrders = apiClient.fetchOrders(connection, from, to);
        List<OmsOrder> result = new ArrayList<>();

        for (JsonNode node : rawOrders) {
            try {
                OmsOrder order = mapToOmsOrder(connection, node);
                if (order != null) result.add(order);
            } catch (Exception e) {
                log.warn("Failed to map Shopify order id={}: {}", node.path("id").asText(), e.getMessage());
            }
        }

        log.info("Shopify connector fetched {} orders for connection={}", result.size(), connection.getId());
        return result;
    }

    @Override
    public void testConnection(ProviderConnection connection) {
        apiClient.testConnection(connection);
    }

    /** Called by ShopifyWebhookService to map a single order from a webhook payload. */
    public OmsOrder mapWebhookOrder(ProviderConnection connection, JsonNode node) {
        return mapToOmsOrder(connection, node);
    }

    private OmsOrder mapToOmsOrder(ProviderConnection connection, JsonNode node) {
        String financialStatus = node.path("financial_status").asText();
        if (!"paid".equalsIgnoreCase(financialStatus) && !"partially_paid".equalsIgnoreCase(financialStatus)) {
            return null;
        }

        String orderName = node.path("name").asText();          // "#1001" — human-readable
        String shopifyOrderId = node.path("id").asText();
        String currency = node.path("currency").asText("INR");

        BigDecimal total = new BigDecimal(node.path("total_price").asText("0"));
        long amountInSmallestUnit = total.multiply(BigDecimal.valueOf(100)).longValue();

        OffsetDateTime orderDate = parseDate(node.path("processed_at").asText(null));

        @SuppressWarnings("unchecked")
        Map<String, Object> rawPayload = objectMapper.convertValue(node, Map.class);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("shopify_order_id", shopifyOrderId);
        metadata.put("order_name", orderName);
        metadata.put("financial_status", financialStatus);

        String providerOrderId = extractRazorpayPaymentId(connection, node);
        if (providerOrderId != null) {
            metadata.put("razorpay_payment_id", providerOrderId);
            log.info("Shopify order={} linked to Razorpay payment={}", orderName, providerOrderId);
        } else {
            log.debug("Shopify order={} has no Razorpay payment ID (gateway={})",
                    orderName, node.path("payment_gateway").asText("unknown"));
        }

        return new OmsOrder(
                orderName,          // orderId = Shopify order name (#1001)
                providerOrderId,    // pay_xxx from the order's Razorpay transaction; null for other gateways
                amountInSmallestUnit,
                currency.toUpperCase(),
                financialStatus,
                orderDate,
                rawPayload,
                metadata
        );
    }

    private String extractRazorpayPaymentId(ProviderConnection connection, JsonNode orderNode) {
        // payment_gateway_names (array) is the current field; payment_gateway is the legacy single-value field
        boolean isRazorpay = false;
        JsonNode gatewayNames = orderNode.path("payment_gateway_names");
        if (gatewayNames.isArray()) {
            for (JsonNode g : gatewayNames) {
                if (g.asText("").toLowerCase().contains("razorpay")) {
                    isRazorpay = true;
                    break;
                }
            }
        }
        if (!isRazorpay) {
            isRazorpay = orderNode.path("payment_gateway").asText("").toLowerCase().contains("razorpay");
        }
        if (!isRazorpay) return null;

        // Shopify includes a `transactions` array directly in the webhook body — check it first.
        // This avoids a separate API call and works even before the transactions API is updated.
        String fromBody = extractFromTransactionNodes(orderNode.path("transactions"));
        if (fromBody != null) {
            log.debug("Extracted Razorpay payment ID from webhook body transactions: {}", fromBody);
            return fromBody;
        }

        // Webhook body had no transactions or empty receipts — fall back to API call (used for polling path).
        long shopifyOrderId = orderNode.path("id").asLong();
        List<JsonNode> transactions = apiClient.fetchTransactions(connection, shopifyOrderId);
        return extractFromTransactionNodes(objectMapper.valueToTree(transactions));
    }

    private String extractFromTransactionNodes(JsonNode txnArray) {
        if (txnArray == null || !txnArray.isArray()) return null;
        for (JsonNode txn : txnArray) {
            if (!"success".equalsIgnoreCase(txn.path("status").asText())) continue;
            String kind = txn.path("kind").asText("");
            if (!"capture".equalsIgnoreCase(kind) && !"sale".equalsIgnoreCase(kind)) continue;

            // Shopify stores Razorpay's payment_id in receipt.razorpay_payment_id
            String fromReceipt = txn.path("receipt").path("razorpay_payment_id").asText(null);
            if (fromReceipt != null && fromReceipt.startsWith("pay_")) return fromReceipt;

            // Fallback: Shopify also puts the gateway's auth code in authorization
            String authorization = txn.path("authorization").asText(null);
            if (authorization != null && authorization.startsWith("pay_")) return authorization;
        }
        return null;
    }

    private OffsetDateTime parseDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) return null;
        try {
            return OffsetDateTime.parse(dateStr);
        } catch (Exception e) {
            return null;
        }
    }
}
