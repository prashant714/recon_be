package com.reconciliation.reconciliation.rules;

import com.reconciliation.common.enums.EventType;
import com.reconciliation.common.enums.ExceptionType;
import com.reconciliation.common.enums.ReconciliationStatus;
import com.reconciliation.common.enums.Severity;
import com.reconciliation.common.enums.TransactionStatus;
import com.reconciliation.exception_record.service.ExceptionRecordService;
import com.reconciliation.order.entity.Order;
import com.reconciliation.order.repository.OrderRepository;
import com.reconciliation.transaction.entity.Transaction;
import com.reconciliation.transaction.repository.TransactionRepository;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
@org.springframework.core.annotation.Order(30)
public class UnmatchedOrderRule implements ReconciliationRule {

    private final OrderRepository orderRepository;
    private final TransactionRepository transactionRepository;
    private final ExceptionRecordService exceptionRecordService;

    @Value("${app.order-matching.payment-grace-minutes:30}")
    private int paymentGraceMinutes;

    @Value("${app.oms.payment-grace-minutes:1440}")
    private int omsPaymentGraceMinutes;

    @Value("${app.order-matching.order-grace-minutes:15}")
    private int orderGraceMinutes;

    @Override
    public String getName() {
        return "UnmatchedOrderRule";
    }

    @Override
    @Transactional
    public void evaluate() {
        checkOrdersWithNoPayment();
        checkOmsOrdersWithNoPayment();
        checkPaymentsWithNoOrder();
    }

    /** Non-OMS orders registered but no matching payment arrived within the grace window. */
    private void checkOrdersWithNoPayment() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusMinutes(paymentGraceMinutes);
        List<Order> stale = orderRepository.findStaleCreatedOrders(cutoff);

        for (Order order : stale) {
            String desc = String.format(
                    "Order %s (merchant=%s, expected=%d %s) has been waiting for payment for >%d minutes. "
                    + "No captured payment found.",
                    order.getOrderId(), order.getMerchantId(),
                    order.getExpectedAmount(), order.getCurrency(), paymentGraceMinutes);

            exceptionRecordService.createForOrderAlert(
                    ExceptionType.MISSING_PAYMENT,
                    Severity.HIGH,
                    order.getOrderId(),
                    order.getExpectedAmount(),
                    order.getCurrency(),
                    desc,
                    order.getMerchantId());

            log.warn("UnmatchedOrderRule: order {} has no payment after {} min",
                    order.getOrderId(), paymentGraceMinutes);
        }
    }

    /** OMS-synced orders with a longer grace window (default 24h) before flagging. */
    private void checkOmsOrdersWithNoPayment() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusMinutes(omsPaymentGraceMinutes);
        List<Order> stale = orderRepository.findStaleOmsCreatedOrders(cutoff);

        for (Order order : stale) {
            String desc = String.format(
                    "OMS order %s (merchant=%s, provider=%s, expected=%d %s) has been waiting for "
                    + "payment for >%d minutes. No captured payment found.",
                    order.getOrderId(), order.getMerchantId(), order.getOmsProvider(),
                    order.getExpectedAmount(), order.getCurrency(), omsPaymentGraceMinutes);

            exceptionRecordService.createForOrderAlert(
                    ExceptionType.MISSING_PAYMENT,
                    Severity.MEDIUM,
                    order.getOrderId(),
                    order.getExpectedAmount(),
                    order.getCurrency(),
                    desc,
                    order.getMerchantId());

            log.warn("UnmatchedOrderRule: OMS order {} has no payment after {} min",
                    order.getOrderId(), omsPaymentGraceMinutes);
        }
    }

    /**
     * Captured payments with a providerOrderId but no pre-registered order in our system
     * after the order grace window has passed.
     */
    private void checkPaymentsWithNoOrder() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusMinutes(orderGraceMinutes);

        List<Transaction> unregistered = transactionRepository
                .findCapturedWithProviderOrderIdAndNoMatchedOrder(cutoff);

        for (Transaction txn : unregistered) {
            String desc = String.format(
                    "Payment %s (amount=%d %s) was captured for providerOrderId=%s but no matching "
                    + "pre-registered order exists. Merchant may not have registered the order.",
                    txn.getProviderTransactionId(), txn.getPresentmentAmount(),
                    txn.getPresentmentCurrency(), txn.getProviderOrderId());

            exceptionRecordService.createForTransaction(
                    ExceptionType.UNREGISTERED_PAYMENT,
                    Severity.MEDIUM,
                    txn.getId(),
                    null,
                    txn.getPresentmentAmount(),
                    txn.getPresentmentCurrency(),
                    desc,
                    txn.getMerchantId());

            txn.setReconciliationStatus(ReconciliationStatus.EXCEPTION);
            transactionRepository.save(txn);

            log.warn("UnmatchedOrderRule: payment {} has no registered order (providerOrderId={})",
                    txn.getProviderTransactionId(), txn.getProviderOrderId());
        }
    }
}
