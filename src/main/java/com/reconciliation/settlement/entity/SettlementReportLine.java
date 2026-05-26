package com.reconciliation.settlement.entity;

import com.reconciliation.common.enums.ReportLineMatchStatus;
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
@Table(name = "settlement_report_lines")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SettlementReportLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "settlement_id", nullable = false)
    private Long settlementId;

    @Column(nullable = false, length = 30)
    private String provider;

    @Column(name = "provider_txn_id", nullable = false, length = 120)
    private String providerTxnId;

    @Column(name = "entity_type", nullable = false, length = 30)
    private String entityType;

    @Column(name = "gross_amount", nullable = false)
    private Long grossAmount;

    @Column(name = "fee_amount", nullable = false)
    @Builder.Default
    private Long feeAmount = 0L;

    @Column(name = "net_amount", nullable = false)
    private Long netAmount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_status", nullable = false)
    @Builder.Default
    private ReportLineMatchStatus matchStatus = ReportLineMatchStatus.PENDING;

    @Column(name = "matched_to_txn_id")
    private Long matchedToTxnId;

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
