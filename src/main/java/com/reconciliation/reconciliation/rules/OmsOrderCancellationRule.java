package com.reconciliation.reconciliation.rules;

import com.reconciliation.common.enums.ExceptionType;
import com.reconciliation.common.enums.Severity;
import com.reconciliation.exception_record.service.ExceptionRecordService;
import com.reconciliation.order.entity.Order;
import com.reconciliation.order.repository.OrderRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
@org.springframework.core.annotation.Order(40)
public class OmsOrderCancellationRule implements ReconciliationRule {

    private final OrderRepository orderRepository;
    private final ExceptionRecordService exceptionRecordService;

    @Override
    public String getName() {
        return "OmsOrderCancellationRule";
    }

    @Override
    @Transactional
    public void evaluate() {
        List<Order> cancelled = orderRepository.findCancelledOmsOrdersWithPayment();

        for (Order order : cancelled) {
            String desc = String.format(
                    "OMS order %s (merchant=%s, provider=%s) was cancelled but has a linked payment "
                    + "(transactionId=%d). A refund may be needed.",
                    order.getOrderId(), order.getMerchantId(),
                    order.getOmsProvider(), order.getTransactionId());

            exceptionRecordService.createForTransaction(
                    ExceptionType.OMS_ORDER_CANCELLED_WITH_PAYMENT,
                    Severity.HIGH,
                    order.getTransactionId(),
                    null,
                    order.getExpectedAmount(),
                    order.getCurrency(),
                    desc,
                    order.getMerchantId());

            log.warn("OmsOrderCancellationRule: order {} cancelled with payment txn={}",
                    order.getOrderId(), order.getTransactionId());
        }
    }
}
