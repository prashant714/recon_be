package com.reconciliation.transaction.entity;

import com.reconciliation.common.enums.EventType;
import com.reconciliation.common.enums.ReconciliationStatus;
import com.reconciliation.common.enums.TransactionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "transactions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String provider;

    @Column(name = "provider_transaction_id", nullable = false, length = 120)
    private String providerTransactionId;

    @Column(name = "provider_event_id", length = 120)
    private String providerEventId;

    @Column(name = "merchant_id", nullable = false, length = 60)
    private String merchantId;

    @Column(name = "order_id", length = 120)
    private String orderId;

    @Column(name = "provider_order_id", length = 120)
    private String providerOrderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    private EventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionStatus status;

    @Column(name = "parent_transaction_id")
    private Long parentTransactionId;

    @Column(name = "presentment_amount", nullable = false)
    private Long presentmentAmount;

    @Column(name = "presentment_currency", nullable = false, length = 3, columnDefinition = "char(3)")
    private String presentmentCurrency;

    @Column(name = "settlement_amount")
    private Long settlementAmount;

    @Column(name = "settlement_currency", length = 3, columnDefinition = "char(3)")
    private String settlementCurrency;

    @Column(name = "fee_amount")
    private Long feeAmount;

    @Column(name = "tax_amount")
    private Long taxAmount;

    @Column(name = "net_amount")
    private Long netAmount;

    @Column(name = "event_occurred_at", nullable = false)
    private OffsetDateTime eventOccurredAt;

    @Column(name = "captured_at")
    private OffsetDateTime capturedAt;

    @Column(name = "refunded_at")
    private OffsetDateTime refundedAt;

    @Column(name = "ingested_at", nullable = false)
    private OffsetDateTime ingestedAt;

    @Column(name = "settlement_id", length = 120)
    private String settlementId;

    @Column(name = "settlement_date")
    private LocalDate settlementDate;

    @Column(name = "utr_number", length = 60)
    private String utrNumber;

    @Column(name = "payment_method", length = 30)
    private String paymentMethod;

    @Column(name = "payment_method_detail", length = 60)
    private String paymentMethodDetail;

    @Column(name = "card_last4", length = 4, columnDefinition = "char(4)")
    private String cardLast4;

    @Column(name = "card_network", length = 20)
    private String cardNetwork;

    @Column(length = 60)
    private String bank;

    @Column(length = 120)
    private String vpa;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "payer_email", length = 254)
    private String payerEmail;

    @Column(name = "payer_phone", length = 20)
    private String payerPhone;

    @Column(name = "payer_name", length = 120)
    private String payerName;

    @Enumerated(EnumType.STRING)
    @Column(name = "reconciliation_status", nullable = false)
    @Builder.Default
    private ReconciliationStatus reconciliationStatus = ReconciliationStatus.PENDING;

    @Column(name = "matched_at")
    private OffsetDateTime matchedAt;

    @Column(name = "exception_id")
    private Long exceptionId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload", columnDefinition = "jsonb")
    private Map<String, Object> rawPayload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> notes;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (ingestedAt == null) {
            ingestedAt = now;
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
