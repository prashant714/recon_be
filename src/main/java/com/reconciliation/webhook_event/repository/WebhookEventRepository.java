package com.reconciliation.webhook_event.repository;

import com.reconciliation.webhook_event.entity.WebhookEvent;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface WebhookEventRepository extends JpaRepository<WebhookEvent, Long> {

    boolean existsByProviderAndProviderEventId(String provider, String providerEventId);

    Optional<WebhookEvent> findByProviderAndProviderEventId(String provider, String providerEventId);

    @Query("SELECT w FROM WebhookEvent w WHERE w.processed = false ORDER BY w.receivedAt ASC")
    List<WebhookEvent> findUnprocessedEvents(Pageable pageable);

    @Modifying
    @Query("""
        UPDATE WebhookEvent w
        SET w.processed = true,
            w.processedAt = :processedAt,
            w.processingError = null
        WHERE w.id = :id
        """)
    void markAsProcessed(@Param("id") Long id, @Param("processedAt") OffsetDateTime processedAt);

    @Modifying
    @Query("""
        UPDATE WebhookEvent w
        SET w.processed = true,
            w.processedAt = :processedAt,
            w.processingError = :error
        WHERE w.id = :id
        """)
    void markAsFailed(
            @Param("id") Long id,
            @Param("processedAt") OffsetDateTime processedAt,
            @Param("error") String error);
}
