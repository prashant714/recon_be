package com.reconciliation.settlement.repository;

import com.reconciliation.common.enums.SettlementStatus;
import com.reconciliation.settlement.entity.Settlement;
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
public interface SettlementRepository extends JpaRepository<Settlement, Long> {

    Optional<Settlement> findByProviderAndProviderSettlementId(String provider, String providerSettlementId);

    Optional<Settlement> findByUtrNumber(String utrNumber);

    List<Settlement> findByMerchantId(String merchantId);

    List<Settlement> findBySettlementStatus(com.reconciliation.common.enums.SettlementStatus settlementStatus);

    List<Settlement> findBySettledAtBetween(OffsetDateTime from, OffsetDateTime to);

    List<Settlement> findBySettlementStatusAndCreatedAtBefore(
            com.reconciliation.common.enums.SettlementStatus status, OffsetDateTime before);

    /** Find SETTLED settlements for a merchant whose net amount falls within a tolerance
     *  range and credit date falls within a date window.
     *  Used for amount+date fuzzy matching against bank statement entries (Pass 2). */
    @Query("""
        SELECT s FROM Settlement s
        WHERE s.merchantId = :merchantId
          AND s.netAmount BETWEEN :minAmount AND :maxAmount
          AND s.bankCreditDate BETWEEN :from AND :to
          AND s.settlementStatus = com.reconciliation.common.enums.SettlementStatus.SETTLED
        """)
    List<Settlement> findSettledByNetAmountAndCreditDateRange(
            @Param("merchantId") String merchantId,
            @Param("minAmount") Long minAmount,
            @Param("maxAmount") Long maxAmount,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    /** Paginated filtered listing — all params are optional (null = no filter). */
    @Query("""
        SELECT s FROM Settlement s
        WHERE (:provider IS NULL OR LOWER(s.provider) = LOWER(:provider))
          AND (:status IS NULL OR s.settlementStatus = :status)
          AND (:dateFrom IS NULL OR s.bankCreditDate >= :dateFrom)
          AND (:dateTo IS NULL OR s.bankCreditDate <= :dateTo)
        ORDER BY s.settledAt DESC NULLS LAST
        """)
    Page<Settlement> findFiltered(
            @Param("provider") String provider,
            @Param("status") SettlementStatus status,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            Pageable pageable);

    /** Find SETTLED settlements where providerSettlementId appears in a narration string.
     *  Used for narration-parse pass (Pass 3). */
    @Query("""
        SELECT s FROM Settlement s
        WHERE s.settlementStatus = com.reconciliation.common.enums.SettlementStatus.SETTLED
          AND :narration LIKE CONCAT('%', s.providerSettlementId, '%')
        """)
    List<Settlement> findByProviderSettlementIdInNarration(@Param("narration") String narration);

    /** SETTLED settlements older than the overdue threshold with no bank credit confirmed. */
    @Query("""
        SELECT s FROM Settlement s
        WHERE s.settlementStatus = com.reconciliation.common.enums.SettlementStatus.SETTLED
          AND s.settledAt < :cutoff
        """)
    List<Settlement> findSettledBeforeCutoff(@Param("cutoff") OffsetDateTime cutoff);
}
