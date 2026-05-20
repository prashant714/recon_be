package com.reconciliation.paymentflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
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
@Table(name = "payment_flow_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentFlowEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String provider;

    @Column(name = "provider_event_id", length = 120)
    private String providerEventId;

    @Column(name = "provider_transaction_id", length = 120)
    private String providerTransactionId;

    @Column(name = "webhook_event_id")
    private Long webhookEventId;

    @Column(name = "user_id")
    private Long userId;

    @Column(length = 30)
    private String source;

    @Column(nullable = false, length = 60)
    private String step;

    @Column(nullable = false, length = 30)
    private String status;

    @Column(columnDefinition = "TEXT")
    private String message;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
