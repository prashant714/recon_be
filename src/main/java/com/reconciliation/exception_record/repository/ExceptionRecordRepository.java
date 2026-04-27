package com.reconciliation.exception_record.repository;

import com.reconciliation.common.enums.ExceptionStatus;
import com.reconciliation.common.enums.ExceptionType;
import com.reconciliation.exception_record.entity.ExceptionRecord;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
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

    long countByStatusIn(Collection<ExceptionStatus> statuses);

    boolean existsBySettlementIdAndStatusIn(Long settlementId, Collection<ExceptionStatus> statuses);
}
