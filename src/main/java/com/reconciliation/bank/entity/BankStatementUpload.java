package com.reconciliation.bank.entity;

import com.reconciliation.common.enums.BankStatementUploadStatus;
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
@Table(name = "bank_statement_uploads")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BankStatementUpload {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "upload_id", nullable = false, unique = true, length = 60)
    private String uploadId;

    @Column(name = "merchant_id", nullable = false, length = 60)
    private String merchantId;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private BankStatementUploadStatus status = BankStatementUploadStatus.ACCEPTED;

    @Column(name = "rows_parsed", nullable = false)
    @Builder.Default
    private Integer rowsParsed = 0;

    @Column(name = "matched_rows", nullable = false)
    @Builder.Default
    private Integer matchedRows = 0;

    @Column(name = "exception_rows", nullable = false)
    @Builder.Default
    private Integer exceptionRows = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer progress = 0;

    @Column(name = "message", length = 255)
    private String message;

    @Column(name = "uploaded_at", nullable = false)
    private OffsetDateTime uploadedAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (uploadedAt == null) uploadedAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
