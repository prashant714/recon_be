package com.reconciliation.exception_record.entity;

import com.reconciliation.common.enums.ExceptionStatus;
import com.reconciliation.common.enums.ExceptionType;
import com.reconciliation.common.enums.Severity;
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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "exception_records")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExceptionRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false, length = 60)
    private String merchantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "exception_type", nullable = false)
    private ExceptionType exceptionType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Severity severity = Severity.MEDIUM;

    @Column(name = "transaction_id")
    private Long transactionId;

    @Column(name = "settlement_id")
    private Long settlementId;

    @Column(name = "bank_entry_id")
    private Long bankEntryId;

    @Column(name = "expected_amount")
    private Long expectedAmount;

    @Column(name = "actual_amount")
    private Long actualAmount;

    @Column(name = "discrepancy_amount")
    private Long discrepancyAmount;

    @Column(length = 3)
    private String currency;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ExceptionStatus status = ExceptionStatus.OPEN;

    @Column(name = "resolved_by", length = 60)
    private String resolvedBy;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    @Column(name = "resolution_notes", columnDefinition = "TEXT")
    private String resolutionNotes;

    @Column(name = "detected_at", nullable = false)
    private OffsetDateTime detectedAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (detectedAt == null) {
            detectedAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
