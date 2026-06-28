package com.reconciliation.webhook.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconciliation.common.enums.ConnectionStatus;
import com.reconciliation.connection.entity.ProviderConnection;
import com.reconciliation.connection.repository.ProviderConnectionRepository;
import com.reconciliation.oms.connector.OmsOrder;
import com.reconciliation.oms.service.OmsOrderIngestionService;
import com.reconciliation.oms.shopify.ShopifyOmsConnector;
import com.reconciliation.order.service.OrderMatchingService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class ShopifyWebhookService {

    private static final String PROVIDER = "shopify";

    private final ProviderConnectionRepository connectionRepository;
    private final ShopifyOmsConnector shopifyConnector;
    private final OmsOrderIngestionService ingestionService;
    private final OrderMatchingService orderMatchingService;
    private final ObjectMapper objectMapper;
    private final String webhookSecret;

    public ShopifyWebhookService(
            ProviderConnectionRepository connectionRepository,
            ShopifyOmsConnector shopifyConnector,
            OmsOrderIngestionService ingestionService,
            OrderMatchingService orderMatchingService,
            ObjectMapper objectMapper,
            @Value("${app.oms.shopify.webhook-secret}") String webhookSecret) {
        this.connectionRepository = connectionRepository;
        this.shopifyConnector = shopifyConnector;
        this.ingestionService = ingestionService;
        this.orderMatchingService = orderMatchingService;
        this.objectMapper = objectMapper;
        this.webhookSecret = webhookSecret;
    }

    /**
     * Verify the Shopify HMAC, map the order, and ingest it.
     * Returns true if the event was accepted (valid signature + handled topic).
     */
    public boolean handle(byte[] rawBody, String hmacHeader, String shopDomain, String topic) {
        Optional<ProviderConnection> connectionOpt = connectionRepository
                .findByProviderAndOrganizationIdAndStatus(PROVIDER, shopDomain, ConnectionStatus.ACTIVE);

        if (connectionOpt.isEmpty()) {
            log.warn("Shopify webhook from unknown shop={} — no active connection found", shopDomain);
            return false;
        }

        ProviderConnection connection = connectionOpt.get();

        if (!verifyHmac(rawBody, hmacHeader, connection)) {
            log.warn("Shopify webhook HMAC verification failed for shop={}", shopDomain);
            return false;
        }

        if ("order_transactions/create".equals(topic)) {
            return handleTransaction(rawBody, connection, shopDomain);
        }

        if (!isOrderTopic(topic)) {
            log.debug("Shopify webhook topic={} not handled — ignoring", topic);
            return true;
        }

        try {
            JsonNode orderNode = objectMapper.readTree(rawBody);
            OmsOrder order = shopifyConnector.mapWebhookOrder(connection, orderNode);

            if (order == null) {
                log.debug("Shopify webhook topic={} shop={} — order not eligible (unpaid)", topic, shopDomain);
                return true;
            }

            ingestionService.ingest(connection.getMerchantId(), PROVIDER, List.of(order));
            log.info("Shopify webhook processed topic={} shop={} orderId={}",
                    topic, shopDomain, order.orderId());
        } catch (Exception e) {
            log.error("Shopify webhook processing failed topic={} shop={}: {}",
                    topic, shopDomain, e.getMessage(), e);
            return false;
        }

        return true;
    }

    private boolean handleTransaction(byte[] rawBody, ProviderConnection connection, String shopDomain) {
        try {
            JsonNode txn = objectMapper.readTree(rawBody);
            String kind          = txn.path("kind").asText("");
            String status        = txn.path("status").asText("");
            String shopifyOrderId = txn.path("order_id").asText(null);
            String authorization  = txn.path("authorization").asText(null);
            String gateway        = txn.path("gateway").asText(null);

            log.info("order_transactions/create: received kind={} status={} shopifyOrderId={} authorization={} gateway={} shop={}",
                    kind, status, shopifyOrderId, authorization, gateway, shopDomain);

            if (!"capture".equalsIgnoreCase(kind) && !"sale".equalsIgnoreCase(kind)) {
                log.info("order_transactions/create: skipping kind={} (not capture/sale)", kind);
                return true;
            }
            if (!"success".equalsIgnoreCase(status)) {
                log.info("order_transactions/create: skipping status={} (not success)", status);
                return true;
            }
            if (shopifyOrderId == null) return true;

            String paymentId = authorization != null ? extractPaymentId(authorization) : null;

            if (paymentId == null && gateway != null && gateway.toLowerCase().contains("razorpay")) {
                log.info("order_transactions/create: authorization null/unparseable — fetching pay_id from Shopify API for shopifyOrderId={}", shopifyOrderId);
                paymentId = shopifyConnector.fetchRazorpayPaymentId(connection, Long.parseLong(shopifyOrderId));
            }

            if (paymentId == null) {
                log.info("order_transactions/create: no pay_ ID or PaymentSession token found (authorization={} gateway={}) shop={}", authorization, gateway, shopDomain);
                return true;
            }

            if (paymentId.startsWith("pay_")) {
                log.info("order_transactions/create: shopifyOrderId={} paymentId={} shop={}", shopifyOrderId, paymentId, shopDomain);
                orderMatchingService.linkTransactionToOmsOrder(connection.getMerchantId(), shopifyOrderId, paymentId);
            } else {
                // Cards Onsite by 1Razorpay: paymentId is a Shopify PaymentSession token.
                // The same token appears in Razorpay order notes.shopify_order_id — use it to bridge order↔transaction.
                log.info("order_transactions/create: shopifyOrderId={} paymentSessionToken={} shop={}", shopifyOrderId, paymentId, shopDomain);
                orderMatchingService.linkTransactionToOmsOrderByToken(connection.getMerchantId(), shopifyOrderId, paymentId);
            }
        } catch (Exception e) {
            log.error("Shopify order_transactions/create processing failed shop={}: {}", shopDomain, e.getMessage(), e);
            return false;
        }
        return true;
    }

    // authorization field for Razorpay: "order_xxx|pay_xxx" or just "pay_xxx"
    private String extractPaymentId(String authorization) {
        if (authorization.contains("|")) {
            for (String part : authorization.split("\\|")) {
                if (part.startsWith("pay_")) return part;
            }
        }
        return authorization.startsWith("pay_") ? authorization : null;
    }

    // Shopify signs webhooks with HMAC-SHA256 using the webhook signing secret, Base64-encoded
    private boolean verifyHmac(byte[] rawBody, String hmacHeader, ProviderConnection connection) {
        if (hmacHeader == null || hmacHeader.isBlank()) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String computed = Base64.getEncoder().encodeToString(mac.doFinal(rawBody));
            return MessageDigest.isEqual(
                    computed.getBytes(StandardCharsets.UTF_8),
                    hmacHeader.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("Shopify HMAC computation failed: {}", e.getMessage());
            return false;
        }
    }

    private boolean isOrderTopic(String topic) {
        return "orders/paid".equals(topic) || "orders/updated".equals(topic);
    }
}
