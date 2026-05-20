package com.reconciliation.transaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconciliation.common.enums.EventType;
import com.reconciliation.common.enums.TransactionStatus;
import com.reconciliation.transaction.entity.Transaction;
import com.reconciliation.transaction.service.NormalizationService;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class NormalizationServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final NormalizationService service = new NormalizationService();

    @Test
    void normalizesRazorpayPaymentAuthorized() throws Exception {
        String payload = """
                {
                  "id": "evt_1",
                  "event": "payment.authorized",
                  "payload": {
                    "payment": {
                      "entity": {
                        "id": "pay_1",
                        "order_id": "order_1",
                        "amount": 2500,
                        "currency": "inr",
                        "created_at": 1710000000,
                        "email": "payer@example.com",
                        "contact": "9999999999"
                      }
                    }
                  }
                }
                """;

        Transaction transaction = service.normalizeRazorpayPaymentAuthorized(
                objectMapper.readTree(payload), "merchant_001");

        assertThat(transaction.getEventType()).isEqualTo(EventType.PAYMENT);
        assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.AUTHORIZED);
        assertThat(transaction.getPresentmentCurrency()).isEqualTo("INR");
        assertThat(transaction.getOrderId()).isEqualTo("order_1");
    }

    @Test
    void normalizesRazorpayPayerNameFromCardNameWhenTopLevelNameIsMissing() throws Exception {
        String payload = """
                {
                  "id": "evt_1",
                  "event": "payment.captured",
                  "payload": {
                    "payment": {
                      "entity": {
                        "id": "pay_1",
                        "order_id": "order_1",
                        "amount": 2500,
                        "currency": "inr",
                        "created_at": 1710000000,
                        "email": "payer@example.com",
                        "contact": "9999999999",
                        "card": {
                          "name": "Shrey Mishra"
                        }
                      }
                    }
                  }
                }
                """;

        Transaction transaction = service.normalizeRazorpayPaymentCaptured(
                objectMapper.readTree(payload), "merchant_001");

        assertThat(transaction.getPayerName()).isEqualTo("Shrey Mishra");
    }

    @Test
    void normalizesStripeChargeSucceeded() throws Exception {
        String payload = """
                {
                  "id": "evt_3",
                  "type": "charge.succeeded",
                  "created": 1710001000,
                  "data": {
                    "object": {
                      "id": "ch_1",
                      "amount": 2000,
                      "currency": "usd",
                      "payment_intent": "pi_1",
                      "receipt_email": "payer@example.com",
                      "billing_details": { "name": "Test Payer", "email": "payer@example.com" },
                      "payment_method_details": {
                        "type": "card",
                        "card": { "brand": "visa", "last4": "4242", "network": "visa" }
                      }
                    }
                  }
                }
                """;

        Transaction transaction = service.normalizeStripeChargeSucceeded(
                objectMapper.readTree(payload), "merchant_001");

        assertThat(transaction.getEventType()).isEqualTo(EventType.PAYMENT);
        assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.CAPTURED);
        assertThat(transaction.getProviderTransactionId()).isEqualTo("ch_1");
        assertThat(transaction.getPresentmentCurrency()).isEqualTo("USD");
        assertThat(transaction.getPaymentMethod()).isEqualTo("card");
        assertThat(transaction.getCardLast4()).isEqualTo("4242");
        assertThat(transaction.getCardNetwork()).isEqualTo("visa");
        assertThat(transaction.getPayerEmail()).isEqualTo("payer@example.com");
    }

    @Test
    void normalizesStripeChargeRefunded() throws Exception {
        String payload = """
                {
                  "id": "evt_2",
                  "type": "charge.refunded",
                  "created": 1710000000,
                  "data": {
                    "object": {
                      "id": "ch_1",
                      "payment_intent": "pi_1",
                      "currency": "usd",
                      "amount_refunded": 1200,
                      "billing_details": { "email": "payer@example.com" },
                      "refunds": {
                        "data": [
                          { "id": "re_1", "amount": 1200 }
                        ]
                      }
                    }
                  }
                }
                """;

        Transaction transaction = service.normalizeStripeChargeRefunded(
                objectMapper.readTree(payload), "merchant_001");

        assertThat(transaction.getEventType()).isEqualTo(EventType.REFUND);
        assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.REFUNDED);
        assertThat(transaction.getProviderTransactionId()).isEqualTo("re_1");
        assertThat(transaction.getPresentmentCurrency()).isEqualTo("USD");
    }

    @Test
    void normalizesStripeRefundCreated() throws Exception {
        String payload = """
                {
                  "id": "poll_re_1",
                  "type": "refund.created",
                  "created": 1710000000,
                  "data": {
                    "object": {
                      "id": "re_1",
                      "charge": "ch_1",
                      "currency": "usd",
                      "amount": 1200
                    }
                  }
                }
                """;

        Transaction transaction = service.normalizeStripeRefundCreated(
                objectMapper.readTree(payload), "merchant_001");

        assertThat(transaction.getEventType()).isEqualTo(EventType.REFUND);
        assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.REFUNDED);
        assertThat(transaction.getProviderTransactionId()).isEqualTo("re_1");
        assertThat(transaction.getPresentmentCurrency()).isEqualTo("USD");
    }
}
