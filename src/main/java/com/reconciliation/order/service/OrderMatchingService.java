package com.reconciliation.order.service;

import com.reconciliation.common.enums.ExceptionType;
import com.reconciliation.common.enums.OrderStatus;
import com.reconciliation.common.enums.ReconciliationStatus;
import com.reconciliation.common.enums.Severity;
import com.reconciliation.connection.service.ProviderConnectionService;
import com.reconciliation.exception_record.service.ExceptionRecordService;
import com.reconciliation.order.entity.Order;
import com.reconciliation.order.repository.OrderRepository;
import com.reconciliation.polling.service.RazorpayPollingService;
import com.reconciliation.transaction.entity.Transaction;
import com.reconciliation.transaction.repository.TransactionRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderMatchingService {

    private final OrderRepository orderRepository;
    private final TransactionRepository transactionRepository;
    private final ExceptionRecordService exceptionRecordService;
    private final RazorpayPollingService razorpayPollingService;
    private final ProviderConnectionService providerConnectionService;

    @Value("${app.order-matching.amount-tolerance-paisa:100}")
    private long tolerancePaisa;

    /**
     * Called from TransactionProcessingService after a payment is upserted.
     * Looks up any pre-registered order for this transaction and runs the match.
     */
    @Transactional
    public void tryMatchByTransaction(Transaction txn) {
        if (!isCapturedPayment(txn)) return;

        Optional<Order> orderOpt = Optional.empty();
        if (txn.getOrderId() != null || txn.getProviderOrderId() != null) {
            orderOpt = resolveOrder(txn.getMerchantId(), txn.getOrderId(), txn.getProviderOrderId());
        }

        // OMS-created orders store the Razorpay payment_id as providerOrderId — match by providerTransactionId
        if (orderOpt.isEmpty() && txn.getProviderTransactionId() != null) {
            orderOpt = orderRepository.findByMerchantIdAndProviderOrderId(
                    txn.getMerchantId(), txn.getProviderTransactionId());
        }

        if (orderOpt.isPresent()) {
            match(orderOpt.get(), txn);
            return;
        }

        // Last resort: fetch the Razorpay order's notes to find the Shopify order reference.
        // Shopify sets notes on the Razorpay order during checkout (e.g. shopify_order_id).
        if (txn.getProviderOrderId() != null && txn.getProviderOrderId().startsWith("order_")) {
            orderOpt = resolveOrderFromRazorpayOrderNotes(txn);
        }

        if (orderOpt.isPresent()) {
            match(orderOpt.get(), txn);
        } else {
            log.info("tryMatchByTransaction: no order found for txn={} orderId={} providerOrderId={} providerTxnId={}",
                    txn.getId(), txn.getOrderId(), txn.getProviderOrderId(), txn.getProviderTransactionId());
        }
    }

    private Optional<Order> resolveOrderFromRazorpayOrderNotes(Transaction txn) {
        try {
            Map<String, String> notes;
            var connections = providerConnectionService.findAllActiveByProvider("razorpay");
            var conn = connections.stream()
                    .filter(c -> txn.getMerchantId().equals(c.getMerchantId()))
                    .findFirst();
            if (conn.isPresent()) {
                String keyId     = providerConnectionService.decryptApiKey(conn.get());
                String keySecret = providerConnectionService.decryptSecret(conn.get());
                notes = razorpayPollingService.fetchOrderNotes(keyId, keySecret, txn.getProviderOrderId());
            } else {
                // merchant_001 uses app-level credentials — no ProviderConnection row needed
                log.info("resolveOrderFromRazorpayOrderNotes: no ProviderConnection for merchant={}, using default credentials",
                        txn.getMerchantId());
                notes = razorpayPollingService.fetchOrderNotes(txn.getProviderOrderId());
            }

            // Try common keys Shopify plugins use when creating the Razorpay order
            for (String key : List.of("shopify_order_id", "shopify_order_number", "order_id", "order_number")) {
                String value = notes.get(key);
                if (value == null || value.isBlank()) continue;

                // Shopify numeric ID (e.g. "6730304258095")
                Optional<Order> found = orderRepository.findByMerchantIdAndProviderOrderId(txn.getMerchantId(), value);
                if (found.isPresent()) {
                    log.info("resolveOrderFromRazorpayOrderNotes: matched order={} via notes.{}={}",
                            found.get().getOrderId(), key, value);
                    return found;
                }
                // Shopify order name (e.g. "#1011")
                found = orderRepository.findByMerchantIdAndOrderId(txn.getMerchantId(), value);
                if (found.isPresent()) {
                    log.info("resolveOrderFromRazorpayOrderNotes: matched order={} via notes.{}={}",
                            found.get().getOrderId(), key, value);
                    return found;
                }
            }
        } catch (Exception e) {
            log.warn("resolveOrderFromRazorpayOrderNotes failed for txn={}: {}", txn.getId(), e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Called from OrderController when an order is registered.
     * Looks up any already-captured transaction and retroactively matches.
     */
    /**
     * Called from order_transactions/create webhook.
     * Finds the OMS order by its Shopify numeric ID (stored as providerOrderId),
     * upgrades providerOrderId to the real Razorpay pay_xxx, then tries to match.
     */
    @Transactional
    public void linkTransactionToOmsOrder(String merchantId, String shopifyOrderId, String paymentId) {
        Optional<Order> orderOpt = orderRepository.findByMerchantIdAndProviderOrderId(merchantId, shopifyOrderId);
        if (orderOpt.isEmpty()) {
            // order_transactions/create arrived before orders/paid — pre-link by parking shopifyOrderId on the transaction
            // so resolveTransaction() can find it when the order is eventually created
            Optional<Transaction> txnOpt = transactionRepository.findPaymentByProviderTransactionId("razorpay", merchantId, paymentId);
            if (txnOpt.isPresent()) {
                Transaction txn = txnOpt.get();
                if (txn.getOrderId() == null) {
                    txn.setOrderId(shopifyOrderId);
                    transactionRepository.save(txn);
                    log.info("linkTransactionToOmsOrder: order not found yet — pre-linked txn={} with shopifyOrderId={} (will match when orders/paid arrives)",
                            paymentId, shopifyOrderId);
                }
            } else {
                log.info("linkTransactionToOmsOrder: order not found for shopifyOrderId={} and transaction not found for paymentId={}",
                        shopifyOrderId, paymentId);
            }
            return;
        }
        Order order = orderOpt.get();
        if (order.getTransactionId() != null) {
            log.debug("linkTransactionToOmsOrder: order={} already matched — skipping", order.getOrderId());
            return;
        }
        log.info("linkTransactionToOmsOrder: upgrading order={} providerOrderId {} → {}",
                order.getOrderId(), shopifyOrderId, paymentId);
        order.setProviderOrderId(paymentId);
        orderRepository.save(order);
        tryMatchByOrder(order);
    }

    @Transactional
    public void tryMatchByOrder(Order order) {
        Optional<Transaction> txnOpt = resolveTransaction(order);
        if (txnOpt.isPresent()) {
            match(order, txnOpt.get());
        } else {
            log.info("tryMatchByOrder: no transaction found for order={} providerOrderId={}",
                    order.getOrderId(), order.getProviderOrderId());
        }
    }

    private void match(Order order, Transaction txn) {
        if (order.getOrderStatus() == OrderStatus.PAYMENT_RECEIVED) {
            // already matched — idempotent
            return;
        }

        long diff = txn.getPresentmentAmount() - order.getExpectedAmount();
        long absDiff = Math.abs(diff);

        order.setTransactionId(txn.getId());
        txn.setOrderId(order.getOrderId());

        if (absDiff <= tolerancePaisa) {
            order.setOrderStatus(OrderStatus.PAYMENT_RECEIVED);
            order.setAmountMatched(true);
            order.setDiscrepancyAmount(0L);
            order.setMatchedAt(OffsetDateTime.now());

            txn.setReconciliationStatus(ReconciliationStatus.MATCHED);
            txn.setMatchedAt(OffsetDateTime.now());

            log.info("Order matched: orderId={} txnId={} amount={}",
                    order.getOrderId(), txn.getId(), txn.getPresentmentAmount());
        } else {
            OrderStatus mismatchStatus = diff > 0 ? OrderStatus.OVERPAID : OrderStatus.UNDERPAID;
            order.setOrderStatus(mismatchStatus);
            order.setAmountMatched(false);
            order.setDiscrepancyAmount(diff);

            String desc = String.format(
                    "Order %s expected %d %s but payment %s captured %d %s. Difference: %d paisa.",
                    order.getOrderId(), order.getExpectedAmount(), order.getCurrency(),
                    txn.getProviderTransactionId(), txn.getPresentmentAmount(),
                    txn.getPresentmentCurrency(), diff);

            exceptionRecordService.createForTransaction(
                    ExceptionType.ORDER_AMOUNT_MISMATCH,
                    Severity.HIGH,
                    txn.getId(),
                    order.getExpectedAmount(),
                    txn.getPresentmentAmount(),
                    txn.getPresentmentCurrency(),
                    desc,
                    txn.getMerchantId());

            txn.setReconciliationStatus(ReconciliationStatus.EXCEPTION);
            log.warn("Order amount mismatch: orderId={} expected={} actual={} diff={}",
                    order.getOrderId(), order.getExpectedAmount(), txn.getPresentmentAmount(), diff);
        }

        orderRepository.save(order);
        transactionRepository.save(txn);
    }

    private boolean isCapturedPayment(Transaction txn) {
        return txn.getStatus() == com.reconciliation.common.enums.TransactionStatus.CAPTURED
                && txn.getEventType() == com.reconciliation.common.enums.EventType.PAYMENT;
    }

    private Optional<Order> resolveOrder(String merchantId, String orderId, String providerOrderId) {
        if (orderId != null && providerOrderId != null) {
            return orderRepository.findByMerchantIdAndAnyOrderId(merchantId, orderId, providerOrderId);
        }
        if (orderId != null) {
            return orderRepository.findByMerchantIdAndOrderId(merchantId, orderId);
        }
        return orderRepository.findByMerchantIdAndProviderOrderId(merchantId, providerOrderId);
    }

    private Optional<Transaction> resolveTransaction(Order order) {
        if (order.getProviderOrderId() != null) {
            // OMS-driven path: providerOrderId holds the Razorpay payment_id (pay_xxx) — match on providerTransactionId
            Optional<Transaction> byPaymentId = transactionRepository.findPaymentByProviderTransactionId(
                    "razorpay", order.getMerchantId(), order.getProviderOrderId());
            if (byPaymentId.isPresent()) return byPaymentId;

            // Manual-registration path: providerOrderId holds a Razorpay/Stripe order_id — match on providerOrderId
            Optional<Transaction> byProviderOrder = transactionRepository
                    .findByProviderAndMerchantIdAndProviderOrderId(
                            "razorpay", order.getMerchantId(), order.getProviderOrderId())
                    .or(() -> transactionRepository.findByProviderAndMerchantIdAndProviderOrderId(
                            "stripe", order.getMerchantId(), order.getProviderOrderId()));
            if (byProviderOrder.isPresent()) return byProviderOrder;

            // Race-condition path: order_transactions/create arrived before orders/paid and parked
            // the shopifyOrderId in txn.orderId — find it here so the match can complete
            Optional<Transaction> byShopifyId = transactionRepository.findFirstCapturedByMerchantIdAndOrderId(
                    order.getMerchantId(), order.getProviderOrderId());
            if (byShopifyId.isPresent()) return byShopifyId;
        }
        if (order.getOrderId() != null) {
            return transactionRepository.findFirstCapturedByMerchantIdAndOrderId(
                    order.getMerchantId(), order.getOrderId());
        }
        return Optional.empty();
    }
}
