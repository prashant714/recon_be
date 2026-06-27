package com.reconciliation.oms.service;

import com.reconciliation.common.enums.OrderStatus;
import com.reconciliation.oms.connector.OmsOrder;
import com.reconciliation.order.entity.Order;
import com.reconciliation.order.repository.OrderRepository;
import com.reconciliation.order.service.OrderMatchingService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class OmsOrderIngestionService {

    private final OrderRepository orderRepository;
    private final OrderMatchingService orderMatchingService;

    @Transactional
    public OmsIngestionResult ingest(String merchantId, String omsProvider,
                                      List<OmsOrder> omsOrders) {
        int created = 0, updated = 0, skipped = 0;

        for (OmsOrder oms : omsOrders) {
            if (shouldSkip(oms.omsStatus())) {
                skipped++;
                continue;
            }

            Optional<Order> existing = orderRepository
                    .findByMerchantIdAndOrderId(merchantId, oms.orderId());

            if (existing.isEmpty() && oms.providerOrderId() != null) {
                existing = orderRepository
                        .findByMerchantIdAndProviderOrderId(merchantId, oms.providerOrderId());
            }

            if (existing.isPresent()) {
                Order order = existing.get();
                if (order.getOmsProvider() != null) {
                    updateFromOms(order, oms);
                    orderRepository.save(order);
                    updated++;
                } else {
                    skipped++;
                }
            } else {
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
                orderMatchingService.tryMatchByOrder(order);
                created++;
            }
        }

        log.info("OMS ingestion merchant={} provider={}: created={} updated={} skipped={}",
                merchantId, omsProvider, created, updated, skipped);
        return new OmsIngestionResult(created, updated, skipped);
    }

    private boolean shouldSkip(String omsStatus) {
        return "draft".equalsIgnoreCase(omsStatus) || "void".equalsIgnoreCase(omsStatus);
    }

    private OrderStatus mapOmsStatus(String omsStatus) {
        return switch (omsStatus.toLowerCase()) {
            case "cancelled" -> OrderStatus.CANCELLED;
            default -> OrderStatus.CREATED;
        };
    }

    private void updateFromOms(Order order, OmsOrder oms) {
        order.setOmsOrderStatus(oms.omsStatus());
        order.setOmsSyncedAt(OffsetDateTime.now());
        order.setOmsRawPayload(oms.rawPayload());
        if (order.getOrderStatus() == OrderStatus.CREATED) {
            order.setExpectedAmount(oms.expectedAmount());
        }
        if ("cancelled".equalsIgnoreCase(oms.omsStatus()) || "void".equalsIgnoreCase(oms.omsStatus())) {
            order.setOrderStatus(OrderStatus.CANCELLED);
        }
    }
}
