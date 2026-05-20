package com.reconciliation.paymentflow.service;

import com.reconciliation.paymentflow.entity.PaymentFlowEvent;
import com.reconciliation.paymentflow.repository.PaymentFlowEventRepository;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentFlowEventService {

    private final PaymentFlowEventRepository paymentFlowEventRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(
            String provider,
            String providerEventId,
            String providerTransactionId,
            Long webhookEventId,
            Long userId,
            String source,
            String step,
            String status,
            String message,
            Map<String, Object> metadata) {
        try {
            paymentFlowEventRepository.save(PaymentFlowEvent.builder()
                    .provider(provider)
                    .providerEventId(providerEventId)
                    .providerTransactionId(providerTransactionId)
                    .webhookEventId(webhookEventId)
                    .userId(userId)
                    .source(source)
                    .step(step)
                    .status(status)
                    .message(message)
                    .metadata(metadata)
                    .build());
        } catch (Exception e) {
            log.warn("Failed to persist payment flow event step={} providerEventId={}: {}",
                    step, providerEventId, e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public List<PaymentFlowEvent> search(String providerTransactionId, Long webhookEventId, Long userId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        return paymentFlowEventRepository.search(
                providerTransactionId,
                webhookEventId,
                userId,
                PageRequest.of(0, safeLimit));
    }
}
