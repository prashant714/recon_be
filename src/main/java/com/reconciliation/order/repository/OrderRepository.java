package com.reconciliation.order.repository;

import com.reconciliation.common.enums.OrderStatus;
import com.reconciliation.order.entity.Order;
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
public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByMerchantIdAndOrderId(String merchantId, String orderId);

    Optional<Order> findByMerchantIdAndProviderOrderId(String merchantId, String providerOrderId);

    @Query("""
        SELECT o FROM Order o
        WHERE o.merchantId = :merchantId
          AND (o.orderId = :orderId OR o.providerOrderId = :providerOrderId)
        """)
    Optional<Order> findByMerchantIdAndAnyOrderId(
            @Param("merchantId") String merchantId,
            @Param("orderId") String orderId,
            @Param("providerOrderId") String providerOrderId);

    Page<Order> findByMerchantId(String merchantId, Pageable pageable);

    Page<Order> findByMerchantIdAndOrderStatus(String merchantId, OrderStatus status, Pageable pageable);

    /** Non-OMS orders still CREATED after the grace window — payment never arrived. */
    @Query("""
        SELECT o FROM Order o
        WHERE o.orderStatus = com.reconciliation.common.enums.OrderStatus.CREATED
          AND o.omsProvider IS NULL
          AND o.createdAt < :cutoff
        """)
    List<Order> findStaleCreatedOrders(@Param("cutoff") OffsetDateTime cutoff);

    /** OMS-synced orders still CREATED after the (longer) grace window. */
    @Query("""
        SELECT o FROM Order o
        WHERE o.orderStatus = com.reconciliation.common.enums.OrderStatus.CREATED
          AND o.omsProvider IS NOT NULL
          AND o.createdAt < :cutoff
        """)
    List<Order> findStaleOmsCreatedOrders(@Param("cutoff") OffsetDateTime cutoff);

    @Query("""
        SELECT o FROM Order o
        WHERE o.omsProvider IS NOT NULL
          AND o.orderStatus = com.reconciliation.common.enums.OrderStatus.CANCELLED
          AND o.transactionId IS NOT NULL
        """)
    List<Order> findCancelledOmsOrdersWithPayment();
}
