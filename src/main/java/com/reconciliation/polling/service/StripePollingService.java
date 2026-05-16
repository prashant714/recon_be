package com.reconciliation.polling.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.Stripe;
import com.stripe.model.Charge;
import com.stripe.model.ChargeCollection;
import com.stripe.model.Refund;
import com.stripe.model.RefundCollection;
import com.stripe.param.ChargeListParams;
import com.stripe.param.RefundListParams;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class StripePollingService {

    private final ObjectMapper objectMapper;
    private final String secretKey;

    public StripePollingService(
            ObjectMapper objectMapper,
            @Value("${app.stripe.secret-key:}") String secretKey) {
        this.objectMapper = objectMapper;
        this.secretKey = secretKey;
    }

    public List<byte[]> fetchCharges(OffsetDateTime from, OffsetDateTime to) {
        List<byte[]> results = new ArrayList<>();
        if (!hasUsableCredentials()) {
            log.info("Skipping Stripe charges polling because a real API key is not configured");
            return results;
        }
        Stripe.apiKey = secretKey;

        String startingAfter = null;

        try {
            while (true) {
                ChargeListParams.Builder paramsBuilder = ChargeListParams.builder()
                        .setCreated(ChargeListParams.Created.builder()
                                .setGte(from.toEpochSecond())
                                .setLte(to.toEpochSecond())
                                .build())
                        .setLimit(100L);

                if (startingAfter != null) {
                    paramsBuilder.setStartingAfter(startingAfter);
                }

                ChargeCollection collection = Charge.list(paramsBuilder.build());

                for (Charge charge : collection.getData()) {
                    // Wrap in envelope matching Stripe webhook structure
                    String envelope = String.format(
                        "{\"id\":\"poll_%s\",\"type\":\"charge.succeeded\"," +
                        "\"created\":%d,\"data\":{\"object\":%s}}",
                        charge.getId(),
                        charge.getCreated(),
                        charge.toJson()
                    );
                    results.add(envelope.getBytes());
                    startingAfter = charge.getId();
                }

                if (!collection.getHasMore()) break;
            }
        } catch (Exception e) {
            log.error("Stripe charges polling failed: {}", e.getMessage(), e);
        }

        log.debug("Fetched {} charges from Stripe", results.size());
        return results;
    }

    public List<byte[]> fetchRefunds(OffsetDateTime from, OffsetDateTime to) {
        List<byte[]> results = new ArrayList<>();
        if (!hasUsableCredentials()) {
            log.info("Skipping Stripe refunds polling because a real API key is not configured");
            return results;
        }
        Stripe.apiKey = secretKey;

        String startingAfter = null;

        try {
            while (true) {
                RefundListParams.Builder paramsBuilder = RefundListParams.builder()
                        .setCreated(RefundListParams.Created.builder()
                                .setGte(from.toEpochSecond())
                                .setLte(to.toEpochSecond())
                                .build())
                        .setLimit(100L);

                if (startingAfter != null) {
                    paramsBuilder.setStartingAfter(startingAfter);
                }

                RefundCollection collection = Refund.list(paramsBuilder.build());

                for (Refund refund : collection.getData()) {
                    String envelope = String.format(
                        "{\"id\":\"poll_%s\",\"type\":\"refund.created\"," +
                        "\"created\":%d,\"data\":{\"object\":%s}}",
                        refund.getId(),
                        refund.getCreated(),
                        refund.toJson()
                    );
                    results.add(envelope.getBytes());
                    startingAfter = refund.getId();
                }

                if (!collection.getHasMore()) break;
            }
        } catch (Exception e) {
            log.error("Stripe refunds polling failed: {}", e.getMessage(), e);
        }

        return results;
    }

    private boolean hasUsableCredentials() {
        return secretKey != null
                && !secretKey.isBlank()
                && !secretKey.contains("placeholder");
    }
}
