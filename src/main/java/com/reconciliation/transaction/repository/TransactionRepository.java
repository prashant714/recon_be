package com.reconciliation.transaction.repository;

import com.reconciliation.common.enums.ReconciliationStatus;
import com.reconciliation.common.enums.TransactionStatus;
import com.reconciliation.transaction.entity.Transaction;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    Optional<Transaction> findByProviderAndProviderTransactionId(
            String provider, String providerTransactionId);

    @Query(value = """
        SELECT 1
        FROM pg_advisory_xact_lock(hashtext(:provider), hashtext(:providerTransactionId))
        """, nativeQuery = true)
    Integer lockProviderTransactionId(
            @Param("provider") String provider,
            @Param("providerTransactionId") String providerTransactionId);

    boolean existsByProviderAndProviderTransactionId(
            String provider, String providerTransactionId);

    List<Transaction> findByMerchantIdAndOrderId(String merchantId, String orderId);

    Optional<Transaction> findByProviderAndMerchantIdAndProviderOrderId(
            String provider, String merchantId, String providerOrderId);

    List<Transaction> findByEventTypeAndParentTransactionIdIsNullAndEventOccurredAtBefore(
            com.reconciliation.common.enums.EventType eventType, OffsetDateTime before);

    List<Transaction> findByStatusAndEventOccurredAtBefore(
            TransactionStatus status, OffsetDateTime before);

    @Query("""
        SELECT t FROM Transaction t
        WHERE t.status = com.reconciliation.common.enums.TransactionStatus.CAPTURED
          AND t.eventType = com.reconciliation.common.enums.EventType.PAYMENT
          AND t.reconciliationStatus = com.reconciliation.common.enums.ReconciliationStatus.PENDING_SETTLEMENT
          AND t.eventOccurredAt < :before
        """)
    List<Transaction> findPendingCapturedPaymentsBefore(@Param("before") OffsetDateTime before);

    @Query("""
        SELECT t FROM Transaction t
        WHERE t.status = :status
          AND t.reconciliationStatus = :reconStatus
          AND t.eventOccurredAt < :before
        """)
    List<Transaction> findByStatusAndReconciliationStatusAndEventOccurredAtBefore(
            @Param("status") TransactionStatus status,
            @Param("reconStatus") ReconciliationStatus reconStatus,
            @Param("before") OffsetDateTime before);

    @Query("""
        SELECT t FROM Transaction t
        WHERE t.status = com.reconciliation.common.enums.TransactionStatus.AUTHORIZED
          AND t.eventOccurredAt < :threshold
          AND t.reconciliationStatus = com.reconciliation.common.enums.ReconciliationStatus.PENDING
        """)
    List<Transaction> findStaleAuthorizedPayments(@Param("threshold") OffsetDateTime threshold);

    @Query("""
        SELECT t FROM Transaction t
        WHERE t.merchantId = :merchantId
          AND t.orderId = :orderId
          AND t.status = com.reconciliation.common.enums.TransactionStatus.CAPTURED
          AND t.eventType = com.reconciliation.common.enums.EventType.PAYMENT
        """)
    List<Transaction> findCapturedPaymentsByMerchantAndOrder(
            @Param("merchantId") String merchantId,
            @Param("orderId") String orderId);

    @Query("""
        SELECT t FROM Transaction t
        WHERE t.provider = :provider
          AND t.merchantId = :merchantId
          AND t.eventType = com.reconciliation.common.enums.EventType.PAYMENT
          AND t.providerTransactionId = :providerTransactionId
        """)
    Optional<Transaction> findPaymentByProviderTransactionId(
            @Param("provider") String provider,
            @Param("merchantId") String merchantId,
            @Param("providerTransactionId") String providerTransactionId);

    @Query("""
        SELECT t.merchantId, t.orderId
        FROM Transaction t
        WHERE t.status = com.reconciliation.common.enums.TransactionStatus.CAPTURED
          AND t.eventType = com.reconciliation.common.enums.EventType.PAYMENT
          AND t.orderId IS NOT NULL
        GROUP BY t.merchantId, t.orderId
        HAVING COUNT(t.id) > 1
        """)
    List<Object[]> findDuplicateCapturedOrderKeys();

    @Modifying
    @Query("""
        UPDATE Transaction t
        SET t.reconciliationStatus = :status,
            t.matchedAt = :matchedAt,
            t.updatedAt = :now
        WHERE t.id = :id
        """)
    void updateReconciliationStatus(
            @Param("id") Long id,
            @Param("status") ReconciliationStatus status,
            @Param("matchedAt") OffsetDateTime matchedAt,
            @Param("now") OffsetDateTime now);

    @Query("""
        SELECT t FROM Transaction t
        WHERE t.eventType = com.reconciliation.common.enums.EventType.REFUND
          AND t.parentTransactionId IS NULL
          AND t.eventOccurredAt < :cutoff
    """)
    List<Transaction> findOrphanRefunds(@Param("cutoff") OffsetDateTime cutoff);

    @Query("""
        SELECT COALESCE(SUM(t.netAmount), 0)
        FROM Transaction t
        WHERE t.settlementId = :settlementId
    """)
    Long sumNetAmountBySettlementId(@Param("settlementId") String settlementId);

    long countByReconciliationStatus(ReconciliationStatus status);

    @Query("""
        SELECT COUNT(t)
        FROM Transaction t
        WHERE t.eventOccurredAt >= :since
    """)
    long countSince(@Param("since") OffsetDateTime since);

    @Query("""
        SELECT COUNT(t)
        FROM Transaction t
        WHERE t.eventOccurredAt >= :since
          AND t.reconciliationStatus = :status
    """)
    long countByReconciliationStatusSince(
            @Param("since") OffsetDateTime since,
            @Param("status") ReconciliationStatus status);

    @Query("""
        SELECT LOWER(COALESCE(t.provider, 'unknown')),
               COUNT(t),
               SUM(CASE WHEN t.reconciliationStatus = com.reconciliation.common.enums.ReconciliationStatus.EXCEPTION
                        THEN 1 ELSE 0 END)
        FROM Transaction t
        WHERE t.eventOccurredAt >= :since
        GROUP BY LOWER(COALESCE(t.provider, 'unknown'))
    """)
    List<Object[]> findProviderSummarySince(@Param("since") OffsetDateTime since);
}
