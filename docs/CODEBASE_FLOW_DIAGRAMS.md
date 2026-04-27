# Reconciliation Platform: Visual Flow Diagrams

This companion file provides Mermaid sequence diagrams for the most important runtime paths.

## 1) Webhook Success Path

```mermaid
sequenceDiagram
    autonumber
    participant P as "Provider (Razorpay/Stripe)"
    participant C as "Webhook Controller"
    participant S as "Signature Service"
    participant I as "WebhookIngestionService"
    participant E as "WebhookEventRepository"
    participant T as "TransactionProcessingService"
    participant N as "NormalizationService"
    participant U as "UserIdentityService"
    participant X as "TransactionService"

    P->>C: POST /webhooks/{provider} (raw payload + signature)
    C->>S: verify(rawBody, signature)
    S-->>C: valid
    C->>I: ingestAsync(rawBody, provider, "webhook")

    I->>I: parse payload + extract eventId/eventType
    I->>E: save(WebhookEvent)
    E-->>I: saved event (id)
    I->>T: processAsync(webhookEventId, provider)

    T->>E: findById(webhookEventId)
    E-->>T: webhookEvent
    T->>N: normalize{ProviderEvent}(payload, merchantId)
    N-->>T: canonical Transaction

    alt "Payer info exists"
        T->>U: resolveUserId(merchantId, email, phone, name)
        U-->>T: userId
        T->>T: set transaction.userId
    end

    alt "Event type is REFUND"
        T->>X: linkRefundToParent(transaction, payload, provider)
    end

    T->>X: upsert(transaction)
    X-->>T: persisted transaction
    T->>E: save(event.processed = true)
    T-->>T: log processed
```

## 2) Duplicate Webhook Path (Idempotency)

```mermaid
sequenceDiagram
    autonumber
    participant P as "Provider"
    participant C as "Webhook Controller"
    participant I as "WebhookIngestionService"
    participant E as "WebhookEventRepository"

    P->>C: Retry same webhook event
    C->>I: ingestAsync(rawBody, provider, "webhook")
    I->>E: save(WebhookEvent with same provider + providerEventId)
    E-->>I: DataIntegrityViolationException
    I-->>I: log "Duplicate event ignored"
    I-->>C: return (no async processing)
```

## 3) Refund Orphan Detection Path

```mermaid
sequenceDiagram
    autonumber
    participant J as "ReconciliationJob (db-scheduler)"
    participant R as "ReconciliationEngine"
    participant O as "OrphanRefundRule"
    participant TR as "TransactionRepository"
    participant ES as "ExceptionRecordService"

    J->>R: runAll()
    R->>O: evaluate()
    O->>TR: findOrphanRefunds(cutoff)
    TR-->>O: refunds with parentTransactionId = null

    loop "for each orphan refund"
        O->>ES: createForTransaction(ORPHAN_REFUND, ...)
        ES-->>O: ExceptionRecord (id)
        O->>TR: save(refund.reconciliationStatus = EXCEPTION, exceptionId)
    end
```

## 4) Admin Replay Flow

```mermaid
sequenceDiagram
    autonumber
    participant A as "Admin API Client"
    participant AC as "AdminController"
    participant AS as "AdminService"
    participant E as "WebhookEventRepository"
    participant T as "TransactionProcessingService"
    participant AU as "AuditService"

    A->>AC: POST /api/v1/admin/replay {webhookEventId}
    AC->>AS: replay(eventId, actor, ip)
    AS->>E: findById(eventId)
    E-->>AS: webhookEvent
    AS->>AS: reset processed=false, processedAt=null, processingError=null
    AS->>E: save(reset event)
    AS->>T: processAsync(eventId, provider)
    AS->>AU: log("webhook_replayed", ...)
    AS-->>AC: {status:"queued", webhookEventId, provider, eventType}
    AC-->>A: 200 OK
```

## 5) Optional: Rate-Limit Protection for Webhooks

```mermaid
sequenceDiagram
    autonumber
    participant P as "Provider"
    participant F as "RateLimitConfig (Filter)"
    participant C as "Webhook Controller"

    P->>F: request to /webhooks/*
    F->>F: get/create IP bucket
    alt "token available"
        F->>C: allow request
        C-->>P: normal webhook response
    else "rate limit exceeded"
        F-->>P: 429 {"error":"Too many requests"}
    end
```

---

If you want next, I can add one more diagram for `TransactionService.upsert(...)` showing the out-of-order timestamp guard branch in detail.
