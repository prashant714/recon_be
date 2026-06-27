package com.reconciliation.order.entity;

import com.reconciliation.common.enums.OrderStatus;
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
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false, length = 60)
    private String merchantId;

    @Column(name = "order_id", nullable = false, length = 120)
    private String orderId;

    @Column(name = "provider_order_id", length = 120)
    private String providerOrderId;

    @Column(name = "expected_amount", nullable = false)
    private Long expectedAmount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_status", nullable = false)
    @Builder.Default
    private OrderStatus orderStatus = OrderStatus.CREATED;

    @Column(name = "transaction_id")
    private Long transactionId;

    @Column(name = "amount_matched", nullable = false)
    @Builder.Default
    private Boolean amountMatched = false;

    @Column(name = "discrepancy_amount")
    private Long discrepancyAmount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Column(name = "oms_provider", length = 30)
    private String omsProvider;

    @Column(name = "oms_order_status", length = 60)
    private String omsOrderStatus;

    @Column(name = "oms_synced_at")
    private OffsetDateTime omsSyncedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "oms_raw_payload", columnDefinition = "jsonb")
    private Map<String, Object> omsRawPayload;

    @Column(name = "matched_at")
    private OffsetDateTime matchedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
