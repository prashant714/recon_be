package com.reconciliation.admin.controller;

import com.reconciliation.admin.service.AdminService;
import com.reconciliation.admin.service.SelectedTransactionReconciliationService;
import com.reconciliation.audit.service.AuditService;
import com.reconciliation.paymentflow.service.PaymentFlowEventService;
import com.reconciliation.reconciliation.job.SettlementReconcilerJob;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;
    private final AuditService auditService;
    private final PaymentFlowEventService paymentFlowEventService;
    private final SettlementReconcilerJob settlementReconcilerJob;
    private final SelectedTransactionReconciliationService selectedTransactionReconciliationService;

    @PostMapping("/poll")
    public Map<String, Object> poll(
            @RequestBody PollRequest request,
            @RequestHeader(value = "X-Actor", defaultValue = "local-admin") String actor,
            HttpServletRequest httpRequest) {
        return adminService.poll(request.provider(), request.from(), request.to(),
                request.merchantId(), actor, httpRequest.getRemoteAddr());
    }

    @PostMapping("/replay")
    public Map<String, Object> replay(
            @RequestBody ReplayRequest request,
            @RequestHeader(value = "X-Actor", defaultValue = "local-admin") String actor,
            HttpServletRequest httpRequest) {
        return adminService.replay(request.webhookEventId(), actor, httpRequest.getRemoteAddr());
    }

    @PostMapping("/settlement-reconciler/run")
    public Map<String, Object> runSettlementReconciler(
            @RequestHeader(value = "X-Actor", defaultValue = "local-admin") String actor,
            HttpServletRequest httpRequest) {
        return settlementReconcilerJob.run(actor, httpRequest.getRemoteAddr());
    }

    @PostMapping("/reconcile-transactions")
    public Map<String, Object> reconcileTransactions(
            @RequestBody ReconcileTransactionsRequest request,
            @RequestHeader(value = "X-Actor", defaultValue = "frontend-admin") String actor,
            HttpServletRequest httpRequest) {
        Map<String, Object> result = selectedTransactionReconciliationService
                .reconcile(request.transactionIds(), request.mode());
        auditService.log(actor, "transactions_reconciled", "transaction", null, null,
                result, httpRequest.getRemoteAddr());
        return result;
    }

    @GetMapping("/audit-logs")
    public Map<String, Object> auditLogs(
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) Long entityId,
            @RequestParam(required = false) String actor) {
        return Map.of("items", auditService.search(entityType, entityId, actor));
    }

    @GetMapping("/payment-flow-events")
    public Map<String, Object> paymentFlowEvents(
            @RequestParam(required = false) String providerTransactionId,
            @RequestParam(required = false) Long webhookEventId,
            @RequestParam(required = false) Long userId,
            @RequestParam(defaultValue = "100") int limit) {
        return Map.of("items", paymentFlowEventService.search(providerTransactionId, webhookEventId, userId, limit));
    }

    public record PollRequest(
            String provider,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            String merchantId) {}

    public record ReplayRequest(Long webhookEventId) {}

    public record ReconcileTransactionsRequest(List<Long> transactionIds, String mode) {}
}
