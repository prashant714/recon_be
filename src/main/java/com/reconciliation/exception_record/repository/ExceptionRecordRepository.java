package com.reconciliation.exception_record.repository;

import com.reconciliation.common.enums.ExceptionStatus;
import com.reconciliation.common.enums.ExceptionType;
import com.reconciliation.exception_record.entity.ExceptionRecord;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ExceptionRecordRepository extends JpaRepository<ExceptionRecord, Long> {

    boolean existsByExceptionTypeAndTransactionIdAndStatusIn(
            ExceptionType exceptionType,
            Long transactionId,
            java.util.Collection<ExceptionStatus> statuses);

    Optional<ExceptionRecord> findFirstByExceptionTypeAndTransactionIdAndStatusInOrderByDetectedAtDesc(
            ExceptionType exceptionType,
            Long transactionId,
            java.util.Collection<ExceptionStatus> statuses);

    org.springframework.data.domain.Page<ExceptionRecord> findByMerchantIdAndDetectedAtAfter(
            String merchantId, java.time.OffsetDateTime since, org.springframework.data.domain.Pageable pageable);

    org.springframework.data.domain.Page<ExceptionRecord> findByMerchantIdAndDetectedAtBetween(
            String merchantId, java.time.OffsetDateTime from, java.time.OffsetDateTime to,
            org.springframework.data.domain.Pageable pageable);

    List<ExceptionRecord> findByMerchantIdOrderByDetectedAtDesc(String merchantId, Pageable pageable);

    org.springframework.data.domain.Page<ExceptionRecord> findByMerchantIdAndStatusAndDetectedAtAfter(
            String merchantId, ExceptionStatus status, java.time.OffsetDateTime since, org.springframework.data.domain.Pageable pageable);

    @org.springframework.data.jpa.repository.Query("""
        SELECT COUNT(e) FROM ExceptionRecord e
        WHERE e.merchantId = :merchantId
          AND e.status = com.reconciliation.common.enums.ExceptionStatus.OPEN
          AND e.detectedAt > :since
    """)
    long countOpenExceptions(
            @org.springframework.data.repository.query.Param("merchantId") String merchantId,
            @org.springframework.data.repository.query.Param("since") java.time.OffsetDateTime since);

    @org.springframework.data.jpa.repository.Query("""
        SELECT COUNT(e) FROM ExceptionRecord e
        WHERE e.merchantId = :merchantId
          AND e.status = com.reconciliation.common.enums.ExceptionStatus.OPEN
          AND e.detectedAt >= :from
          AND e.detectedAt < :to
    """)
    long countOpenExceptionsBetween(
            @org.springframework.data.repository.query.Param("merchantId") String merchantId,
            @org.springframework.data.repository.query.Param("from") java.time.OffsetDateTime from,
            @org.springframework.data.repository.query.Param("to") java.time.OffsetDateTime to);

    @Query("""
        SELECT e.exceptionType, COUNT(e)
        FROM ExceptionRecord e
        WHERE e.merchantId = :merchantId
          AND e.detectedAt > :since
        GROUP BY e.exceptionType
    """)
    List<Object[]> countByTypeForMerchant(
            @Param("merchantId") String merchantId,
            @Param("since") java.time.OffsetDateTime since);

    @Query("""
        SELECT e.exceptionType, COUNT(e)
        FROM ExceptionRecord e
        WHERE e.merchantId = :merchantId
          AND e.detectedAt >= :from
          AND e.detectedAt < :to
        GROUP BY e.exceptionType
    """)
    List<Object[]> countByTypeForMerchantBetween(
            @Param("merchantId") String merchantId,
            @Param("from") java.time.OffsetDateTime from,
            @Param("to") java.time.OffsetDateTime to);

    long countByStatusIn(Collection<ExceptionStatus> statuses);

    long countByMerchantIdAndStatusIn(String merchantId, Collection<ExceptionStatus> statuses);

    boolean existsBySettlementIdAndStatusIn(Long settlementId, Collection<ExceptionStatus> statuses);

    boolean existsByExceptionTypeAndSettlementIdAndStatusIn(
            ExceptionType exceptionType,
            Long settlementId,
            Collection<ExceptionStatus> statuses);

    boolean existsByExceptionTypeAndMerchantIdAndDescriptionContainingAndStatusIn(
            ExceptionType exceptionType,
            String merchantId,
            String descriptionFragment,
            Collection<ExceptionStatus> statuses);

    @Query(value = """
        SELECT CAST(e.detected_at AS date) AS bucket,
               COUNT(*) AS exceptions
        FROM exception_records e
        WHERE e.merchant_id = :merchantId
          AND e.detected_at >= :since
        GROUP BY CAST(e.detected_at AS date)
        ORDER BY bucket
        """, nativeQuery = true)
    List<Object[]> findDailyExceptionTrend(
            @Param("merchantId") String merchantId,
            @Param("since") java.time.OffsetDateTime since);

    @Query(value = """
        SELECT CAST(e.detected_at AS date) AS bucket,
               COUNT(*) AS exceptions
        FROM exception_records e
        WHERE e.merchant_id = :merchantId
          AND e.detected_at >= :from
          AND e.detected_at < :to
        GROUP BY CAST(e.detected_at AS date)
        ORDER BY bucket
        """, nativeQuery = true)
    List<Object[]> findDailyExceptionTrendBetween(
            @Param("merchantId") String merchantId,
            @Param("from") java.time.OffsetDateTime from,
            @Param("to") java.time.OffsetDateTime to);
}
