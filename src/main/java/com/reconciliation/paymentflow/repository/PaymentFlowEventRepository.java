package com.reconciliation.paymentflow.repository;

import com.reconciliation.paymentflow.entity.PaymentFlowEvent;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface PaymentFlowEventRepository extends JpaRepository<PaymentFlowEvent, Long> {

    @Query("""
        SELECT p
        FROM PaymentFlowEvent p
        WHERE (:providerTransactionId IS NULL OR p.providerTransactionId = :providerTransactionId)
          AND (:webhookEventId IS NULL OR p.webhookEventId = :webhookEventId)
          AND (:userId IS NULL OR p.userId = :userId)
        ORDER BY p.createdAt DESC
        """)
    List<PaymentFlowEvent> search(
            @Param("providerTransactionId") String providerTransactionId,
            @Param("webhookEventId") Long webhookEventId,
            @Param("userId") Long userId,
            Pageable pageable);
}
