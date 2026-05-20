package com.reconciliation.transaction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconciliation.common.enums.EventType;
import com.reconciliation.common.enums.ReconciliationStatus;
import com.reconciliation.common.enums.TransactionStatus;
import com.reconciliation.transaction.entity.Transaction;
import com.reconciliation.transaction.repository.TransactionRepository;
import com.reconciliation.transaction.service.TransactionService;
import com.reconciliation.transaction.service.TransactionUpsertResult;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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

        TransactionUpsertResult result = service.upsert(incoming);

        assertThat(result.transaction()).isSameAs(existing);
        assertThat(result.action()).isEqualTo(TransactionUpsertResult.Action.IGNORED);
        var inOrder = inOrder(repository);
        inOrder.verify(repository).lockProviderTransactionId("RAZORPAY", "pay_1");
        inOrder.verify(repository).findByProviderAndProviderTransactionId("RAZORPAY", "pay_1");
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

    @Test
    void upsertPromotesAuthorizedPaymentToCapturedWhenTimestampsMatch() {
        OffsetDateTime occurredAt = OffsetDateTime.parse("2024-01-01T10:00:00Z");

        Transaction existing = Transaction.builder()
                .id(1L)
                .provider("razorpay")
                .providerTransactionId("pay_1")
                .merchantId("merchant_001")
                .eventType(EventType.PAYMENT)
                .status(TransactionStatus.AUTHORIZED)
                .presentmentAmount(1000L)
                .presentmentCurrency("INR")
                .eventOccurredAt(occurredAt)
                .reconciliationStatus(ReconciliationStatus.PENDING)
                .build();
        Transaction incoming = Transaction.builder()
                .provider("razorpay")
                .providerTransactionId("pay_1")
                .providerEventId("payment.captured:pay_1")
                .merchantId("merchant_001")
                .eventType(EventType.PAYMENT)
                .status(TransactionStatus.CAPTURED)
                .presentmentAmount(1000L)
                .presentmentCurrency("INR")
                .eventOccurredAt(occurredAt)
                .capturedAt(occurredAt.plusSeconds(5))
                .feeAmount(30L)
                .taxAmount(5L)
                .netAmount(965L)
                .reconciliationStatus(ReconciliationStatus.PENDING_SETTLEMENT)
                .build();

        when(repository.findByProviderAndProviderTransactionId("razorpay", "pay_1"))
                .thenReturn(Optional.of(existing));
        when(repository.save(existing)).thenReturn(existing);

        TransactionUpsertResult result = service.upsert(incoming);

        assertThat(result.transaction()).isSameAs(existing);
        assertThat(result.action()).isEqualTo(TransactionUpsertResult.Action.UPDATED);
        assertThat(existing.getStatus()).isEqualTo(TransactionStatus.CAPTURED);
        assertThat(existing.getProviderEventId()).isEqualTo("payment.captured:pay_1");
        assertThat(existing.getNetAmount()).isEqualTo(965L);
        assertThat(existing.getReconciliationStatus()).isEqualTo(ReconciliationStatus.PENDING_SETTLEMENT);
        verify(repository).save(existing);
    }

    @Test
    void linkStripeRefundCreatedToParentCharge() throws Exception {
        Transaction refund = Transaction.builder()
                .provider("stripe")
                .providerTransactionId("re_1")
                .merchantId("merchant_001")
                .eventType(EventType.REFUND)
                .status(TransactionStatus.REFUNDED)
                .presentmentAmount(100L)
                .presentmentCurrency("USD")
                .eventOccurredAt(OffsetDateTime.now())
                .build();

        Transaction payment = Transaction.builder()
                .id(88L)
                .provider("stripe")
                .providerTransactionId("ch_1")
                .merchantId("merchant_001")
                .eventType(EventType.PAYMENT)
                .status(TransactionStatus.CAPTURED)
                .presentmentAmount(100L)
                .presentmentCurrency("USD")
                .eventOccurredAt(OffsetDateTime.now())
                .build();

        JsonNode payload = new ObjectMapper().readTree("""
                {"type":"refund.created","data":{"object":{"id":"re_1","charge":"ch_1"}}}
                """);

        when(repository.findByProviderAndProviderTransactionId("stripe", "ch_1"))
                .thenReturn(Optional.of(payment));

        service.linkRefundToParent(refund, payload, "stripe");

        assertThat(refund.getParentTransactionId()).isEqualTo(88L);
    }
}
