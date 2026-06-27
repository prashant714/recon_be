package com.reconciliation.webhook.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconciliation.common.enums.ConnectionStatus;
import com.reconciliation.connection.entity.ProviderConnection;
import com.reconciliation.connection.repository.ProviderConnectionRepository;
import com.reconciliation.oms.connector.OmsOrder;
import com.reconciliation.oms.service.OmsOrderIngestionService;
import com.reconciliation.oms.shopify.ShopifyOmsConnector;
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
    private final ObjectMapper objectMapper;
    private final String clientSecret;

    public ShopifyWebhookService(
            ProviderConnectionRepository connectionRepository,
            ShopifyOmsConnector shopifyConnector,
            OmsOrderIngestionService ingestionService,
            ObjectMapper objectMapper,
            @Value("${app.oms.shopify.client-secret}") String clientSecret) {
        this.connectionRepository = connectionRepository;
        this.shopifyConnector = shopifyConnector;
        this.ingestionService = ingestionService;
        this.objectMapper = objectMapper;
        this.clientSecret = clientSecret;
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

        if (!isHandledTopic(topic)) {
            log.debug("Shopify webhook topic={} not handled — ignoring", topic);
            return true; // return 200 so Shopify doesn't retry unhandled topics
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

    // Shopify signs webhooks with HMAC-SHA256 using the app's client secret, Base64-encoded
    private boolean verifyHmac(byte[] rawBody, String hmacHeader, ProviderConnection connection) {
        if (hmacHeader == null || hmacHeader.isBlank()) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(clientSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String computed = Base64.getEncoder().encodeToString(mac.doFinal(rawBody));
            return MessageDigest.isEqual(
                    computed.getBytes(StandardCharsets.UTF_8),
                    hmacHeader.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("Shopify HMAC computation failed: {}", e.getMessage());
            return false;
        }
    }

    // orders/paid fires when financial_status becomes paid
    // orders/updated catches partial_paid and any subsequent status changes
    private boolean isHandledTopic(String topic) {
        return "orders/paid".equals(topic) || "orders/updated".equals(topic);
    }
}
