package com.reconciliation.oms.zohobooks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconciliation.common.util.EncryptionService;
import com.reconciliation.connection.entity.ProviderConnection;
import com.reconciliation.oms.connector.OmsConnector;
import com.reconciliation.oms.connector.OmsOrder;
import com.reconciliation.oms.zoho.ZohoTokenManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
public class ZohoBooksOmsConnector implements OmsConnector {

    private static final String PROVIDER = "zoho_books";
    private static final DateTimeFormatter BOOKS_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final ZohoBooksApiClient apiClient;
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
        List<JsonNode> payments = apiClient.fetchCustomerPayments(connection, from, to);
        List<OmsOrder> result = new ArrayList<>();

        for (JsonNode node : payments) {
            try {
                OmsOrder order = mapToOmsOrder(node);
                if (order != null) result.add(order);
            } catch (Exception e) {
                log.warn("Failed to map Zoho Books payment id={}: {}",
                        node.path("payment_id").asText(), e.getMessage());
            }
        }

        log.info("Zoho Books connector fetched {} payment records for connection={}",
                result.size(), connection.getId());
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
        String paymentId = node.path("payment_id").asText();
        String paymentNumber = node.path("payment_number").asText();
        String currencyCode = node.path("currency_code").asText("INR");

        BigDecimal amount = new BigDecimal(node.path("amount").asText("0"));
        long amountInPaisa = amount.multiply(BigDecimal.valueOf(100)).longValue();

        OffsetDateTime paymentDate = parseDate(node.path("date").asText(null));

        // reference_number carries the Razorpay payment ID (pay_xxx) or order ID (order_xxx)
        // set by Zoho Books' native Razorpay integration at payment time
        String referenceNumber = node.path("reference_number").asText(null);
        String providerOrderId = extractRazorpayReference(referenceNumber);

        // Use the first linked invoice number as the business order identifier
        String invoiceNumber = null;
        JsonNode invoices = node.path("invoices");
        if (invoices.isArray() && invoices.size() > 0) {
            invoiceNumber = invoices.get(0).path("invoice_number").asText(null);
        }

        // Skip payments with no recognizable order anchor
        if (invoiceNumber == null && providerOrderId == null) {
            log.debug("Skipping Zoho Books payment {} — no invoice number or Razorpay reference", paymentNumber);
            return null;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> rawPayload = objectMapper.convertValue(node, Map.class);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("payment_number", paymentNumber);
        metadata.put("payment_id", paymentId);
        if (node.has("customer_name")) {
            metadata.put("customer_name", node.get("customer_name").asText());
        }
        if (referenceNumber != null && !referenceNumber.isBlank()) {
            metadata.put("reference_number", referenceNumber);
        }

        // orderId = invoice number (links to the merchant's order in downstream reconciliation)
        // providerOrderId = pay_xxx or order_xxx from Razorpay (primary matching key)
        return new OmsOrder(
                invoiceNumber != null ? invoiceNumber : paymentNumber,
                providerOrderId,
                amountInPaisa,
                currencyCode.toUpperCase(),
                "paid",
                paymentDate,
                rawPayload,
                metadata
        );
    }

    /**
     * Zoho Books native Razorpay integration writes the Razorpay reference into
     * reference_number. It may be a payment ID (pay_xxx) or an order ID (order_xxx).
     * Both are valid matching anchors against Transaction records.
     */
    private String extractRazorpayReference(String ref) {
        if (ref == null || ref.isBlank()) return null;
        if (ref.startsWith("pay_") || ref.startsWith("order_")) return ref;
        return null;
    }

    private OffsetDateTime parseDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) return null;
        try {
            return LocalDate.parse(dateStr, BOOKS_DATE_FORMAT).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
        } catch (Exception e) {
            try {
                return OffsetDateTime.parse(dateStr);
            } catch (Exception e2) {
                return null;
            }
        }
    }
}
