package com.reconciliation.transaction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconciliation.common.enums.EventType;
import com.reconciliation.common.enums.ReconciliationStatus;
import com.reconciliation.common.enums.TransactionStatus;
import com.reconciliation.transaction.entity.Transaction;
import com.reconciliation.transaction.repository.TransactionRepository;
import com.reconciliation.transaction.service.TransactionService;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TransactionServiceTest {

    private final TransactionRepository repository = mock(TransactionRepository.class);
    private final TransactionService service = new TransactionService(repository);

    @Test
    void upsertIgnoresOlderEvent() {
        Transaction existing = Transaction.builder()
                .id(1L)
                .provider("RAZORPAY")
                .providerTransactionId("pay_1")
                .merchantId("merchant_001")
                .eventType(EventType.PAYMENT)
                .status(TransactionStatus.CAPTURED)
                .presentmentAmount(1000L)
                .presentmentCurrency("INR")
                .eventOccurredAt(OffsetDateTime.parse("2024-01-01T10:00:00Z"))
                .reconciliationStatus(ReconciliationStatus.PENDING_SETTLEMENT)
                .build();
        Transaction incoming = Transaction.builder()
                .provider("RAZORPAY")
                .providerTransactionId("pay_1")
                .merchantId("merchant_001")
                .eventType(EventType.PAYMENT)
                .status(TransactionStatus.AUTHORIZED)
                .presentmentAmount(1000L)
                .presentmentCurrency("INR")
                .eventOccurredAt(OffsetDateTime.parse("2024-01-01T09:00:00Z"))
                .reconciliationStatus(ReconciliationStatus.PENDING)
                .build();
        when(repository.findByProviderAndProviderTransactionId("RAZORPAY", "pay_1"))
                .thenReturn(Optional.of(existing));

        Transaction result = service.upsert(incoming);

        assertThat(result).isSameAs(existing);
    }

    @Test
    void linkRefundToParentUsesPaymentIdFromPayload() throws Exception {
        Transaction refund = Transaction.builder()
                .provider("razorpay")
                .providerTransactionId("rfnd_1")
                .merchantId("merchant_001")
                .eventType(EventType.REFUND)
                .status(TransactionStatus.REFUNDED)
                .presentmentAmount(100L)
                .presentmentCurrency("INR")
                .eventOccurredAt(OffsetDateTime.now())
                .build();

        Transaction payment = Transaction.builder()
                .id(77L)
                .provider("razorpay")
                .providerTransactionId("pay_1")
                .merchantId("merchant_001")
                .eventType(EventType.PAYMENT)
                .status(TransactionStatus.CAPTURED)
                .presentmentAmount(100L)
                .presentmentCurrency("INR")
                .eventOccurredAt(OffsetDateTime.now())
                .build();

        JsonNode payload = new ObjectMapper().readTree("""
                {"payload":{"refund":{"entity":{"payment_id":"pay_1"}}}}
                """);

        when(repository.findByProviderAndProviderTransactionId("razorpay", "pay_1"))
                .thenReturn(Optional.of(payment));

        service.linkRefundToParent(refund, payload, "razorpay");

        assertThat(refund.getParentTransactionId()).isEqualTo(77L);
    }
}
