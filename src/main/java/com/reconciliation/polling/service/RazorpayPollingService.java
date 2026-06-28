package com.reconciliation.polling.service;

import com.razorpay.Entity;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class RazorpayPollingService {

    @Value("${app.razorpay.key-id:}")
    private String defaultKeyId;

    @Value("${app.razorpay.key-secret:}")
    private String defaultKeySecret;

    private static final int PAGE_SIZE = 100;
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 1000;

    public List<byte[]> fetchPayments(String keyId, String keySecret, OffsetDateTime from, OffsetDateTime to) {
        log.debug("Polling Razorpay payments from={} to={}", from, to);
        return fetchPaginated(keyId, keySecret, "payments", from, to);
    }

    public List<byte[]> fetchRefunds(String keyId, String keySecret, OffsetDateTime from, OffsetDateTime to) {
        log.debug("Polling Razorpay refunds from={} to={}", from, to);
        return fetchPaginated(keyId, keySecret, "refunds", from, to);
    }

    public List<byte[]> fetchPayments(OffsetDateTime from, OffsetDateTime to) {
        return fetchPayments(defaultKeyId, defaultKeySecret, from, to);
    }

    public List<byte[]> fetchRefunds(OffsetDateTime from, OffsetDateTime to) {
        return fetchRefunds(defaultKeyId, defaultKeySecret, from, to);
    }

    public List<Map<String, Object>> fetchPaymentsBySettlementId(String keyId, String keySecret, String settlementId) {
        List<Map<String, Object>> lines = new ArrayList<>();
        RazorpayClient client = createClient(keyId, keySecret);
        if (client == null) {
            log.info("Skipping settlement report fetch — invalid Razorpay credentials");
            return lines;
        }

        int skip = 0;
        while (true) {
            try {
                org.json.JSONObject options = new org.json.JSONObject();
                options.put("settlement_id", settlementId);
                options.put("count", PAGE_SIZE);
                options.put("skip", skip);

                List<? extends Entity> payments = client.payments.fetchAll(options);
                if (payments == null || payments.isEmpty()) break;

                for (Entity p : payments) {
                    org.json.JSONObject item = p.toJson();
                    long amount = item.optLong("amount", 0L);
                    long fee = item.optLong("fee", 0L);
                    long tax = item.optLong("tax", 0L);
                    long net = amount - fee - tax;

                    Map<String, Object> line = new LinkedHashMap<>();
                    line.put("providerTxnId", item.optString("id"));
                    line.put("entityType", "payment");
                    line.put("grossAmount", amount);
                    line.put("feeAmount", fee + tax);
                    line.put("netAmount", net);
                    line.put("currency", item.optString("currency", "INR").toUpperCase());
                    lines.add(line);
                }

                if (payments.size() < PAGE_SIZE) break;
                skip += PAGE_SIZE;

            } catch (Exception e) {
                log.error("Failed to fetch settlement report for settlementId={}: {}", settlementId, e.getMessage());
                break;
            }
        }
        return lines;
    }

    public List<Map<String, Object>> fetchPaymentsBySettlementId(String settlementId) {
        return fetchPaymentsBySettlementId(defaultKeyId, defaultKeySecret, settlementId);
    }

    private List<byte[]> fetchPaginated(String keyId, String keySecret, String entity, OffsetDateTime from, OffsetDateTime to) {
        List<byte[]> results = new ArrayList<>();
        RazorpayClient client = createClient(keyId, keySecret);
        if (client == null) {
            log.info("Skipping Razorpay {} polling — invalid credentials", entity);
            return results;
        }

        int skip = 0;
        while (true) {
            try {
                org.json.JSONObject options = new org.json.JSONObject();
                options.put("from", from.toEpochSecond());
                options.put("to", to.toEpochSecond());
                options.put("count", PAGE_SIZE);
                options.put("skip", skip);

                org.json.JSONArray items = fetchWithRetry(client, entity, options);

                if (items == null || items.length() == 0) break;

                for (int i = 0; i < items.length(); i++) {
                    org.json.JSONObject item = items.getJSONObject(i);
                    String eventType = eventTypeFor(entity, item);
                    if (eventType == null) continue;

                    org.json.JSONObject envelope = new org.json.JSONObject();
                    envelope.put("id", syntheticEventId(entity, item));
                    envelope.put("event", eventType);
                    envelope.put("payload", new org.json.JSONObject()
                            .put(entity.equals("payments") ? "payment" : "refund",
                                    new org.json.JSONObject().put("entity", item)));

                    results.add(envelope.toString().getBytes());
                }

                if (items.length() < PAGE_SIZE) break;
                skip += PAGE_SIZE;

            } catch (Exception e) {
                log.error("Razorpay polling failed for {}: {}", entity, e.getMessage());
                break;
            }
        }

        return results;
    }

    /**
     * Fetches the notes map from a Razorpay order. Shopify sets these notes when it creates
     * the Razorpay order during checkout — they typically contain the Shopify order reference.
     */
    public Map<String, String> fetchOrderNotes(String keyId, String keySecret, String razorpayOrderId) {
        RazorpayClient client = createClient(keyId, keySecret);
        if (client == null) return Map.of();
        try {
            com.razorpay.Order order = client.orders.fetch(razorpayOrderId);
            org.json.JSONObject json = order.toJson();
            org.json.JSONObject notes = json.optJSONObject("notes");
            log.info("Razorpay order={} receipt={} notes={}", razorpayOrderId,
                    json.optString("receipt", null), notes);
            if (notes == null) return Map.of();
            Map<String, String> result = new LinkedHashMap<>();
            for (String key : notes.keySet()) {
                result.put(key, notes.optString(key));
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to fetch Razorpay order {}: {}", razorpayOrderId, e.getMessage());
            return Map.of();
        }
    }

    private RazorpayClient createClient(String keyId, String keySecret) {
        if (!hasText(keyId) || !hasText(keySecret)
                || keyId.contains("placeholder") || keySecret.contains("placeholder")) {
            return null;
        }
        try {
            return new RazorpayClient(keyId, keySecret);
        } catch (RazorpayException e) {
            log.error("Failed to create RazorpayClient: {}", e.getMessage());
            return null;
        }
    }

    private org.json.JSONArray fetchWithRetry(RazorpayClient client, String entity, org.json.JSONObject options) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                List<? extends Entity> items = "payments".equals(entity)
                        ? client.payments.fetchAll(options)
                        : client.refunds.fetchAll(options);

                org.json.JSONArray jsonArray = new org.json.JSONArray();
                for (Entity item : items) {
                    jsonArray.put(item.toJson());
                }
                return jsonArray;
            } catch (Exception e) {
                if (attempt == MAX_RETRIES) throw new RuntimeException(e);
                log.warn("Razorpay API retry {}/{}: {}", attempt, MAX_RETRIES, e.getMessage());
                try { Thread.sleep(RETRY_DELAY_MS * attempt); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        }
        return null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String syntheticEventId(String entity, org.json.JSONObject item) {
        String objectId = item.optString("id", null);
        if (hasText(objectId)) {
            return "poll_" + entity + "_" + objectId;
        }
        return "poll_" + entity + "_" + java.util.UUID.randomUUID();
    }

    private String eventTypeFor(String entity, org.json.JSONObject item) {
        if ("refunds".equals(entity)) {
            return "refund.processed";
        }
        String status = item.optString("status", "");
        return switch (status) {
            case "captured" -> "payment.captured";
            case "authorized" -> "payment.authorized";
            case "failed" -> "payment.failed";
            default -> null;
        };
    }
}
