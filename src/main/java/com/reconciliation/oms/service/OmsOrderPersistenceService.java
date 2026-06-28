package com.reconciliation.oms.service;

import com.reconciliation.common.enums.OrderStatus;
import com.reconciliation.oms.connector.OmsOrder;
import com.reconciliation.order.entity.Order;
import com.reconciliation.order.repository.OrderRepository;
import com.reconciliation.order.service.OrderMatchingService;
import java.time.OffsetDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
class OmsOrderPersistenceService {

    private final OrderRepository orderRepository;
    private final OrderMatchingService orderMatchingService;

    /**
     * Upsert a single OMS order in its own independent transaction.
     * Returns true if created, false if updated.
     * On concurrent insert conflict, throws DataIntegrityViolationException so the
     * caller can retry as a force-update in a fresh transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean upsert(String merchantId, String omsProvider, OmsOrder oms) {
        Optional<Order> existing = orderRepository.findByMerchantIdAndOrderId(merchantId, oms.orderId());
        if (existing.isEmpty() && oms.providerOrderId() != null) {
            existing = orderRepository.findByMerchantIdAndProviderOrderId(merchantId, oms.providerOrderId());
        }

        if (existing.isPresent()) {
            Order order = existing.get();
            if (order.getOmsProvider() == null) {
                log.debug("OMS update skipped for orderId={} — not an OMS order", oms.orderId());
                return false;
            }
            boolean gainedProviderOrderId = order.getProviderOrderId() == null && oms.providerOrderId() != null;
            applyUpdate(order, oms);
            orderRepository.save(order);
            if (gainedProviderOrderId) {
                log.info("OMS order={} gained providerOrderId={} — attempting match",
                        oms.orderId(), oms.providerOrderId());
            }
            // If we just learned the payment ID and the order isn't matched yet, try matching now
            if (gainedProviderOrderId && order.getTransactionId() == null) {
                orderMatchingService.tryMatchByOrder(order);
            } else if (order.getTransactionId() != null) {
                log.debug("OMS order={} already matched to transactionId={} — skipping re-match",
                        oms.orderId(), order.getTransactionId());
            }
            return false;
        } else {
            log.info("OMS order={} created via {} providerOrderId={}",
                    oms.orderId(), omsProvider, oms.providerOrderId());
            Order order = Order.builder()
                    .merchantId(merchantId)
                    .orderId(oms.orderId())
                    .providerOrderId(oms.providerOrderId())
                    .expectedAmount(oms.expectedAmount())
                    .currency(oms.currency().toUpperCase())
                    .orderStatus(mapOmsStatus(oms.omsStatus()))
                    .omsProvider(omsProvider)
                    .omsOrderStatus(oms.omsStatus())
                    .omsSyncedAt(OffsetDateTime.now())
                    .omsRawPayload(oms.rawPayload())
                    .metadata(oms.metadata())
                    .build();

            order = orderRepository.save(order);
            log.info("OMS order={} saved, attempting match (providerOrderId={})",
                    oms.orderId(), oms.providerOrderId());
            orderMatchingService.tryMatchByOrder(order);
            return true;
        }
    }

    /**
     * Force-update an order that we know exists (used after a concurrent-insert race).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void forceUpdate(String merchantId, OmsOrder oms) {
        Optional<Order> existing = orderRepository.findByMerchantIdAndOrderId(merchantId, oms.orderId());
        if (existing.isEmpty() && oms.providerOrderId() != null) {
            existing = orderRepository.findByMerchantIdAndProviderOrderId(merchantId, oms.providerOrderId());
        }
        existing.ifPresent(order -> {
            applyUpdate(order, oms);
            orderRepository.save(order);
        });
    }

    private void applyUpdate(Order order, OmsOrder oms) {
        order.setOmsOrderStatus(oms.omsStatus());
        order.setOmsSyncedAt(OffsetDateTime.now());
        order.setOmsRawPayload(oms.rawPayload());
        // Fill in providerOrderId if we now have the payment ID and didn't before
        if (order.getProviderOrderId() == null && oms.providerOrderId() != null) {
            order.setProviderOrderId(oms.providerOrderId());
        }
        if (order.getOrderStatus() == OrderStatus.CREATED) {
            order.setExpectedAmount(oms.expectedAmount());
        }
        if ("cancelled".equalsIgnoreCase(oms.omsStatus()) || "void".equalsIgnoreCase(oms.omsStatus())) {
            order.setOrderStatus(OrderStatus.CANCELLED);
        }
    }

    private OrderStatus mapOmsStatus(String omsStatus) {
        return switch (omsStatus.toLowerCase()) {
            case "cancelled" -> OrderStatus.CANCELLED;
            default -> OrderStatus.CREATED;
        };
    }
}
