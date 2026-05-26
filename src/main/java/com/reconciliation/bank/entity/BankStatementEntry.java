package com.reconciliation.bank.entity;

import com.reconciliation.common.enums.BankEntryStatus;
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
@Table(name = "bank_statement_entries")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BankStatementEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false, length = 60)
    private String merchantId;

    @Column(name = "upload_batch_id", nullable = false, length = 60)
    private String uploadBatchId;

    @Column(name = "entry_date", nullable = false)
    private LocalDate entryDate;

    /** Amount in smallest currency unit (paisa for INR, cents for USD). */
    @Column(nullable = false)
    private Long amount;

    @Column(nullable = false, length = 3, columnDefinition = "char(3)")
    @Builder.Default
    private String currency = "INR";

    /** "CR" for credit, "DR" for debit. */
    @Column(name = "credit_debit", nullable = false, length = 2)
    private String creditDebit;

    @Column(name = "utr_number", length = 80)
    private String utrNumber;

    @Column(name = "bank_reference", length = 120)
    private String bankReference;

    @Column(columnDefinition = "TEXT")
    private String narration;

    /** "razorpay" or "stripe" — inferred from narration during parsing. */
    @Column(name = "provider_hint", length = 30)
    private String providerHint;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_status", nullable = false)
    @Builder.Default
    private BankEntryStatus matchStatus = BankEntryStatus.PENDING;

    /** How it was matched: UTR, AMOUNT_DATE, or NARRATION. */
    @Column(name = "matched_by", length = 20)
    private String matchedBy;

    @Column(name = "matched_settlement_id")
    private Long matchedSettlementId;

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
