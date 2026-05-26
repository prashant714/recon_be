package com.reconciliation.settlement.entity;

import com.reconciliation.common.enums.SettlementStatus;
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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "settlements")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Settlement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String provider;

    @Column(name = "provider_settlement_id", nullable = false, length = 120)
    private String providerSettlementId;

    @Column(name = "merchant_id", nullable = false, length = 60)
    private String merchantId;

    @Column(name = "gross_amount", nullable = false)
    private Long grossAmount;

    @Column(name = "total_fees", nullable = false)
    @Builder.Default
    private Long totalFees = 0L;

    @Column(name = "total_tax", nullable = false)
    @Builder.Default
    private Long totalTax = 0L;

    @Column(name = "net_amount", nullable = false)
    private Long netAmount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "bank_credit_amount")
    private Long bankCreditAmount;

    @Column(name = "bank_credit_date")
    private LocalDate bankCreditDate;

    @Column(name = "utr_number", length = 60)
    private String utrNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "settlement_status", nullable = false)
    @Builder.Default
    private SettlementStatus settlementStatus = SettlementStatus.PENDING;

    @Column(name = "transaction_count")
    private Integer transactionCount;

    @Column(name = "settled_at")
    private OffsetDateTime settledAt;

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
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
