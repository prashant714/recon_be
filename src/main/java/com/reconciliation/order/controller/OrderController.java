package com.reconciliation.order.controller;

import com.reconciliation.common.enums.OrderStatus;
import com.reconciliation.order.entity.Order;
import com.reconciliation.order.repository.OrderRepository;
import com.reconciliation.order.service.OrderMatchingService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderRepository orderRepository;
    private final OrderMatchingService orderMatchingService;

    public record OrderRequest(
            @NotBlank String orderId,
            @NotBlank String providerOrderId,
            @NotNull @Positive Long expectedAmount,
            @NotBlank String currency,
            Map<String, Object> metadata) {}

    /**
     * Merchant pre-registers an order before payment initiation.
     * Immediately attempts retroactive matching if a payment already arrived.
     */
    @PostMapping
    public ResponseEntity<?> registerOrder(
            @Valid @RequestBody OrderRequest req,
            HttpServletRequest httpRequest) {

        String merchantId = (String) httpRequest.getAttribute("merchantId");
        if (merchantId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Merchant authentication required"));
        }

        if (orderRepository.findByMerchantIdAndOrderId(merchantId, req.orderId()).isPresent()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Order already registered: " + req.orderId()));
        }
        if (orderRepository.findByMerchantIdAndProviderOrderId(merchantId, req.providerOrderId()).isPresent()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Provider order already registered: " + req.providerOrderId()));
        }

        Order order = orderRepository.save(Order.builder()
                .merchantId(merchantId)
                .orderId(req.orderId())
                .providerOrderId(req.providerOrderId())
                .expectedAmount(req.expectedAmount())
                .currency(req.currency().toUpperCase())
                .orderStatus(OrderStatus.CREATED)
                .metadata(req.metadata())
                .build());

        // retroactive match — payment may have already arrived before this call
        orderMatchingService.tryMatchByOrder(order);

        Order saved = orderRepository.findById(order.getId()).orElse(order);
        return ResponseEntity.ok(toResponse(saved));
    }

    /** List orders for the authenticated merchant, with optional status filter. */
    @GetMapping
    public ResponseEntity<?> listOrders(
            @RequestParam(defaultValue = "CREATED") String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int limit,
            HttpServletRequest httpRequest) {

        String merchantId = (String) httpRequest.getAttribute("merchantId");
        if (merchantId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Merchant authentication required"));
        }

        PageRequest pageable = PageRequest.of(page, Math.min(limit, 100),
                Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<Order> orders;
        try {
            OrderStatus orderStatus = OrderStatus.valueOf(status.toUpperCase());
            orders = orderRepository.findByMerchantIdAndOrderStatus(merchantId, orderStatus, pageable);
        } catch (IllegalArgumentException e) {
            orders = orderRepository.findByMerchantId(merchantId, pageable);
        }

        return ResponseEntity.ok(Map.of(
                "content", orders.getContent().stream().map(this::toResponse).toList(),
                "totalElements", orders.getTotalElements(),
                "totalPages", orders.getTotalPages(),
                "page", page
        ));
    }

    /** Get a single order by orderId. */
    @GetMapping("/{orderId}")
    public ResponseEntity<?> getOrder(
            @PathVariable String orderId,
            HttpServletRequest httpRequest) {

        String merchantId = (String) httpRequest.getAttribute("merchantId");
        if (merchantId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Merchant authentication required"));
        }

        Optional<Order> order = orderRepository.findByMerchantIdAndOrderId(merchantId, orderId);
        return order.map(o -> ResponseEntity.ok(toResponse(o)))
                .orElse(ResponseEntity.notFound().build());
    }

    private Map<String, Object> toResponse(Order o) {
        return Map.of(
                "id", o.getId(),
                "orderId", o.getOrderId(),
                "providerOrderId", o.getProviderOrderId() != null ? o.getProviderOrderId() : "",
                "expectedAmount", o.getExpectedAmount(),
                "currency", o.getCurrency(),
                "orderStatus", o.getOrderStatus(),
                "amountMatched", o.getAmountMatched(),
                "discrepancyAmount", o.getDiscrepancyAmount() != null ? o.getDiscrepancyAmount() : 0L,
                "transactionId", o.getTransactionId() != null ? o.getTransactionId() : "",
                "createdAt", o.getCreatedAt()
        );
    }
}
