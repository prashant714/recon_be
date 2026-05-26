package com.reconciliation.webhook_event.entity;

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
@Table(name = "webhook_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WebhookEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String provider;

    @Column(name = "provider_event_id", nullable = false, length = 120)
    private String providerEventId;

    @Column(name = "merchant_id", length = 60)
    private String merchantId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "received_at", nullable = false)
    private OffsetDateTime receivedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> payload;

    @Column(name = "signature_valid", nullable = false)
    private boolean signatureValid;

    @Column(nullable = false, length = 20)
    private String source;

    @Column(nullable = false)
    @Builder.Default
    private boolean processed = false;

    @Column(name = "processed_at")
    private OffsetDateTime processedAt;

    @Column(name = "processing_error", columnDefinition = "TEXT")
    private String processingError;

    @PrePersist
    public void prePersist() {
        if (receivedAt == null) {
            receivedAt = OffsetDateTime.now();
        }
    }
}
