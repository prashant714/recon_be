package com.reconciliation.order.service;

import com.reconciliation.common.enums.ExceptionType;
import com.reconciliation.common.enums.OrderStatus;
import com.reconciliation.common.enums.ReconciliationStatus;
import com.reconciliation.common.enums.Severity;
import com.reconciliation.exception_record.service.ExceptionRecordService;
import com.reconciliation.order.entity.Order;
import com.reconciliation.order.repository.OrderRepository;
import com.reconciliation.transaction.entity.Transaction;
import com.reconciliation.transaction.repository.TransactionRepository;
import java.time.OffsetDateTime;
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

        orderOpt.ifPresent(order -> match(order, txn));
    }

    /**
     * Called from OrderController when an order is registered.
     * Looks up any already-captured transaction and retroactively matches.
     */
    @Transactional
    public void tryMatchByOrder(Order order) {
        Optional<Transaction> txnOpt = resolveTransaction(order);
        txnOpt.ifPresent(txn -> match(order, txn));
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
        }
        if (order.getOrderId() != null) {
            return transactionRepository.findFirstCapturedByMerchantIdAndOrderId(
                    order.getMerchantId(), order.getOrderId());
        }
        return Optional.empty();
    }
}
