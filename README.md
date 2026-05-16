# Reconciliation Platform — Complete Implementation Guide

This document explains every part of the system end-to-end. A reader with no prior knowledge of this codebase should be able to understand what each component does, why it exists, and how everything connects.

---

## Table of Contents

1. [What This System Does](#1-what-this-system-does)
2. [Technology Stack](#2-technology-stack)
3. [High-Level Architecture](#3-high-level-architecture)
4. [Project Structure](#4-project-structure)
5. [Security Layer](#5-security-layer)
6. [Webhook Ingestion Flow](#6-webhook-ingestion-flow)
7. [Transaction Processing Flow](#7-transaction-processing-flow)
8. [Normalization — Provider to Canonical Format](#8-normalization--provider-to-canonical-format)
9. [User Identity Resolution](#9-user-identity-resolution)
10. [Transaction Upsert Logic](#10-transaction-upsert-logic)
11. [Reconciliation Engine](#11-reconciliation-engine)
12. [Reconciliation Rules Deep Dive](#12-reconciliation-rules-deep-dive)
13. [Exception Management](#13-exception-management)
14. [Settlement Reconciliation](#14-settlement-reconciliation)
15. [Gap Filler — Polling for Missed Events](#15-gap-filler--polling-for-missed-events)
16. [Admin Operations](#16-admin-operations)
17. [Dashboard and Metrics](#17-dashboard-and-metrics)
18. [Audit Logging](#18-audit-logging)
19. [Scheduled Jobs Summary](#19-scheduled-jobs-summary)
20. [Data Model](#20-data-model)
21. [API Reference](#21-api-reference)
22. [Configuration Reference](#22-configuration-reference)
23. [Testing Strategy](#23-testing-strategy)

---

## 1. What This System Does

Payment providers like Razorpay and Stripe send webhook events whenever something happens — a payment is captured, a refund is processed, a dispute is raised. These events arrive in real time and need to be stored, normalized into a common format, and checked for correctness.

**Reconciliation** is the process of verifying that every payment event is accounted for, matches expected amounts, and has no anomalies (missing captures, orphaned refunds, duplicate charges, settlement discrepancies).

This platform:

- **Receives** webhook events from Razorpay and Stripe
- **Verifies** each event's HMAC signature before accepting it
- **Normalizes** provider-specific payloads into a single canonical `Transaction` format
- **Resolves** the end-user identity behind each transaction
- **Runs reconciliation rules** on a schedule to flag any anomalies
- **Creates exception records** for every anomaly found
- **Allows ops teams** to review, resolve, or ignore exceptions
- **Polls providers** on a schedule to catch any events missed by webhooks
- **Provides dashboards** showing match rates, open exceptions, and provider summaries

---

## 2. Technology Stack

| Component | Choice | Notes |
|---|---|---|
| Language | Java 21 | Uses virtual threads, switch expressions |
| Framework | Spring Boot 3.2 | Web, Security, JPA, Actuator |
| Database | PostgreSQL | All tables use identity columns |
| Migrations | Flyway | V1–V8 migrations, runs on startup |
| Scheduling | db-scheduler 14 | Distributed scheduler backed by PostgreSQL — safe for multi-instance deployments |
| Async | Spring `@Async` | Dedicated `webhookProcessingExecutor` thread pool |
| Auth | JWT (jjwt 0.12) | Stateless; webhook endpoints are public |
| Rate Limiting | Bucket4j 8.10 | Token bucket, per-IP, webhooks only |
| Razorpay SDK | razorpay-java 1.4.3 | Used for polling |
| Stripe SDK | stripe-java 25.3 | Used for polling |
| API Docs | SpringDoc / Swagger | Available at `/swagger-ui.html` |
| Metrics | Micrometer + Prometheus | Exposed at `/actuator/prometheus` |
| Build | Gradle 8 | Java 21 toolchain |

---

## 3. High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                         INBOUND EVENTS                              │
│                                                                     │
│  Razorpay ──► POST /webhooks/razorpay ──►  RazorpayWebhookController│
│  Stripe   ──► POST /webhooks/stripe   ──►  StripeWebhookController  │
│                                                                     │
│          Both verify HMAC signature, then call:                     │
│                    WebhookIngestionService                          │
│                           │                                         │
│               ┌───────────┴────────────┐                           │
│               ▼                        ▼                           │
│       save WebhookEvent         TransactionProcessingService        │
│       (raw payload)              (async, thread pool)               │
│                                        │                           │
│                           NormalizationService                      │
│                           UserIdentityService                       │
│                           TransactionService.upsert()               │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│                       SCHEDULED JOBS                                │
│                                                                     │
│  ReconciliationJob (every 5 min) ──► ReconciliationEngine           │
│                                           │                         │
│                     ┌──────────┬──────────┼──────────┬──────────┐  │
│                     ▼          ▼          ▼          ▼          ▼  │
│               ExactIdMatch  Missing   OrphanRefund  Duplicate  Settlement│
│               Rule         Capture                 Capture    Total │
│                                                                     │
│  GapFillerJob (every 15 min) ──► RazorpayPollingService             │
│                               └─► StripePollingService              │
│                               both feed ──► WebhookIngestionService │
│                                                                     │
│  SettlementReconcilerJob (2 AM daily) ──► close SETTLED settlements │
│                                      └─► flag overdue PENDING ones  │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│                        OPERATIONS APIs                              │
│                                                                     │
│  GET  /api/v1/dashboard/summary     ──► DashboardService            │
│  GET  /api/v1/exceptions            ──► ExceptionQueryService       │
│  GET  /api/v1/transactions          ──► TransactionQueryService     │
│  GET  /api/v1/settlements           ──► SettlementService           │
│  POST /api/v1/admin/replay          ──► AdminService                │
│  POST /api/v1/admin/poll            ──► AdminService (live poll)    │
│  POST /api/v1/admin/settlement-reconciler/run ──► manual trigger    │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 4. Project Structure

```
src/main/java/com/reconciliation/
│
├── webhook/                         # Inbound webhook handling
│   ├── controller/                  # RazorpayWebhookController, StripeWebhookController
│   └── service/                     # WebhookIngestionService, TransactionProcessingService
│                                    # RazorpaySignatureService, StripeSignatureService
│
├── webhook_event/                   # Raw event storage
│   ├── entity/WebhookEvent.java
│   └── repository/WebhookEventRepository.java
│
├── transaction/                     # Canonical transaction model
│   ├── controller/TransactionController.java
│   ├── entity/Transaction.java
│   ├── repository/TransactionRepository.java
│   └── service/                     # NormalizationService, TransactionService
│                                    # TransactionQueryService
│
├── user/                            # Payer identity resolution
│   ├── entity/User.java
│   ├── repository/UserRepository.java
│   └── service/UserIdentityService.java
│
├── reconciliation/                  # Reconciliation engine and rules
│   ├── service/ReconciliationEngine.java
│   ├── rules/                       # ExactIdMatchRule, MissingCaptureRule,
│   │                                # OrphanRefundRule, DuplicateCaptureRule,
│   │                                # SettlementTotalRule, ReconciliationRule (interface)
│   └── job/                         # ReconciliationJob, GapFillerJob,
│                                    # SettlementReconcilerJob
│
├── exception_record/                # Anomaly tracking
│   ├── controller/ExceptionController.java
│   ├── entity/ExceptionRecord.java
│   ├── repository/ExceptionRecordRepository.java
│   └── service/                     # ExceptionRecordService, ExceptionQueryService
│
├── settlement/                      # Settlement records
│   ├── controller/SettlementController.java
│   ├── entity/Settlement.java
│   ├── repository/SettlementRepository.java
│   └── service/SettlementService.java
│
├── dashboard/                       # Summary metrics
│   ├── controller/DashboardController.java
│   └── service/DashboardService.java
│
├── admin/                           # Operational controls
│   ├── controller/AdminController.java
│   └── service/AdminService.java
│
├── audit/                           # Immutable audit trail
│   ├── entity/AuditLog.java
│   ├── repository/AuditLogRepository.java
│   └── service/AuditService.java
│
├── polling/                         # Provider API polling clients
│   ├── client/                      # RazorpayApiClient, StripeApiClient
│   └── service/                     # RazorpayPollingService, StripePollingService
│
├── config/                          # Spring configuration
│   ├── SecurityConfig.java          # JWT + route rules
│   ├── JwtFilter.java               # Reads Bearer token, sets security context
│   ├── JwtConfig.java               # Token generation and validation
│   ├── RateLimitConfig.java         # Bucket4j per-IP rate limiter
│   ├── AsyncConfig.java             # webhookProcessingExecutor thread pool
│   ├── SchedulerConfig.java         # db-scheduler bean
│   ├── MerchantConfig.java          # Merchant properties binding
│   └── WebConfig.java               # CORS
│
└── common/
    ├── enums/                       # Provider, EventType, TransactionStatus,
    │                                # ReconciliationStatus, ExceptionType,
    │                                # ExceptionStatus, Severity, SettlementStatus
    ├── exception/                   # GlobalExceptionHandler, domain exceptions
    └── util/                        # AmountUtils, CurrencyUtils, TimestampUtils,
                                     # EncryptionService
```

---

## 5. Security Layer

Every HTTP request passes through three layers before reaching a controller.

### Layer 1 — Rate Limit Filter (`RateLimitConfig`)

A standard servlet `Filter` registered as a Spring component. It intercepts requests to `/webhooks/**` only and applies a token-bucket rate limiter per client IP using Bucket4j.

- Bucket size: **1000 requests per minute** per IP
- Requests exceeding the limit receive HTTP 429 with `{"error":"Too many requests"}`
- All non-webhook paths skip this filter entirely

### Layer 2 — JWT Filter (`JwtFilter`)

A `OncePerRequestFilter` that runs on every request. It reads the `Authorization: Bearer <token>` header, validates the JWT using `JwtConfig` (HMAC-SHA256 signed), extracts the email claim, and sets a `UsernamePasswordAuthenticationToken` in the Spring Security context.

If no token is present or the token is invalid, the filter does not reject the request — it simply leaves the security context unauthenticated, and Spring Security's authorization rules handle the rest.

### Layer 3 — Spring Security Authorization (`SecurityConfig`)

Route-level authorization rules:

| Path pattern | Rule |
|---|---|
| `/webhooks/**` | Permit all (no JWT required — signature verified at controller level) |
| `/actuator/health`, `/actuator/prometheus` | Permit all |
| `/swagger-ui/**`, `/api-docs/**` | Permit all |
| Everything else (`/api/**`) | Must be authenticated (valid JWT) |

Sessions are stateless (no HTTP session created). CSRF is disabled for `/webhooks/**` and `/api/**`.

### Webhook Signature Verification

Webhook controllers perform HMAC verification before any processing.

**Razorpay** (`RazorpaySignatureService`):
- Computes `HMAC-SHA256(rawBody, webhookSecret)` and hex-encodes it
- Compares with the `X-Razorpay-Signature` header using constant-time comparison (`MessageDigest.isEqual`)
- Returns HTTP 400 if verification fails

**Stripe** (`StripeSignatureService`):
- Reads the `Stripe-Signature` header (contains timestamp + signatures)
- Validates using Stripe's SDK (`WebhookSignatureVerifier`)
- Returns HTTP 400 if verification fails

---

## 6. Webhook Ingestion Flow

This is the entry point for all payment events, whether they arrive via live webhook or via polling.

### Step-by-step

```
Provider  ──POST──►  WebhookController
                          │
                    verify HMAC signature
                          │ (fail → 400)
                          │ (pass → continue)
                          ▼
                   WebhookIngestionService.ingestAsync(rawBody, provider, source)
                          │
                    parse JSON, extract event ID and event type
                          │
                    build WebhookEvent entity
                          │
                    webhookEventRepository.save(event)
                          │
                    ┌─────┴──────────────────────────────┐
                    │  DataIntegrityViolationException?   │
                    │  (UNIQUE provider + event_id)       │
                    │  → duplicate, silently ignored      │
                    └─────────────────────────────────────┘
                          │ (saved successfully)
                          ▼
                   processingService.processAsync(eventId, provider)
                          │ (returns immediately — fire-and-forget)
                          ▼
                   Controller returns HTTP 200 "received"
```

**Key design decisions:**

- The controller returns `200 OK` immediately after saving the raw event. Processing happens asynchronously. This ensures the provider's retry logic is not triggered unnecessarily.
- The `source` field on `WebhookEvent` distinguishes whether the event came from a live webhook (`"webhook"`) or from the polling gap-filler (`"polling"`) or from an admin-triggered poll (`"admin-poll"`).
- Deduplication uses a database unique constraint on `(provider, provider_event_id)`. No application-level locking needed.

### WebhookEvent entity fields

| Field | Description |
|---|---|
| `provider` | `"razorpay"` or `"stripe"` |
| `providerEventId` | The event ID from the provider's payload |
| `eventType` | e.g. `payment.captured`, `charge.refunded` |
| `payload` | Full raw JSON stored as a JSON column |
| `processed` | `false` initially, set to `true` after processing |
| `processingError` | Populated if processing threw an exception |
| `source` | `webhook`, `polling`, or `admin-poll` |
| `signatureValid` | Always `true` — set at ingestion (signature verified at controller) |

---

## 7. Transaction Processing Flow

`TransactionProcessingService.processAsync()` runs in the `webhookProcessingExecutor` thread pool (core=5, max=20, queue=500). It is annotated with `@Async` and `@Transactional`.

```
processAsync(webhookEventId, provider)
        │
   load WebhookEvent by ID
        │
   route(payload, provider, eventType)
        │
   NormalizationService  ──►  canonical Transaction object
        │                      (or null if event type is not handled)
        │
   if null → markProcessed, return
        │
   if payer email/phone present:
        └──► UserIdentityService.resolveUserId(merchantId, email, phone, name)
                    │ (find-or-create user record)
                    └──► transaction.setUserId(userId)
        │
   if eventType == REFUND:
        └──► TransactionService.linkRefundToParent(transaction, payload, provider)
                    │ (looks up parent payment by provider payment ID)
                    └──► transaction.setParentTransactionId(parentId)
        │
   TransactionService.upsert(transaction)
        │ (insert or merge based on eventOccurredAt)
        │
   UserIdentityService.incrementAggregates(userId, amount, isFailed)
        │ (atomic update of user's txn count, total amount, fail count)
        │
   WebhookEvent.processed = true, processedAt = now
        │
   save WebhookEvent
```

**Error handling:** If any step throws, the exception is caught, `processingError` is set on the webhook event, and `processed` is set to `true`. This prevents the event from being re-queued automatically. An admin can replay it once the root cause is fixed.

### Event routing table

| Provider | Event type | Normalization method |
|---|---|---|
| razorpay | `payment.authorized` | `normalizeRazorpayPaymentAuthorized` |
| razorpay | `payment.captured` | `normalizeRazorpayPaymentCaptured` |
| razorpay | `payment.failed` | `normalizeRazorpayPaymentFailed` |
| razorpay | `refund.processed` | `normalizeRazorpayRefundProcessed` |
| razorpay | `settlement.processed` | returns `null` (handled by SettlementService) |
| razorpay | `dispute.created` | `normalizeRazorpayDisputeCreated` |
| stripe | `payment_intent.succeeded` | `normalizeStripePaymentSucceeded` |
| stripe | `payment_intent.payment_failed` | `normalizeStripePaymentFailed` |
| stripe | `charge.refunded` | `normalizeStripeChargeRefunded` |
| stripe | `charge.dispute.created` | `normalizeStripeDisputeCreated` |
| stripe | `payout.paid` | returns `null` (handled by SettlementService) |

---

## 8. Normalization — Provider to Canonical Format

`NormalizationService` converts provider-specific JSON payloads into a uniform `Transaction` entity. This isolates all provider-specific parsing in one place.

### Razorpay payload structure

Razorpay wraps data in a nested `payload.<entity_type>.entity` path:

```json
{
  "id": "evt_XYZ",
  "event": "payment.captured",
  "payload": {
    "payment": {
      "entity": {
        "id": "pay_ABC",
        "amount": 50000,
        "currency": "INR",
        "fee": 1000,
        "tax": 180,
        "order_id": "order_123",
        "email": "user@example.com",
        "contact": "+919876543210",
        "method": "card",
        "card": { "last4": "1234", "network": "Visa" }
      }
    }
  }
}
```

### Stripe payload structure

Stripe uses a flat `data.object` path:

```json
{
  "id": "evt_XYZ",
  "type": "payment_intent.succeeded",
  "created": 1700000000,
  "data": {
    "object": {
      "id": "pi_ABC",
      "amount": 5000,
      "currency": "usd",
      "receipt_email": "user@example.com"
    }
  }
}
```

### Canonical Transaction fields set during normalization

| Field | Source | Notes |
|---|---|---|
| `provider` | hardcoded per method | `"razorpay"` or `"stripe"` |
| `providerTransactionId` | payment/charge/refund `id` | unique per provider |
| `merchantId` | passed in from config | `merchant_001` by default |
| `eventType` | mapped from event name | `PAYMENT`, `REFUND`, `CHARGEBACK` |
| `status` | mapped from event name | `AUTHORIZED`, `CAPTURED`, `FAILED`, `REFUNDED`, `DISPUTED` |
| `presentmentAmount` | amount field (paisa or cents) | raw integer, smallest currency unit |
| `presentmentCurrency` | currency field | uppercased, e.g. `"INR"`, `"USD"` |
| `feeAmount` | fee field | Razorpay provides at capture; Stripe does not in webhook |
| `netAmount` | `amount - fee - tax` | Razorpay only at capture |
| `payerEmail` | email / receipt_email | lowercased and trimmed by UserIdentityService |
| `payerPhone` | contact field | normalized to +91 format |
| `paymentMethod` | method / payment_method_details | `"card"`, `"upi"`, `"netbanking"` |
| `reconciliationStatus` | set at normalization | see table below |
| `eventOccurredAt` | `created_at` (unix epoch) | converted to OffsetDateTime UTC |

### Initial reconciliation status by event type

| Event | Initial reconciliation status | Reason |
|---|---|---|
| `payment.authorized` | `PENDING` | Not yet captured; wait |
| `payment.captured` | `PENDING_SETTLEMENT` | Captured; waiting for settlement grouping |
| `payment.failed` | `MATCHED` | Failed = no money moved; no reconciliation needed |
| `refund.processed` | `PENDING` | Link to parent and verify |
| `dispute.created` | `EXCEPTION` | Dispute always needs attention |

---

## 9. User Identity Resolution

`UserIdentityService.resolveUserId()` finds or creates a `User` record for the payer behind a transaction. This enables per-user analytics (total spend, failed count) and allows ops to see all transactions for a single customer.

### Resolution logic

```
1. If email is present → find User by (merchantId, email)
       found → update lastSeenAt, return userId
2. If email not found but phone is present → find User by (merchantId, phone)
       found → backfill email if newly available, update lastSeenAt, return userId
3. Neither found → create new User record
       on DataIntegrityViolationException (race condition) → re-query by email
4. If neither email nor phone → return null (UPI-only transactions have no stable identity)
```

The method uses `SERIALIZABLE` isolation to prevent duplicate user creation under concurrent ingestion of the same user's transactions.

After a transaction is upserted, `incrementAggregates()` atomically updates:
- `total_txn_count`
- `total_txn_amount`
- `failed_txn_count`

### Phone normalization

- Strips spaces, dashes, dots
- 10-digit numbers prefixed with `+91`
- Numbers starting with `0` converted to `+91`

---

## 10. Transaction Upsert Logic

`TransactionService.upsert()` handles both new transactions and updates to existing ones. The key design principle is **event-time ordering**: a newer event always wins over an older one for the same transaction.

```
find existing by (provider, providerTransactionId)
        │
        ├── not found → INSERT and return
        │
        └── found:
                compare incoming.eventOccurredAt vs current.eventOccurredAt
                        │
                        ├── incoming is older or equal → SKIP, return current
                        │
                        └── incoming is newer → MERGE mutable fields
                                │
                                ├── status, eventType, providerEventId
                                ├── orderId, providerOrderId (first non-blank wins)
                                ├── feeAmount, netAmount, taxAmount, settlementAmount
                                ├── settlementId, settlementDate, utrNumber
                                ├── paymentMethod, cardLast4, cardNetwork, bank, vpa
                                ├── payerEmail, payerPhone, payerName
                                ├── userId (if incoming is non-null)
                                ├── capturedAt, refundedAt
                                └── notes (merged map — incoming keys overwrite)
```

**Why `firstNonBlank` for string fields?** Once an orderId is set, it should not be cleared by a later event that lacks it. Provider APIs sometimes omit fields in later events that were present in earlier ones.

**Refund linking:** When a `REFUND` event is processed, `TransactionService.linkRefundToParent()` extracts the original payment ID from the payload and looks up the parent `Transaction`. If found, `parentTransactionId` is set on the refund record, creating the payment-refund relationship.

---

## 11. Reconciliation Engine

`ReconciliationEngine.runAll()` executes all reconciliation rules in sequence. Rules are Spring beans implementing the `ReconciliationRule` interface, so new rules are picked up automatically by Spring's dependency injection without any registration step.

```java
public interface ReconciliationRule {
    String getName();
    void evaluate();
}
```

### Execution model

- **ReconciliationJob** triggers `runAll()` every 5 minutes via db-scheduler
- db-scheduler runs in a distributed-safe manner — only one instance runs the job at a time even in a multi-node deployment
- If one rule throws an exception, that exception is logged and the engine continues to the next rule — one broken rule never blocks the others
- Execution time is recorded as a Micrometer timer: `reconciliation.run.duration`
- Total exceptions created is tracked as a counter: `reconciliation.exceptions.created`

---

## 12. Reconciliation Rules Deep Dive

### ExactIdMatchRule

**Purpose:** Close out `CAPTURED` payments that have been waiting for settlement.

**Candidates:** Transactions with `status=CAPTURED` AND `reconciliationStatus=PENDING_SETTLEMENT` AND `eventOccurredAt < now - 5 minutes`.

**Logic:**
- If the transaction has a non-blank `orderId` → mark `reconciliationStatus=MATCHED`
- If `orderId` is missing → create `UNMATCHED_PAYMENT` exception (MEDIUM severity), mark `reconciliationStatus=EXCEPTION`

**Why 5-minute delay?** A payment captured right now may still be receiving supplemental data (like order ID) from a second event arriving within seconds.

---

### MissingCaptureRule

**Purpose:** Flag payments stuck in `AUTHORIZED` for too long, indicating a likely auto-expiry or missed capture.

**Candidates:** Transactions with `status=AUTHORIZED` AND `eventOccurredAt < now - 24 hours` (configurable via `app.reconciliation.missing-capture-threshold-hours`).

**On match:** Creates `MISSING_CAPTURE` exception (HIGH severity).

---

### OrphanRefundRule

**Purpose:** Flag refunds that could not be linked to a parent payment.

**Candidates:** Transactions with `eventType=REFUND` AND `parentTransactionId IS NULL` AND `eventOccurredAt < now - 10 minutes`.

**On match:** Creates `ORPHAN_REFUND` exception (HIGH severity).

**Why 10-minute grace?** The parent payment and the refund may arrive very close together. The 10-minute window allows the parent to be saved before the rule runs.

---

### DuplicateCaptureRule

**Purpose:** Detect the same order being charged more than once.

**Candidates:** `(merchantId, orderId)` groups where more than one `CAPTURED` payment exists for the same order.

**Logic:** Query groups that have `COUNT > 1`, then flag every transaction in that group with a `DUPLICATE_CAPTURE` exception (CRITICAL severity).

---

### SettlementTotalRule

**Purpose:** Verify that the sum of net amounts for all transactions in a settlement matches the settlement's declared `netAmount`.

**Candidates:** Settlements with `settlementStatus=SETTLED`.

**Logic:**
```
transactionSum = SUM(netAmount) WHERE settlementId = settlement.providerSettlementId
diff = ABS(settlement.netAmount - transactionSum)

if diff > tolerance (default 100 paisa):
    create SETTLEMENT_DISCREPANCY exception (CRITICAL)
    mark settlement.settlementStatus = DISCREPANT
else:
    log success (settlement is correct)
```

The tolerance (default 100 paisa = ₹1) is configurable via `app.reconciliation.amount-tolerance-paisa`.

---

### ExceptionRecord deduplication

`ExceptionRecordService.createForTransaction()` checks whether an OPEN or IN_REVIEW exception of the same `(type, transactionId)` already exists before creating a new one. This prevents the engine from creating duplicate exceptions on every 5-minute run.

---

## 13. Exception Management

An `ExceptionRecord` represents an anomaly detected by the reconciliation engine.

### Exception types

| Type | Created by | Severity |
|---|---|---|
| `UNMATCHED_PAYMENT` | ExactIdMatchRule | MEDIUM |
| `MISSING_CAPTURE` | MissingCaptureRule | HIGH |
| `ORPHAN_REFUND` | OrphanRefundRule | HIGH |
| `DUPLICATE_CAPTURE` | DuplicateCaptureRule | CRITICAL |
| `SETTLEMENT_DISCREPANCY` | SettlementTotalRule, SettlementReconcilerJob | CRITICAL / HIGH |

### Exception lifecycle

```
OPEN  ──►  IN_REVIEW  ──►  RESOLVED
  │                            ▲
  └────────────────────────────┘
  │
  └──► IGNORED
```

- `OPEN`: Created by a reconciliation rule. Needs attention.
- `IN_REVIEW`: An ops team member has started investigating.
- `RESOLVED`: The underlying issue has been fixed.
- `IGNORED`: Acknowledged as non-actionable (e.g., test transaction).

When status transitions to `RESOLVED` or `IGNORED`, `resolvedAt` and `resolvedBy` are stamped on the record.

### Exception API (`/api/v1/exceptions`)

All requests route through `ExceptionController` → `ExceptionQueryService`.

**GET `/api/v1/exceptions`** — List exceptions with filters:
- `days` (default 7) — look-back window
- `status` — filter by `OPEN`, `IN_REVIEW`, `RESOLVED`, `IGNORED`
- `provider` — filter by provider (resolved via linked transaction)
- `type` — filter by exception type
- `page`, `limit` — pagination

Response includes a `summary` block with counts by status.

**GET `/api/v1/exceptions/{id}`** — Get a single exception with its linked transaction and full audit trail.

**PATCH `/api/v1/exceptions/{id}`** — Update status and add resolution notes. Requires `X-Actor` header. Every update is recorded in the audit log.

```json
PATCH /api/v1/exceptions/42
X-Actor: ops-engineer-1
{
  "status": "RESOLVED",
  "notes": "Verified with Razorpay dashboard — legitimate duplicate, refund already issued"
}
```

---

## 14. Settlement Reconciliation

Settlements represent the bank transfer from Razorpay or Stripe to the merchant. Each settlement groups multiple transactions.

### Settlement entity key fields

| Field | Description |
|---|---|
| `providerSettlementId` | Provider's unique ID for this settlement |
| `grossAmount` | Total gross amount |
| `totalFees` | Total platform fees |
| `netAmount` | Gross minus fees |
| `bankCreditAmount` | Amount actually credited to bank |
| `bankCreditDate` | Date of bank credit |
| `utrNumber` | UTR reference for bank transfer |
| `settlementStatus` | `PENDING` → `SETTLED` → `MATCHED_TO_BANK` or `DISCREPANT` |

### Settlement status flow

```
PENDING  ──►  SETTLED  ──►  MATCHED_TO_BANK  (SettlementReconcilerJob closes cleanly)
                  │
                  └──►  DISCREPANT           (SettlementTotalRule: amount mismatch)
```

### SettlementReconcilerJob (daily at 2 AM)

This job runs independently of the main ReconciliationEngine and handles two responsibilities:

**Phase 1 — Close clean settlements:**
- Find all settlements with `status = SETTLED`
- For each, check if any `OPEN` or `IN_REVIEW` exceptions reference it
- If no open exceptions → update to `MATCHED_TO_BANK`
- If open exceptions exist → leave as `SETTLED`, log warning

**Phase 2 — Flag overdue pending settlements:**
- Find settlements with `status = PENDING` AND `createdAt < now - 7 days` (configurable)
- For each not already flagged, create a `SETTLEMENT_DISCREPANCY` exception (HIGH severity) describing how many days it has been pending
- Prevents long-pending settlements from going unnoticed

The job can also be triggered manually via `POST /api/v1/admin/settlement-reconciler/run`.

---

## 15. Gap Filler — Polling for Missed Events

Webhooks can be missed due to network issues, provider outages, or misconfigured endpoints. The Gap Filler job compensates by periodically polling the provider APIs for recent events.

### GapFillerJob (every 15 minutes)

```
Compute window:
    from = now - 30 minutes  (configurable: app.polling.gap-filler-lookback-minutes)
    to   = now

Razorpay:
    fetchPayments(from, to)  ──► paginated Razorpay API call (page size 100)
    fetchRefunds(from, to)   ──► paginated Razorpay API call
    each result wrapped in synthetic webhook envelope → ingestAsync(payload, "razorpay", "polling")

Stripe:
    fetchCharges(from, to)   ──► cursor-paginated Stripe API call (limit 100)
    fetchRefunds(from, to)   ──► cursor-paginated Stripe API call
    each result wrapped in synthetic webhook envelope → ingestAsync(payload, "stripe", "polling")
```

### Synthetic envelope format

The polling services wrap raw API responses in a structure that matches the webhook payload format, so `WebhookIngestionService` and `NormalizationService` work identically for both sources.

**Razorpay example:**
```json
{
  "id": "poll_payments_0_0",
  "event": "payment.captured",
  "payload": {
    "payment": {
      "entity": { ...razorpay payment object... }
    }
  }
}
```

**Stripe example:**
```json
{
  "id": "poll_ch_ABC",
  "type": "charge.succeeded",
  "created": 1700000000,
  "data": {
    "object": { ...stripe charge object... }
  }
}
```

Deduplication in `WebhookIngestionService` (unique constraint on `provider + providerEventId`) ensures that events already received via webhook are silently ignored when they appear again via polling.

Metrics: `polling.gaps.filled` counter tracks how many events were picked up by polling.

---

## 16. Admin Operations

All admin endpoints are under `/api/v1/admin` and require a valid JWT. They accept an `X-Actor` header to identify who triggered the action (used in audit logs).

### Replay a webhook event

```
POST /api/v1/admin/replay
X-Actor: ops-engineer
{ "webhookEventId": 1234 }
```

Use case: A webhook event failed processing (e.g., NormalizationService bug). After deploying a fix, replay resets the event's `processed` flag and re-queues async processing.

**What happens:**
1. Load `WebhookEvent` by ID
2. Reset `processed=false`, `processedAt=null`, `processingError=null`
3. Call `TransactionProcessingService.processAsync(eventId, provider)` (async)
4. Log audit entry: `action=webhook_replayed`
5. Return `{"status": "queued", ...}`

### Admin-triggered polling

```
POST /api/v1/admin/poll
X-Actor: ops-engineer
{
  "provider": "razorpay",
  "from": "2024-01-15T00:00:00Z",
  "to": "2024-01-15T23:59:59Z"
}
```

Use case: A specific time window had a webhook outage. Trigger a targeted backfill for that window without waiting for the scheduled gap-filler.

**What happens:**
1. Call `RazorpayPollingService.fetchPayments(from, to)` + `fetchRefunds(from, to)`  
   (or Stripe equivalents for `"stripe"`)
2. For each fetched payload, call `WebhookIngestionService.ingestAsync(payload, provider, "admin-poll")`
3. Log audit entry: `action=admin_poll_triggered` with `fetched` count
4. Return `{"status": "accepted", "provider": "...", "fetched": N, ...}`

### Manual settlement reconciliation

```
POST /api/v1/admin/settlement-reconciler/run
X-Actor: ops-engineer
```

Triggers `SettlementReconcilerJob.run()` on demand. Returns a summary of what was processed.

### Audit log search

```
GET /api/v1/admin/audit-logs?entityType=webhook_event&entityId=1234
GET /api/v1/admin/audit-logs?actor=ops-engineer
```

---

## 17. Dashboard and Metrics

### Summary endpoint

```
GET /api/v1/dashboard/summary?days=7
```

Returns a comprehensive summary for the given look-back window:

| Key | Description |
|---|---|
| `totalTransactions` | Count of all transactions in the window |
| `matched` | Count with `reconciliationStatus=MATCHED` |
| `openExceptions` | Count of OPEN exception records |
| `matchRate` | `matched / total * 100` (percentage) |
| `byProvider` | Per-provider breakdown: total count + exception count |
| `byExceptionType` | Exception count grouped by type |
| `recentExceptions` | 5 most recent exceptions (id, type, severity, status) |

### Metrics endpoint

```
GET /api/v1/dashboard/metrics
```

Returns:

| Key | Description |
|---|---|
| `transactionsProcessed` | All-time transaction count |
| `openExceptions` | Open + in-review exception count |
| `matchRate` | All-time match rate |
| `webhookQueueDepth` | Currently 0 (placeholder for future queue depth metric) |
| `status` | Always `"ok"` |

### Prometheus metrics (Micrometer)

Available at `GET /actuator/prometheus`:

| Metric | Type | Description |
|---|---|---|
| `reconciliation.exceptions.created` | Counter | Total exceptions created by the engine |
| `reconciliation.run.duration` | Timer | Time taken for a full engine run |
| `polling.gaps.filled` | Counter | Events fetched by gap-filler polling |

---

## 18. Audit Logging

`AuditService.log()` creates an immutable `AuditLog` record for every sensitive operation.

### What gets audited

| Action | Triggered by |
|---|---|
| `webhook_replayed` | AdminService.replay() |
| `admin_poll_triggered` | AdminService.poll() |
| `settlement_reconciler_run` | SettlementReconcilerJob.run() |
| `exception_status_updated` | ExceptionQueryService.update() |
| `RESOLVE_EXCEPTION` | ExceptionController (legacy path) |

### AuditLog fields

| Field | Description |
|---|---|
| `actor` | Who performed the action (from `X-Actor` header or `"scheduler"`) |
| `action` | Name of the action |
| `entityType` | Type of entity affected (`webhook_event`, `exception_record`, etc.) |
| `entityId` | Primary key of the affected entity |
| `oldValue` | State before the change (JSON) |
| `newValue` | State after the change (JSON) |
| `ipAddress` | Client IP (stored as PostgreSQL `inet` type) |
| `createdAt` | Timestamp of the audit entry |

---

## 19. Scheduled Jobs Summary

All jobs use [db-scheduler](https://github.com/kagkarlsson/db-scheduler), a persistent distributed scheduler backed by a PostgreSQL table (`scheduled_tasks`). This ensures that in a multi-instance deployment, each job runs exactly once.

| Job | Schedule | Purpose |
|---|---|---|
| `ReconciliationJob` | Every 5 minutes | Runs all 5 reconciliation rules |
| `GapFillerJob` | Every 15 minutes | Polls Razorpay + Stripe for missed events |
| `SettlementReconcilerJob` | Daily at 2 AM | Closes settled settlements; flags overdue pending ones |

---

## 20. Data Model

### Table: `webhook_events`

| Column | Type | Notes |
|---|---|---|
| `id` | bigint PK | Auto-generated |
| `provider` | varchar(30) | `razorpay` or `stripe` |
| `provider_event_id` | varchar(120) | UNIQUE with provider |
| `event_type` | varchar(80) | e.g. `payment.captured` |
| `payload` | jsonb | Full raw payload |
| `processed` | boolean | Processing complete |
| `received_at` | timestamptz | When we received the event |
| `processed_at` | timestamptz | When processing completed |
| `processing_error` | text | Error message if processing failed |
| `source` | varchar(30) | `webhook`, `polling`, `admin-poll` |
| `signature_valid` | boolean | Always true (verified before saving) |

### Table: `transactions`

| Column | Type | Notes |
|---|---|---|
| `id` | bigint PK | |
| `provider` | varchar(30) | |
| `provider_transaction_id` | varchar(120) | UNIQUE with provider |
| `provider_event_id` | varchar(120) | Most recent event that updated this |
| `merchant_id` | varchar(60) | |
| `order_id` | varchar(120) | Internal order ID |
| `provider_order_id` | varchar(120) | Provider's order ID |
| `parent_transaction_id` | bigint FK | Refund → Payment link |
| `event_type` | enum | `PAYMENT`, `REFUND`, `CHARGEBACK` |
| `status` | enum | `AUTHORIZED`, `CAPTURED`, `FAILED`, `REFUNDED`, `DISPUTED` |
| `reconciliation_status` | enum | `PENDING`, `PENDING_SETTLEMENT`, `MATCHED`, `EXCEPTION` |
| `presentment_amount` | bigint | In smallest currency unit |
| `presentment_currency` | char(3) | `INR`, `USD`, etc. |
| `fee_amount` | bigint | Platform fee |
| `tax_amount` | bigint | GST or tax on fee |
| `net_amount` | bigint | `presentment - fee - tax` |
| `settlement_id` | varchar(120) | Provider's settlement ID |
| `user_id` | bigint FK | Resolved payer identity |
| `exception_id` | bigint FK | Linked exception record |
| `payer_email` | varchar | |
| `payer_phone` | varchar | |
| `payment_method` | varchar | `card`, `upi`, `netbanking` |
| `event_occurred_at` | timestamptz | Provider-reported event time |
| `matched_at` | timestamptz | When reconciliation succeeded |

### Table: `users`

| Column | Type | Notes |
|---|---|---|
| `id` | bigint PK | |
| `merchant_id` | varchar(60) | |
| `email` | varchar | UNIQUE with merchant_id |
| `phone` | varchar | UNIQUE with merchant_id |
| `name` | varchar | |
| `first_seen_at` | timestamptz | |
| `last_seen_at` | timestamptz | Updated on every transaction |
| `total_txn_count` | int | Atomically incremented |
| `total_txn_amount` | bigint | Running total |
| `failed_txn_count` | int | Count of failed transactions |

### Table: `exception_records`

| Column | Type | Notes |
|---|---|---|
| `id` | bigint PK | |
| `merchant_id` | varchar(60) | |
| `exception_type` | enum | See exception types above |
| `severity` | enum | `LOW`, `MEDIUM`, `HIGH`, `CRITICAL` |
| `transaction_id` | bigint FK | Linked transaction (nullable) |
| `settlement_id` | bigint FK | Linked settlement (nullable) |
| `expected_amount` | bigint | What was expected |
| `actual_amount` | bigint | What was found |
| `discrepancy_amount` | bigint | `actual - expected` |
| `currency` | char(3) | |
| `description` | text | Human-readable explanation |
| `status` | enum | `OPEN`, `IN_REVIEW`, `RESOLVED`, `IGNORED` |
| `resolved_by` | varchar(60) | Actor who closed the exception |
| `resolved_at` | timestamptz | |
| `resolution_notes` | text | Notes from the ops team |
| `detected_at` | timestamptz | When the rule created this |

### Table: `settlements`

| Column | Type | Notes |
|---|---|---|
| `id` | bigint PK | |
| `provider` | varchar(30) | |
| `provider_settlement_id` | varchar(120) | |
| `merchant_id` | varchar(60) | |
| `gross_amount` | bigint | |
| `total_fees` | bigint | |
| `total_tax` | bigint | |
| `net_amount` | bigint | `gross - fees - tax` |
| `currency` | char(3) | |
| `bank_credit_amount` | bigint | Actual bank credit |
| `bank_credit_date` | date | |
| `utr_number` | varchar(60) | Bank UTR reference |
| `settlement_status` | enum | `PENDING`, `SETTLED`, `MATCHED_TO_BANK`, `DISCREPANT`, `ON_HOLD` |
| `transaction_count` | int | Expected number of transactions |
| `settled_at` | timestamptz | |

### Table: `audit_logs`

| Column | Type | Notes |
|---|---|---|
| `id` | bigint PK | |
| `actor` | varchar | Who performed the action |
| `action` | varchar | Action name |
| `entity_type` | varchar | Type of entity affected |
| `entity_id` | bigint | PK of the affected entity |
| `old_value` | jsonb | State before change |
| `new_value` | jsonb | State after change |
| `ip_address` | inet | Client IP (PostgreSQL native type) |
| `created_at` | timestamptz | Immutable |

---

## 21. API Reference

### Webhook endpoints (public — no JWT required)

| Method | Path | Header | Description |
|---|---|---|---|
| POST | `/webhooks/razorpay` | `X-Razorpay-Signature` | Receive Razorpay event |
| POST | `/webhooks/stripe` | `Stripe-Signature` | Receive Stripe event |

Both return `200 "received"` on success, `400 "Invalid signature"` on failure.

### Dashboard

| Method | Path | Params | Description |
|---|---|---|---|
| GET | `/api/v1/dashboard/summary` | `days` (default 7) | Transaction and exception summary |
| GET | `/api/v1/dashboard/metrics` | — | Real-time metrics |

### Exceptions

| Method | Path | Params / Body | Description |
|---|---|---|---|
| GET | `/api/v1/exceptions` | `days`, `status`, `provider`, `type`, `page`, `limit` | List exceptions |
| GET | `/api/v1/exceptions/{id}` | — | Exception detail + linked transaction + audit trail |
| PATCH | `/api/v1/exceptions/{id}` | `{status, notes}` + `X-Actor` header | Update exception status |

### Transactions

| Method | Path | Params | Description |
|---|---|---|---|
| GET | `/api/v1/transactions` | `provider`, `status`, `from`, `to`, `page`, `limit` | List transactions |
| GET | `/api/v1/transactions/{id}` | — | Transaction detail |

### Settlements

| Method | Path | Params | Description |
|---|---|---|---|
| GET | `/api/v1/settlements` | `provider`, `status`, `dateFrom`, `dateTo`, `page`, `limit` | List settlements |
| GET | `/api/v1/settlements/{id}` | — | Settlement detail with linked transaction count |
| GET | `/api/v1/settlements/{id}/transactions` | — | All transactions in a settlement |

### Admin (requires JWT + `X-Actor` header)

| Method | Path | Body | Description |
|---|---|---|---|
| POST | `/api/v1/admin/replay` | `{webhookEventId}` | Replay a failed webhook event |
| POST | `/api/v1/admin/poll` | `{provider, from, to}` | Backfill events for a time window |
| POST | `/api/v1/admin/settlement-reconciler/run` | — | Manually trigger settlement reconciler |
| GET | `/api/v1/admin/audit-logs` | `entityType`, `entityId`, `actor` | Search audit logs |

---

## 22. Configuration Reference

All configuration is in `src/main/resources/application.yml`. Secrets come from environment variables.

```yaml
app:
  merchant:
    id: ${MERCHANT_ID:merchant_001}             # Default merchant ID

  razorpay:
    key-id: ${RAZORPAY_KEY_ID}                  # Razorpay API key for polling
    key-secret: ${RAZORPAY_KEY_SECRET}          # Razorpay API secret for polling
    webhook-secret: ${RAZORPAY_WEBHOOK_SECRET}  # Used to verify webhook signatures

  stripe:
    secret-key: ${STRIPE_SECRET_KEY}            # Stripe API key for polling
    webhook-secret: ${STRIPE_WEBHOOK_SECRET}    # Used to verify webhook signatures

  jwt:
    secret: ${JWT_SECRET}                       # HMAC secret for signing JWTs
    expiry-hours: 24

  reconciliation:
    run-interval-minutes: 5                     # How often ReconciliationJob runs
    missing-capture-threshold-hours: 24         # Threshold for MissingCaptureRule
    amount-tolerance-paisa: 100                 # Tolerance for SettlementTotalRule (₹1)
    settlement-overdue-days: 7                  # Threshold for SettlementReconcilerJob Phase 2

  polling:
    gap-filler-interval-minutes: 15             # How often GapFillerJob runs
    gap-filler-lookback-minutes: 30             # How far back GapFillerJob looks

  encryption:
    key: ${APP_ENCRYPTION_KEY}                  # AES key for EncryptionService

db-scheduler:
  enabled: true
  threads: 5
  polling-interval: 10s
  table-name: scheduled_tasks
```

### Environment variables required in production

| Variable | Purpose |
|---|---|
| `DB_URL` | PostgreSQL JDBC URL |
| `DB_USERNAME` | Database user |
| `DB_PASSWORD` | Database password |
| `RAZORPAY_KEY_ID` | Razorpay API key (for polling) |
| `RAZORPAY_KEY_SECRET` | Razorpay API secret (for polling) |
| `RAZORPAY_WEBHOOK_SECRET` | Webhook HMAC secret |
| `STRIPE_SECRET_KEY` | Stripe API key (for polling) |
| `STRIPE_WEBHOOK_SECRET` | Webhook HMAC secret |
| `JWT_SECRET` | JWT signing key |
| `APP_ENCRYPTION_KEY` | AES encryption key |
| `MERCHANT_ID` | Merchant identifier |

---

## 23. Testing Strategy

### Test philosophy

All existing tests are unit tests using Mockito mocks. There is no in-memory database dependency — tests wire real service objects with mocked repositories and external clients. This keeps tests fast and focused on business logic.

### Test files and what they cover

| Test file | Coverage |
|---|---|
| `WebhookIngestionServiceTest` | Save new event + queue processing; ignore duplicate via unique violation |
| `WebhookControllerTest` | Signature validation pass/fail; controller returns correct HTTP status |
| `RazorpaySignatureServiceTest` | HMAC-SHA256 verification logic |
| `StripeSignatureServiceTest` | Stripe signature verification |
| `TransactionProcessingServiceTest` | User resolution; refund linking; error path sets processingError |
| `NormalizationServiceTest` | Razorpay and Stripe payloads produce correct canonical Transaction fields |
| `TransactionServiceTest` | Upsert insert path; upsert merge path (newer wins); upsert skip path (older loses) |
| `ReconciliationEngineTest` | One rule failing does not block subsequent rules |
| `RuleBehaviorTest` | Each of the 4 transaction-level rules: candidate detection + exception creation |
| `ExceptionRecordServiceTest` | Deduplication: does not create a second exception for same (type, txnId) |
| `AdminServicePollTest` | Razorpay poll fetches payments+refunds; Stripe poll fetches charges+refunds; unknown provider throws; audit is called |
| `AdminPollToIngestionIntegrationTest` | Full chain: AdminService → real WebhookIngestionService → repo + processingService. Verifies event saved with correct source and provider. Tests deduplication in the chain. |
| `SettlementReconcilerJobTest` | Phase 1: MATCHED_TO_BANK when no exceptions; stays SETTLED when exceptions open. Phase 2: creates exception for overdue; skips already-flagged. Mixed batch; audit always called. |
| `ExceptionQueryServiceTest` | list() window filter; status filter; type filter; pagination; summary counts. detail() with and without linked transaction. update() for each terminal status (RESOLVED, IGNORED sets resolvedAt). |

### Running tests

```bash
./gradlew test
```

All 53 tests pass with no failures.

### Key patterns used in tests

**Constructing services directly:**
```java
// Services are instantiated with mocked dependencies — no Spring context needed
service = new WebhookIngestionService(
    mock(WebhookEventRepository.class),
    mock(TransactionProcessingService.class),
    new ObjectMapper()
);
```

**Integration test pattern (AdminPollToIngestionIntegrationTest):**
Real service objects are wired together, with only infrastructure mocked (repositories, external API clients). This tests that the services communicate correctly without needing a database.

**ArgumentCaptor for verifying what was saved:**
```java
ArgumentCaptor<WebhookEvent> captor = ArgumentCaptor.forClass(WebhookEvent.class);
verify(repository).save(captor.capture());
assertThat(captor.getValue().getSource()).isEqualTo("admin-poll");
```
