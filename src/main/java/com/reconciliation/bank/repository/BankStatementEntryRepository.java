package com.reconciliation.bank.repository;

import com.reconciliation.bank.entity.BankStatementEntry;
import com.reconciliation.common.enums.BankEntryStatus;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface BankStatementEntryRepository extends JpaRepository<BankStatementEntry, Long> {

    Page<BankStatementEntry> findByMerchantId(String merchantId, Pageable pageable);

    Page<BankStatementEntry> findByMerchantIdAndMatchStatus(
            String merchantId, BankEntryStatus matchStatus, Pageable pageable);

    List<BankStatementEntry> findByMatchStatus(BankEntryStatus matchStatus);

    List<BankStatementEntry> findByMerchantIdAndUploadBatchIdAndMatchStatus(
            String merchantId, String uploadBatchId, BankEntryStatus matchStatus);

    Optional<BankStatementEntry> findByMerchantIdAndUtrNumber(String merchantId, String utrNumber);

    /** CR entries in a date window for amount+date pass. */
    @Query("""
        SELECT b FROM BankStatementEntry b
        WHERE b.merchantId = :merchantId
          AND b.creditDebit = 'CR'
          AND b.amount = :amount
          AND b.entryDate BETWEEN :from AND :to
          AND b.matchStatus = com.reconciliation.common.enums.BankEntryStatus.PENDING
        """)
    List<BankStatementEntry> findPendingCreditByAmountAndDateRange(
            @Param("merchantId") String merchantId,
            @Param("amount") Long amount,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    /** PENDING CR entries older than the overdue cutoff — no bank match found. */
    @Query("""
        SELECT b FROM BankStatementEntry b
        WHERE b.creditDebit = 'CR'
          AND b.matchStatus = com.reconciliation.common.enums.BankEntryStatus.PENDING
          AND b.createdAt < :cutoff
        """)
    List<BankStatementEntry> findOverduePendingCredits(@Param("cutoff") OffsetDateTime cutoff);

    long countByMerchantIdAndMatchStatus(String merchantId, BankEntryStatus matchStatus);

    long countByMerchantIdAndUploadBatchId(String merchantId, String uploadBatchId);

    long countByMerchantIdAndUploadBatchIdAndMatchStatus(
            String merchantId, String uploadBatchId, BankEntryStatus matchStatus);
}
