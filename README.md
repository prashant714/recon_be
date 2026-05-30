# Reconciliation Platform — Complete Implementation Guide

This document explains every part of the system end-to-end. A reader with no prior knowledge of this codebase should be able to understand what each component does, why it exists, and how everything connects.

---

## Table of Contents

1. [What This System Does](#1-what-this-system-does)
2. [Technology Stack](#2-technology-stack)
3. [High-Level Architecture](#3-high-level-architecture)
4. [Project Structure](#4-project-structure)
5. [Security Layer](#5-security-layer)
6. [Merchant Management & Authentication](#6-merchant-management--authentication)
7. [Provider Connections](#7-provider-connections)
8. [Webhook Ingestion Flow](#8-webhook-ingestion-flow)
9. [Transaction Processing Flow](#9-transaction-processing-flow)
10. [Normalization — Provider to Canonical Format](#10-normalization--provider-to-canonical-format)
11. [User Identity Resolution](#11-user-identity-resolution)
12. [Transaction Upsert Logic](#12-transaction-upsert-logic)
13. [Order Matching Flow](#13-order-matching-flow)
14. [Reconciliation Engine](#14-reconciliation-engine)
15. [Reconciliation Rules Deep Dive](#15-reconciliation-rules-deep-dive)
16. [Exception Management](#16-exception-management)
17. [Settlement Reconciliation](#17-settlement-reconciliation)
18. [Bank Statement Matching](#18-bank-statement-matching)
19. [Gap Filler — Polling for Missed Events](#19-gap-filler--polling-for-missed-events)
20. [Admin Operations](#20-admin-operations)
21. [Dashboard and Metrics](#21-dashboard-and-metrics)
22. [Audit Logging](#22-audit-logging)
23. [Scheduled Jobs Summary](#23-scheduled-jobs-summary)
24. [Data Model](#24-data-model)
25. [API Reference](#25-api-reference)
26. [Configuration Reference](#26-configuration-reference)
27. [Testing Strategy](#27-testing-strategy)

---

## 1. What This System Does

Payment providers like Razorpay and Stripe send webhook events whenever something happens — a payment is captured, a refund is processed, a dispute is raised. These events arrive in real time and need to be stored, normalized into a common format, and checked for correctness.

**Reconciliation** is the process of verifying that every payment event is accounted for, matches expected amounts, has no anomalies, and that money from the provider actually arrived in the merchant's bank account.

This platform:

- **Receives** webhook events from Razorpay and Stripe
- **Verifies** each event's HMAC signature before accepting it
- **Normalizes** provider-specific payloads into a single canonical `Transaction` format
- **Resolves** the end-user identity behind each transaction
- **Matches** payments against pre-registered merchant orders with amount tolerance checks
- **Runs reconciliation rules** on a schedule to flag anomalies (missing captures, orphaned refunds, duplicate charges, settlement discrepancies)
- **Matches settlements** against bank statement entries using a three-pass UTR → amount+date → narration strategy
- **Creates exception records** for every anomaly found
- **Allows ops teams** to review, resolve, or ignore exceptions
- **Polls providers** on a schedule to catch any events missed by webhooks
- **Stores encrypted per-merchant provider credentials** for multi-tenant API polling
- **Provides dashboards** showing match rates, open exceptions, activity feeds, and trend data

---

## 2. Technology Stack

| Component | Choice | Notes |
|---|---|---|
| Language | Java 21 | Uses virtual threads, switch expressions |
| Framework | Spring Boot 3.2.12 | Web, Security, JPA, Actuator |
| Database | PostgreSQL 16 | All tables use identity columns |
| Migrations | Flyway | V1–V19 migrations, runs on startup |
| Scheduling | db-scheduler 14 | Distributed scheduler backed by PostgreSQL — safe for multi-instance deployments |
| Async | Spring `@Async` with virtual threads | Java 21 virtual threads enabled via `AsyncConfig` |
| Auth | JWT (jjwt 0.12.5) | Stateless; merchant and admin token types; webhook endpoints are public |
| Rate Limiting | Bucket4j 8.10.1 | Token bucket, per-IP, webhooks only |
| Razorpay SDK | razorpay-java 1.4.3 | Used for polling |
| Stripe SDK | stripe-java 25.3.0 | Used for polling |
| API Docs | SpringDoc OpenAPI 2.3.0 | Available at `/swagger-ui.html` |
| Metrics | Micrometer + Prometheus | Exposed at `/actuator/prometheus` |
| Build | Gradle 8 | Java 21 toolchain |
| Containerization | Docker (multi-stage build) | eclipse-temurin:21-jre-alpine |

---

## 3. High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                         INBOUND EVENTS                              │
│                                                                     │
│  Razorpay ──► POST /webhooks/razorpay ──► RazorpayWebhookController │
│  Stripe   ──► POST /webhooks/stripe   ──► StripeWebhookController   │
│                                                                     │
│          Both verify HMAC signature, then call:                     │
│                    WebhookIngestionService                           │
│                           │                                         │
│               ┌───────────┴─────────────────┐                      │
│               ▼                             ▼                      │
│       save WebhookEvent         TransactionProcessingService        │
│       (raw payload)              (async, virtual threads)           │
│                                        │                            │
│                           NormalizationService                      │
│                           UserIdentityService                       │
│                           TransactionService.upsert()               │
│                           OrderMatchingService.matchOnCapture()     │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│                       SCHEDULED JOBS                                │
│                                                                     │
│  ReconciliationJob (every 5 min) ──► ReconciliationEngine           │
│                                           │                         │
│          ┌──────────┬────────────┬────────┼──────────┬──────────┐  │
│          ▼          ▼            ▼        ▼          ▼          ▼  │
│   ExactIdMatch  Missing      OrphanRefund Duplicate Settlement Provider│
│   Rule         Capture                  Capture    Total      Report │
│                                                               Match  │
│                                                                     │
│  GapFillerJob (every 15 min) ──► RazorpayPollingService             │
│                               └─► StripePollingService              │
│                               both feed ──► WebhookIngestionService │
│                                                                     │
│  SettlementReconcilerJob (2 AM daily) ──► close SETTLED settlements │
│                                      └─► flag overdue PENDING ones  │
│                                                                     │
│  SettlementReportSyncJob (every 2 hours) ──► sync provider lines    │
│  BankStatementCatchUpJob (9 AM daily) ──► retry PENDING matches     │
│                                       └─► flag overdue entries      │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│                      MERCHANT SELF-SERVICE                          │
│                                                                     │
│  POST /api/v1/merchants/register  ──► create merchant, get API key  │
│  POST /api/v1/merchants/login     ──► email + password → JWT        │
│  POST /api/v1/merchants/auth      ──► API key → JWT                 │
│  POST /api/v1/merchants/refresh   ──► extend JWT                    │
│  POST /api/v1/connections         ──► store encrypted provider creds│
│  GET  /api/v1/merchants/me        ──► merchant profile              │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│                        OPERATIONS APIs                              │
│                                                                     │
│  GET  /api/v1/dashboard/summary      ──► DashboardService           │
│  GET  /api/v1/dashboard/metrics      ──► DashboardService           │
│  GET  /api/v1/dashboard/activity     ──► DashboardService           │
│  GET  /api/v1/dashboard/trends       ──► DashboardService           │
│  GET  /api/v1/exceptions             ──► ExceptionQueryService      │
│  GET  /api/v1/transactions           ──► TransactionQueryService    │
│  GET  /api/v1/settlements            ──► SettlementService          │
│  GET  /api/v1/orders                 ──► OrderService               │
│  POST /api/v1/bank-statements/upload ──► CSV upload + matching      │
│  POST /api/v1/admin/replay           ──► AdminService               │
│  POST /api/v1/admin/poll             ──► AdminService (live poll)   │
│  POST /api/v1/admin/settlement-reconciler/run ──► manual trigger    │
│  POST /api/v1/admin/reconcile-transactions    ──► batch reconcile   │
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
├── order/                           # Customer order management
│   ├── controller/OrderController.java
│   ├── entity/Order.java
│   ├── repository/OrderRepository.java
│   └── service/                     # OrderService, OrderMatchingService
│
├── bank/                            # Bank statement reconciliation
│   ├── controller/                  # BankStatementController,
│   │                                # ReconciliationBankStatementController
│   ├── entity/                      # BankStatementEntry, BankStatementUpload
│   ├── repository/                  # BankStatementEntryRepository,
│   │                                # BankStatementUploadRepository
│   └── service/                     # BankStatementIngestionService (CSV/TSV parser),
│                                    # BankStatementMatchingService (3-pass matching),
│                                    # BankStatementUploadService (upload lifecycle)
│
├── merchant/                        # Merchant account management
│   ├── controller/MerchantController.java
│   ├── entity/Merchant.java
│   ├── repository/MerchantRepository.java
│   └── service/MerchantService.java
│
├── connection/                      # Per-merchant provider credentials
│   ├── controller/ProviderConnectionController.java
│   ├── entity/ProviderConnection.java
│   ├── repository/ProviderConnectionRepository.java
│   └── service/ProviderConnectionService.java
│
├── user/                            # Payer identity resolution
│   ├── entity/User.java
│   ├── repository/UserRepository.java
│   └── service/UserIdentityService.java
│
├── reconciliation/                  # Reconciliation engine and rules
│   ├── service/ReconciliationEngine.java
│   ├── rules/                       # ReconciliationRule (interface)
│   │                                # ExactIdMatchRule, MissingCaptureRule,
│   │                                # OrphanRefundRule, DuplicateCaptureRule,
│   │                                # SettlementTotalRule, ProviderReportMatchRule,
│   │                                # UnmatchedOrderRule
│   └── job/                         # ReconciliationJob, GapFillerJob,
│                                    # SettlementReconcilerJob, BankStatementCatchUpJob,
│                                    # SettlementReportSyncJob
│
├── exception_record/                # Anomaly tracking
│   ├── controller/ExceptionController.java
│   ├── entity/ExceptionRecord.java
│   ├── repository/ExceptionRecordRepository.java
│   └── service/                     # ExceptionRecordService, ExceptionQueryService
│
├── settlement/                      # Settlement records and report lines
│   ├── controller/SettlementController.java
│   ├── entity/                      # Settlement.java, SettlementReportLine.java
│   ├── repository/                  # SettlementRepository, SettlementReportLineRepository
│   └── service/SettlementService.java
│
├── dashboard/                       # Summary metrics, activity, trends
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
├── paymentflow/                     # Payment lifecycle event tracking
│   ├── entity/PaymentFlowEvent.java
│   ├── repository/PaymentFlowEventRepository.java
│   └── service/PaymentFlowEventService.java
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
│   ├── AsyncConfig.java             # Virtual thread executor
│   ├── SchedulerConfig.java         # db-scheduler bean
│   ├── MerchantConfig.java          # Merchant properties binding
│   └── WebConfig.java               # CORS
│
└── common/
    ├── enums/                       # Provider, EventType, TransactionStatus,
    │                                # ReconciliationStatus, ExceptionType,
    │                                # ExceptionStatus, Severity, SettlementStatus,
    │                                # OrderStatus, BankEntryStatus,
    │                                # ReportLineMatchStatus,
    │                                # BankStatementUploadStatus, ConnectionStatus
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

A `OncePerRequestFilter` that runs on every request. It reads the `Authorization: Bearer <token>` header, validates the JWT using `JwtConfig` (HMAC-SHA256 signed), and extracts claims to determine who is making the request.

**Token types supported:**

| `type` claim | Identity claim extracted | Set as request attribute |
|---|---|---|
| `merchant` | `merchantId` | `merchantId` attribute on request |
| `admin` | `email` | Sets admin context in security principal |

If no token is present or the token is invalid, the filter leaves the security context unauthenticated, and Spring Security's authorization rules handle the rest.

### Layer 3 — Spring Security Authorization (`SecurityConfig`)

Route-level authorization rules:

| Path pattern | Rule |
|---|---|
| `OPTIONS /**` | Permit all (CORS preflight) |
| `/webhooks/**` | Permit all (no JWT required — signature verified at controller level) |
| `/api/v1/merchants/register` | Permit all (merchant self-registration) |
| `/api/v1/merchants/login` | Permit all (email + password auth) |
| `/api/v1/merchants/auth` | Permit all (API key auth) |
| `/api/v1/merchants/reset-key` | Permit all (API key reset) |
| `/actuator/health`, `/actuator/prometheus` | Permit all |
| `/swagger-ui/**`, `/api-docs/**` | Permit all |
| `/api/v1/**` | Must be authenticated (valid JWT) |

Sessions are stateless (no HTTP session created). CSRF is disabled for `/webhooks/**` and `/api/**`. HTTP Basic and Form Login are disabled.

### Webhook Signature Verification

Webhook controllers perform HMAC verification before any processing.

**Razorpay** (`RazorpaySignatureService`):
- Computes `HMAC-SHA256(rawBody, webhookSecret)` and hex-encodes it
- Resolves the matching merchant by checking per-merchant webhook secrets, then falls back to the default secret
- Compares with the `X-Razorpay-Signature` header using constant-time comparison (`MessageDigest.isEqual`)
- Returns HTTP 400 if verification fails

**Stripe** (`StripeSignatureService`):
- Reads the `Stripe-Signature` header (contains timestamp + signatures)
- Validates using Stripe's SDK (`WebhookSignatureVerifier`)
- Returns HTTP 400 if verification fails

---

## 6. Merchant Management & Authentication

The platform supports multi-tenant operation with self-service merchant registration and multiple authentication methods.

### Merchant Entity

| Field | Type | Notes |
|---|---|---|
| `id` | bigint PK | Auto-generated |
| `merchantId` | varchar(60) | Unique business identifier |
| `name` | varchar(120) | Display name |
| `email` | varchar(254) | Unique, used for login |
| `apiKeyHash` | varchar(256) | BCrypt hash of API key |
| `passwordHash` | varchar(256) | BCrypt hash of login password (optional) |
| `webhookSecret` | varchar(256) | Per-merchant webhook signing secret |
| `status` | varchar(20) | Default `"ACTIVE"` |
| `lastBankStatementUploadAt` | timestamptz | Updated on each successful bank statement upload |
| `createdAt` | timestamptz | Auto-set on create |
| `updatedAt` | timestamptz | Auto-set on create/update |

### Registration

```
POST /api/v1/merchants/register
{
  "merchantId": "merchant_001",
  "name": "Acme Corp",
  "email": "admin@acme.com",
  "password": "optional-password"
}
```

**What happens:**
1. Generates a 32-byte Base64 URL-safe API key
2. Generates a webhook secret (UUID)
3. Stores BCrypt hash of the API key (never stores plaintext)
4. Optionally stores BCrypt hash of the password
5. Returns the API key **one time only** — it cannot be retrieved again

### Authentication Methods

**Method 1 — API Key Auth** (server-to-server):
```
POST /api/v1/merchants/auth
{ "merchantId": "merchant_001", "apiKey": "the-api-key" }
```
Validates the API key against the stored BCrypt hash and returns a merchant JWT.

**Method 2 — Email + Password Login** (frontend / human):
```
POST /api/v1/merchants/login
{ "email": "admin@acme.com", "password": "the-password" }
```
Validates credentials and returns a merchant JWT. Requires a password to have been set during registration or via `set-password`.

**Method 3 — Token Refresh**:
```
POST /api/v1/merchants/refresh
Authorization: Bearer <valid-jwt>
```
Issues a new JWT with extended expiry from a still-valid token.

### Merchant JWT Claims

```json
{
  "sub": "merchant_001",
  "type": "merchant",
  "merchantId": "merchant_001",
  "iat": 1700000000,
  "exp": 1700086400
}
```

All authenticated API endpoints extract `merchantId` from the JWT to scope data access.

### Self-Service Operations

| Endpoint | Purpose |
|---|---|
| `POST /api/v1/merchants/set-password` | Set or update login password (requires JWT) |
| `POST /api/v1/merchants/reset-key` | Generate new API key (returns one-time) |
| `GET /api/v1/merchants/me` | Get merchant profile (requires JWT) |

---

## 7. Provider Connections

Merchants store their Razorpay and Stripe API credentials in the platform so that polling jobs and admin-triggered polls can authenticate against the provider APIs on a per-merchant basis.

### ProviderConnection Entity

| Field | Type | Notes |
|---|---|---|
| `id` | bigint PK | Auto-generated |
| `merchantId` | varchar(60) | Owner merchant |
| `provider` | varchar(30) | `razorpay` or `stripe` |
| `apiKeyEncrypted` | text | AES-encrypted API key |
| `secretEncrypted` | text | AES-encrypted API secret |
| `apiKeyMasked` | varchar | First 4 + `****` + last 4 chars (for display) |
| `status` | enum | `ACTIVE` or `DISABLED` |
| `createdAt` | timestamptz | |
| `updatedAt` | timestamptz | |

Unique constraint on `(merchantId, provider)` — one connection per provider per merchant.

### API

**Save/update connection:**
```
POST /api/v1/connections
{
  "provider": "razorpay",
  "apiKey": "rzp_live_xxxxx",
  "secret": "xxxxx"
}
```
Encrypts credentials using `EncryptionService` (AES) before storage. If a connection already exists for the provider, it is updated.

**List connections:**
```
GET /api/v1/connections
```
Returns connections with masked API keys (never exposes plaintext or encrypted values).

### Usage in Polling

The `GapFillerJob` and `AdminService.poll()` use `ProviderConnectionService` to:
1. Find all active connections for a given provider
2. Decrypt the API key and secret at runtime
3. Initialize the provider SDK client with per-merchant credentials
4. Poll for missed events scoped to that merchant

---

## 8. Webhook Ingestion Flow

This is the entry point for all payment events, whether they arrive via live webhook or via polling.

### Step-by-step

```
Provider  ──POST──►  WebhookController
                          │
                    verify HMAC signature
                    (resolve merchantId from webhook secret)
                          │ (fail → 400)
                          │ (pass → continue)
                          ▼
                   WebhookIngestionService.ingestAsync(rawBody, provider, source)
                          │
                    parse JSON, extract event ID and event type
                    record PaymentFlowEvent: INGEST_RECEIVED
                          │
                    check if event already exists
                    (existsByProviderAndProviderEventId)
                          │ (duplicate → record INGEST_DUPLICATE, return)
                          │ (new → continue)
                          ▼
                    build WebhookEvent entity
                    webhookEventRepository.save(event)
                          │
                    ┌─────┴──────────────────────────────┐
                    │  DataIntegrityViolationException?   │
                    │  (UNIQUE provider + event_id)       │
                    │  → duplicate race condition,        │
                    │    silently ignored                 │
                    └─────────────────────────────────────┘
                          │ (saved successfully)
                    record PaymentFlowEvent: INGEST_STORED
                          ▼
                   processingService.processAsync(eventId, provider)
                          │ (returns immediately — fire-and-forget)
                          ▼
                   Controller returns HTTP 200 "received"
```

**Key design decisions:**

- The controller returns `200 OK` immediately after saving the raw event. Processing happens asynchronously. This ensures the provider's retry logic is not triggered unnecessarily.
- The `source` field on `WebhookEvent` distinguishes whether the event came from a live webhook (`"webhook"`), the polling gap-filler (`"polling"`), or an admin-triggered poll (`"admin-poll"`).
- Deduplication uses both a pre-save existence check and a database unique constraint on `(provider, provider_event_id)`. The double guard handles application-level race conditions.
- `PaymentFlowEvent` records track every stage of the lifecycle (`INGEST_RECEIVED`, `INGEST_DUPLICATE`, `INGEST_STORED`) for auditability.

### WebhookEvent entity fields

| Field | Description |
|---|---|
| `provider` | `"razorpay"` or `"stripe"` |
| `providerEventId` | The event ID from the provider's payload |
| `eventType` | e.g. `payment.captured`, `charge.refunded` |
| `payload` | Full raw JSON stored as a JSONB column |
| `processed` | `false` initially, set to `true` after processing |
| `processingError` | Populated if processing threw an exception |
| `source` | `webhook`, `polling`, or `admin-poll` |
| `signatureValid` | Always `true` — set at ingestion (signature verified at controller) |
| `merchantId` | Resolved from webhook signature verification |

---

## 9. Transaction Processing Flow

`TransactionProcessingService.processAsync()` runs on virtual threads (Java 21, configured via `AsyncConfig`). It is annotated with `@Async` and `@Transactional`.

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
   if status == CAPTURED:
        └──► OrderMatchingService.matchOnCapture(savedTransaction)
                    │ (match against pre-registered orders)
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

## 10. Normalization — Provider to Canonical Format

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
| `merchantId` | resolved from webhook signature | per-merchant scoping |
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

## 11. User Identity Resolution

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

## 12. Transaction Upsert Logic

`TransactionService.upsert()` handles both new transactions and updates to existing ones. The key design principle is **event-time ordering**: a newer event always wins over an older one for the same transaction.

```
find existing by (provider, providerTransactionId)
        │
        ├── not found → INSERT and return
        │
        └── found:
                compare incoming.eventOccurredAt vs current.eventOccurredAt
                        │
                        ├── incoming is older → SKIP, return current
                        │
                        ├── incoming is same time, status does not advance → SKIP
                        │
                        └── incoming is newer (or advances status) → MERGE mutable fields
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

**Status rank ordering** (used to decide if a same-time event advances state):

`PENDING < AUTHORIZED < FAILED/CANCELLED < CAPTURED < PARTIALLY_REFUNDED < REFUNDED < DISPUTED`

**Why `firstNonBlank` for string fields?** Once an orderId is set, it should not be cleared by a later event that omits it. Provider APIs sometimes omit fields in later events that were present in earlier ones.

**Advisory locking:** Before the upsert, `TransactionRepository.lockProviderTransactionId()` acquires a PostgreSQL advisory lock on `(provider, transactionId)`. This prevents concurrent ingestion of the same transaction from creating duplicates.

**Refund linking:** When a `REFUND` event is processed, `TransactionService.linkRefundToParent()` extracts the original payment ID from the payload and looks up the parent `Transaction`. If found, `parentTransactionId` is set on the refund record, creating the payment-refund relationship.

---

## 13. Order Matching Flow

The order domain allows merchants to pre-register expected payment amounts for each order. When a payment is captured, the platform automatically matches it against the correct order and flags any amount discrepancies.

### Order entity

An `Order` represents a merchant-created expectation:
- `orderId` — merchant's internal order reference (unique per merchant)
- `providerOrderId` — provider-assigned order ID (e.g., Razorpay `order_` ID)
- `expectedAmount` — the amount the merchant expects to collect
- `orderStatus` — `CREATED → PAYMENT_RECEIVED / OVERPAID / UNDERPAID / CANCELLED / REFUNDED`
- `transactionId` — FK to the matched `Transaction` once payment is received
- `amountMatched` — boolean flag indicating whether amounts match
- `discrepancyAmount` — difference between `expectedAmount` and actual payment

### Two-way matching

`OrderMatchingService` handles matching in both directions, so order and payment can arrive in any sequence.

**Direction 1: Payment captured first, then order registered**

```
OrderMatchingService.matchOnCapture(transaction)
        │
   find Order by (merchantId, orderId) or (merchantId, providerOrderId)
        │ not found → return (order not yet registered, will match on order creation)
        │ found:
        ▼
   compare transaction.presentmentAmount vs order.expectedAmount
        │
        ├── within tolerance (±100 paisa default) → Order: PAYMENT_RECEIVED, Txn: MATCHED
        │
        ├── transaction > expected (overpaid) →
        │       Order: OVERPAID
        │       ExceptionRecord: ORDER_AMOUNT_MISMATCH (HIGH severity)
        │
        └── transaction < expected (underpaid) →
                Order: UNDERPAID
                ExceptionRecord: ORDER_AMOUNT_MISMATCH (HIGH severity)
```

**Direction 2: Order registered while payment already captured**

```
OrderMatchingService.matchOnOrderCreation(order)
        │
   find Transaction by (merchantId, orderId) with status=CAPTURED
        │ not found → return (no payment yet, will match on capture)
        │ found:
        ▼
   same amount comparison and outcome as Direction 1
```

### Amount tolerance

Configurable via `app.order-matching.amount-tolerance-paisa` (default 100 paisa = ₹1). Applied symmetrically: `|txnAmount - expectedAmount| ≤ tolerance` is considered a match.

### Grace window

`app.order-matching.payment-grace-minutes` (default 30) and `app.order-matching.order-grace-minutes` (default 15) define how long the system waits before treating an unmatched order as stale.

---

## 14. Reconciliation Engine

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

## 15. Reconciliation Rules Deep Dive

### ExactIdMatchRule

**Purpose:** Flag captured payments that have no order reference and cannot be reconciled automatically.

**Candidates:** Transactions with `status=CAPTURED` AND `reconciliationStatus=PENDING_SETTLEMENT` AND `eventOccurredAt < now - 5 minutes`.

**Logic:**
- If the transaction has a non-blank `providerOrderId` → **skip** (already handled by `OrderMatchingService` at ingestion time)
- If `providerOrderId` is missing → create `UNMATCHED_PAYMENT` exception (MEDIUM severity), mark `reconciliationStatus=EXCEPTION`

This rule does **not** promote transactions to `MATCHED` — that happens via `OrderMatchingService` (on capture) or `ProviderReportMatchRule` (on settlement report sync). This rule only catches payments that slipped through without any order reference.

**Why 5-minute delay?** A payment captured right now may still be receiving supplemental data (like order ID) from a second event arriving within seconds.

---

### MissingCaptureRule

**Purpose:** Flag payments stuck in `AUTHORIZED` for too long, indicating a likely auto-expiry or missed capture.

**Candidates:** Transactions with `status=AUTHORIZED` AND `eventOccurredAt < now - 24 hours` (configurable via `app.reconciliation.missing-capture-threshold-hours`).

**On match:** Creates `MISSING_CAPTURE` exception (HIGH severity), sets `reconciliationStatus=EXCEPTION`, and links the exception ID to the transaction.

---

### OrphanRefundRule

**Purpose:** Flag refunds that could not be linked to a parent payment.

**Candidates:** Transactions with `eventType=REFUND` AND `parentTransactionId IS NULL` AND `eventOccurredAt < now - 10 minutes`.

**On match:** Creates `ORPHAN_REFUND` exception (HIGH severity), sets `reconciliationStatus=EXCEPTION`, and links the exception ID to the transaction.

**Why 10-minute grace?** The parent payment and the refund may arrive very close together. The 10-minute window allows the parent to be saved before the rule runs.

---

### DuplicateCaptureRule

**Purpose:** Detect the same order being charged more than once.

**Candidates:** `(merchantId, orderId)` groups where more than one `CAPTURED` payment exists for the same order.

**Logic:** Query groups that have `COUNT > 1`, then for every transaction in that group: create a `DUPLICATE_CAPTURE` exception (CRITICAL severity), set `reconciliationStatus=EXCEPTION`, and link the exception ID.

---

### SettlementTotalRule

**Purpose:** Verify that the sum of net amounts for all transactions in a settlement matches the settlement's declared `netAmount`.

**Candidates:** Settlements with `settlementStatus=SETTLED`.

**Pre-condition — Provider report readiness:** The rule first checks whether the settlement's provider report lines are fully synced and matched. It skips the settlement if:
- No report lines exist yet (report not synced)
- Any report lines are still `PENDING` (ProviderReportMatchRule hasn't run yet)
- Line count is less than the expected `transactionCount`

**Logic (once report is ready):**
```
transactionSum = SUM(netAmount) WHERE settlementId = settlement.providerSettlementId
diff = ABS(settlement.netAmount - transactionSum)

if diff > tolerance (default 100 paisa):
    create SETTLEMENT_DISCREPANCY exception (CRITICAL)
    mark settlementStatus = DISCREPANT

else if bank credit is confirmed (bankCreditAmount and bankCreditDate are set):
    mark settlementStatus = MATCHED_TO_BANK

else:
    log "totals verified, awaiting bank credit confirmation"
```

The tolerance (default 100 paisa = ₹1) is configurable via `app.reconciliation.amount-tolerance-paisa`.

---

### ProviderReportMatchRule

**Purpose:** Verify that every line in a provider's settlement report (`SettlementReportLine`) maps to a known transaction in the system with matching amounts.

**Candidates:** `SettlementReportLine` records with `matchStatus=PENDING`.

**Logic — three outcomes per line:**

```
Look up Transaction by (provider, providerTxnId):

NOT FOUND:
    line.matchStatus = NOT_FOUND_IN_DB
    create PROVIDER_REPORT_MISMATCH exception (HIGH)

FOUND + amounts match (within tolerance):
    line.matchStatus = MATCHED
    line.matchedToTxnId = transaction.id
    transaction.reconciliationStatus = MATCHED
    transaction.settlementId = settlement.providerSettlementId

FOUND + amounts differ:
    line.matchStatus = AMOUNT_MISMATCH
    create PROVIDER_REPORT_MISMATCH exception (HIGH)
    transaction.reconciliationStatus = EXCEPTION
```

This rule is the primary path that promotes transactions to `MATCHED` status — it confirms that the provider's settlement report agrees with our transaction records. It also catches cases where a provider credits an amount for a transaction the platform never received via webhook or polling.

---

### UnmatchedOrderRule

**Purpose:** Detect mismatches between orders and payments in both directions — orders without payments and payments without orders.

**Check 1 — Orders with no payment:**

**Candidates:** Orders with `orderStatus=CREATED` AND `createdAt < now - 30 minutes` (configurable via `app.order-matching.payment-grace-minutes`).

**On match:** Creates `MISSING_PAYMENT` exception (HIGH severity). This alerts ops that a customer's order has no corresponding captured payment — either the payment was missed, the webhook was lost, or the customer abandoned checkout.

**Check 2 — Payments with no pre-registered order:**

**Candidates:** Captured transactions that have a `providerOrderId` but no matching pre-registered `Order` in our system, AND `eventOccurredAt < now - 15 minutes` (configurable via `app.order-matching.order-grace-minutes`).

**On match:** Creates `UNREGISTERED_PAYMENT` exception (MEDIUM severity), sets `reconciliationStatus=EXCEPTION`. This catches payments where the merchant's system processed a charge but never called our order registration API.

---

### ExceptionRecord deduplication

`ExceptionRecordService.createForTransaction()` checks whether an OPEN or IN_REVIEW exception of the same `(type, transactionId)` already exists before creating a new one. This prevents the engine from creating duplicate exceptions on every 5-minute run.

For order-level exceptions (`createForOrderAlert`), deduplication checks for an existing exception matching `(type, merchantId, description containing orderId)`. The `transactionId` is stored as null since no single transaction is involved.

---

## 16. Exception Management

An `ExceptionRecord` represents an anomaly detected by the reconciliation engine or the bank statement matching process.

### Exception types

| Type | Created by | Severity |
|---|---|---|
| `UNMATCHED_PAYMENT` | ExactIdMatchRule (no providerOrderId on captured payment) | MEDIUM |
| `UNMATCHED_REFUND` | Refund matching | MEDIUM |
| `MISSING_CAPTURE` | MissingCaptureRule (AUTHORIZED > 24h) | HIGH |
| `ORPHAN_REFUND` | OrphanRefundRule (refund with no parent payment) | HIGH |
| `DUPLICATE_CAPTURE` | DuplicateCaptureRule (same order charged twice) | CRITICAL |
| `SETTLEMENT_DISCREPANCY` | SettlementTotalRule (amount mismatch), SettlementReconcilerJob (overdue) | CRITICAL / HIGH |
| `PROVIDER_REPORT_MISMATCH` | ProviderReportMatchRule (txn not found or amount differs) | HIGH |
| `ORDER_AMOUNT_MISMATCH` | OrderMatchingService (overpaid or underpaid) | HIGH |
| `AMOUNT_MISMATCH` | General amount discrepancy checks | MEDIUM |
| `MISSING_PAYMENT` | UnmatchedOrderRule (order registered but no payment received) | HIGH |
| `UNREGISTERED_PAYMENT` | UnmatchedOrderRule (payment captured but no order registered) | MEDIUM |
| `STATUS_MISMATCH` | Status inconsistency checks | MEDIUM |
| `FEE_DISCREPANCY` | Fee verification | MEDIUM |
| `BANK_AMOUNT_MISMATCH` | BankStatementMatchingService (UTR/narration matched but amounts differ) | CRITICAL |
| `UNMATCHED_BANK_CREDIT` | BankStatementCatchUpJob (bank credit with no matching settlement) | MEDIUM |
| `OVERDUE_BANK_CREDIT` | BankStatementCatchUpJob, SettlementReconcilerJob (settlement without bank confirmation) | HIGH |

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
- `fromDate`, `toDate` — date range (ISO format, e.g. `2026-05-01`)
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

## 17. Settlement Reconciliation

Settlements represent the bank transfer from Razorpay or Stripe to the merchant. Each settlement groups multiple transactions. A settlement also carries a detailed line-item report (`SettlementReportLine`) from the provider.

### Settlement entity key fields

| Field | Description |
|---|---|
| `providerSettlementId` | Provider's unique ID for this settlement |
| `grossAmount` | Total gross amount |
| `totalFees` | Total platform fees |
| `totalTax` | Tax on fees |
| `netAmount` | Gross minus fees and tax |
| `bankCreditAmount` | Amount actually credited to bank |
| `bankCreditDate` | Date of bank credit |
| `utrNumber` | UTR reference for bank transfer |
| `settlementStatus` | `PENDING → SETTLED → MATCHED_TO_BANK` or `DISCREPANT` or `ON_HOLD` |
| `transactionCount` | Expected number of transactions in this settlement |

### SettlementReportLine entity

Each settlement carries individual line items that correspond to specific transactions:

| Field | Description |
|---|---|
| `settlementId` | FK to parent `Settlement` |
| `providerTxnId` | Provider transaction ID this line represents |
| `entityType` | `payment`, `refund`, or `fee` |
| `grossAmount` | Gross amount for this line |
| `feeAmount` | Fee for this line |
| `netAmount` | Net for this line |
| `matchStatus` | `PENDING → MATCHED / AMOUNT_MISMATCH / NOT_FOUND_IN_DB` |
| `matchedToTxnId` | FK to matched `Transaction` (set by ProviderReportMatchRule) |

### Settlement status flow

```
PENDING  ──►  SETTLED  ──►  MATCHED_TO_BANK  (SettlementReconcilerJob closes cleanly)
                  │
                  ├──►  DISCREPANT           (SettlementTotalRule: amount mismatch)
                  │
                  └──►  ON_HOLD              (manual hold)
```

### SettlementService.saveAndMatch()

When a settlement is saved, `SettlementService.saveAndMatch()` immediately attempts retroactive bank statement matching for the new settlement. This covers the case where a bank statement entry arrived before the settlement was registered.

### SettlementReconcilerJob (daily at 2 AM)

This job runs independently of the main ReconciliationEngine and handles two responsibilities:

**Phase 1 — Close clean settlements:**
- Find all settlements with `status = SETTLED`
- For each, check if any `OPEN` or `IN_REVIEW` exceptions reference it
- If no open exceptions → update to `MATCHED_TO_BANK`
- If open exceptions exist → leave as `SETTLED`, log warning

**Phase 2 — Flag overdue pending settlements:**
- Find settlements with `status = PENDING` AND `createdAt < now - 7 days` (configurable via `app.reconciliation.settlement-overdue-days`)
- For each not already flagged, create a `SETTLEMENT_DISCREPANCY` exception (HIGH severity) describing how many days it has been pending
- Prevents long-pending settlements from going unnoticed

The job can also be triggered manually via `POST /api/v1/admin/settlement-reconciler/run`.

### SettlementReportSyncJob (every 2 hours)

Syncs provider settlement report lines for all `SETTLED` or `DISCREPANT` Razorpay settlements. For each settlement, fetches payment details by settlement ID from Razorpay and creates `SettlementReportLine` records. These are then verified by `ProviderReportMatchRule`.

---

## 18. Bank Statement Matching

Bank statement entries represent individual credit/debit lines uploaded from the merchant's bank portal. The platform matches these entries to provider settlements using a three-pass strategy. This closes the full reconciliation loop: webhook → transaction → settlement → bank.

### Upload Flow

The platform provides two upload endpoints:

| Endpoint | Controller | Purpose |
|---|---|---|
| `POST /api/v1/bank-statements/upload` | BankStatementController | Direct CSV upload + immediate matching |
| `POST /api/v1/reconciliation/bank-statements/upload` | ReconciliationBankStatementController | Upload with tracking (status, progress, re-reconcile) |

The reconciliation controller also provides:
- `GET /api/v1/reconciliation/bank-statements` — List recent uploads with status
- `GET /api/v1/reconciliation/bank-statements/{uploadId}` — Get upload status and match counts
- `POST /api/v1/reconciliation/bank-statements/{uploadId}/reconcile` — Re-trigger matching for an upload

### CSV/TSV Parsing — Multi-Bank Support

`BankStatementIngestionService` parses bank statement files with **smart header detection** that works across all major Indian banks without any per-bank configuration.

**How it works:**

1. **Auto-detect delimiter** — Tabs (TSV) or commas (CSV) are detected per-line
2. **Smart header detection** — Scans lines for the one containing both a date keyword and an amount keyword (e.g., `Date` + `Credit`). All lines before it are skipped as metadata (account holder info, balances, IFSC codes, etc.)
3. **Footer detection** — After 3 consecutive parse failures following valid data rows, parsing stops (skips statement summaries and disclaimers)
4. **Flexible column mapping** — Recognizes multiple header name variants per field
5. **UTR extraction from narration** — When no dedicated UTR column exists (e.g., ICICI), extracts UTR from narration text using pattern matching (e.g., `NEFT/UTR20260501001/RAZORPAY`)

**Supported date formats:** `dd/MM/yyyy`, `dd-MM-yyyy`, `yyyy-MM-dd`, `dd-MMM-yyyy`, `MM/dd/yyyy`

**Supported banks and column mapping:**

| Field | HDFC | ICICI | SBI | Axis | Internal TSV |
|---|---|---|---|---|---|
| Date | `Date`, `Value Date` | `Transaction Date` | `Txn Date` | `Tran Date` | `entryDate` |
| Amount | `Deposit Amt` / `Withdrawal Amt` | `Credit` / `Debit` | `Credit` / `Debit` | `Credit` / `Debit` | `amount` + `creditDebit` |
| Narration | `Narration` | `Description` | `Details` | `Particulars` | `narration` |
| UTR/Ref | `Chq/Ref No` | from narration | `Ref No/Cheque No` | `Chq No` | `utrNumber` |

**Example: Real SBI statement from YONO app**

```
Mr. SHREYA MISHRA                          ← metadata (skipped)
State Bank of India                        ← metadata (skipped)
Account Number : XXXXXXXXX                 ← metadata (skipped)
Statement From : 01-05-2026 to 29-05-2026  ← metadata (skipped)
Date,Details,Ref No/Cheque No,Debit,Credit,Balance    ← header (detected)
01/05/2026,NEFT RAZORPAY SETTLEMENT,UTR20260501001,,488000.00,1500000  ← data
...
Statement Summary : 01-05-2026 To 29-05-2026   ← footer (stopped)
Please do not share your ATM PIN...             ← footer (stopped)
```

### BankStatementEntry entity

| Field | Description |
|---|---|
| `merchantId` | Owner merchant |
| `uploadBatchId` | Groups entries from a single upload |
| `entryDate` | Date of the bank transaction |
| `amount` | Amount in smallest currency unit (paisa) |
| `currency` | Currency code |
| `creditDebit` | `CR` (credit) or `DR` (debit) |
| `utrNumber` | UTR reference (from column or extracted from narration) |
| `bankReference` | Bank's internal reference |
| `narration` | Free-text description from the bank |
| `providerHint` | Provider inferred from narration (e.g. `"razorpay"`) |
| `matchStatus` | `PENDING → MATCHED / UNMATCHED / IGNORED` |
| `matchedBy` | Matching strategy used: `UTR`, `AMOUNT_DATE`, or `NARRATION` |
| `matchedSettlementId` | FK to matched `Settlement` |

### BankStatementUpload entity (upload tracking)

| Field | Description |
|---|---|
| `uploadId` | Unique upload identifier (e.g. `bsu_e5ce45f92a78`) |
| `merchantId` | Owner merchant |
| `fileName` | Original uploaded file name |
| `status` | `ACCEPTED → PROCESSING → COMPLETED / FAILED` |
| `rowsParsed` | Total rows successfully parsed |
| `matchedRows` | Rows matched to settlements |
| `exceptionRows` | Rows with parse errors or mismatches |
| `progress` | Processing progress (0–100) |
| `message` | Status message |
| `uploadedAt` | Upload timestamp |

On successful upload, the merchant's `lastBankStatementUploadAt` field is updated.

### UTR — The Key Matching Identifier

UTR (Unique Transaction Reference) is assigned by RBI for every NEFT/IMPS transfer. It arrives from **both sides**:

```
Razorpay webhook → settlement.entity.utr → stored as Settlement.utrNumber
Bank statement   → Ref No/Cheque No column (or extracted from narration)
                   → stored as BankStatementEntry.utrNumber
```

Razorpay knows the UTR because they initiate the bank transfer. The bank records the same UTR when it receives the transfer. Matching on UTR is 100% conclusive.

### Three-pass matching strategy

`BankStatementMatchingService` attempts three passes in order, stopping as soon as a match is found. All `CR` (credit) entries are considered for matching — entries that don't match any settlement stay `PENDING` for the catch-up job to retry.

#### Pass 1 — UTR Match (Confidence: 100%)

```
if entry.utrNumber is not blank:
    find Settlement where utrNumber = entry.utrNumber AND status = SETTLED
    found → check amount: |settlement.netAmount - entry.amount| > tolerance?
               yes → mark UNMATCHED, create BANK_AMOUNT_MISMATCH exception (CRITICAL)
               no  → mark MATCHED, matchedBy = UTR
                      update settlement to MATCHED_TO_BANK, set bankCreditAmount/Date
```

#### Pass 2 — Amount + Date Match (Confidence: ~85%)

```
if entry is still PENDING:
    find Settlements where:
        status = SETTLED
        netAmount within ±500 paisa of entry.amount
        bankCreditDate within ±1 day of entry.entryDate
        (falls back to settledAt date when bankCreditDate is null)
    filter by provider matching narration (if available)
    first match → mark MATCHED, matchedBy = AMOUNT_DATE
```

The `settledAt` fallback is critical because `bankCreditDate` is only populated after a match — without the fallback, fresh settlements would be invisible to Pass 2.

#### Pass 3 — Narration Parse (Confidence: ~70%)

```
if entry is still PENDING:
    search for any Settlement whose providerSettlementId appears in the narration
    e.g., narration "NEFT Razorpay settlement setl_001" contains "setl_001"
    found → mark MATCHED, matchedBy = NARRATION
```

### Timing — Who Arrives First?

The system handles all timing combinations:

| Scenario | What happens |
|---|---|
| Settlement first, bank statement later | `matchEntry()` at upload time matches immediately |
| Bank statement first, settlement later | `tryMatchBySettlement()` retroactively matches PENDING entries when settlement is saved |
| Both arrive, UTR not yet available | Pass 2/3 matches by amount+date or narration |
| Neither matches immediately | Entry stays `PENDING`, catch-up job retries daily |

### Entry points

- **`matchEntry(entry)`** — Called immediately when a bank statement row is uploaded
- **`tryMatchBySettlement(settlement)`** — Called retroactively when a settlement is saved (covers entries that arrived before the settlement)
- **`rematchPending()`** / **`rematchPending(merchantId, uploadBatchId)`** — Called by `BankStatementCatchUpJob` or manually via the reconcile endpoint

### Unmatched entries and overdue detection

The `BankStatementCatchUpJob` (daily at 9 AM) handles two overdue scenarios:

1. **Unmatched bank credits:** CR entries still `PENDING` after 48 hours (`app.bank-matching.unmatched-entry-grace-hours`) are marked `UNMATCHED` and an `UNMATCHED_BANK_CREDIT` exception (MEDIUM severity) is created.

2. **Settlements without bank confirmation:** Settlements in `SETTLED` status for over 7 days (`app.bank-matching.overdue-settlement-days`) without a corresponding bank statement match trigger an `OVERDUE_BANK_CREDIT` exception (HIGH severity).

---

## 19. Gap Filler — Polling for Missed Events

Webhooks can be missed due to network issues, provider outages, or misconfigured endpoints. The Gap Filler job compensates by periodically polling the provider APIs for recent events.

### GapFillerJob (every 15 minutes)

```
Compute window:
    from = now - 30 minutes  (configurable: app.polling.gap-filler-lookback-minutes)
    to   = now

Razorpay (per-merchant):
    for each active ProviderConnection where provider = "razorpay":
        decrypt credentials
        fetchPayments(from, to)  ──► paginated Razorpay API call (page size 100)
        fetchRefunds(from, to)   ──► paginated Razorpay API call
        each result wrapped in synthetic webhook envelope
            → ingestAsync(payload, "razorpay", "polling")

Stripe (global):
    fetchCharges(from, to)   ──► cursor-paginated Stripe API call (limit 100)
    fetchRefunds(from, to)   ──► cursor-paginated Stripe API call
    each result wrapped in synthetic webhook envelope
        → ingestAsync(payload, "stripe", "polling")
```

### Per-merchant polling

For Razorpay, the gap filler iterates all active `ProviderConnection` records, decrypts each merchant's API credentials, and polls using those credentials. This ensures each merchant's events are fetched with the correct API keys.

### Synthetic envelope format

The polling services wrap raw API responses in a structure that matches the webhook payload format, so `WebhookIngestionService` and `NormalizationService` work identically for both sources.

**Razorpay example:**
```json
{
  "id": "poll_payments_0_0",
  "event": "payment.captured",
  "payload": {
    "payment": {
      "entity": { "...razorpay payment object..." }
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
    "object": { "...stripe charge object..." }
  }
}
```

Deduplication in `WebhookIngestionService` (unique constraint on `provider + providerEventId`) ensures that events already received via webhook are silently ignored when they appear again via polling.

Metrics: `polling.gaps.filled` counter tracks how many events were picked up by polling.

---

## 20. Admin Operations

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
  "to": "2024-01-15T23:59:59Z",
  "merchantId": "merchant_001"
}
```

Use case: A specific time window had a webhook outage. Trigger a targeted backfill for that window without waiting for the scheduled gap-filler.

**What happens:**
1. For Razorpay: looks up the merchant's stored `ProviderConnection` credentials, decrypts them, and calls `RazorpayPollingService.fetchPayments(from, to)` + `fetchRefunds(from, to)`
2. For Stripe: calls `StripePollingService.fetchCharges(from, to)` + `fetchRefunds(from, to)` (uses global credentials)
3. For each fetched payload, call `WebhookIngestionService.ingestAsync(payload, provider, "admin-poll")`
4. Log audit entry: `action=admin_poll_triggered` with `fetched` count
5. Return `{"status": "accepted", "provider": "...", "merchantId": "...", "fetched": N, ...}`

### Manual settlement reconciliation

```
POST /api/v1/admin/settlement-reconciler/run
X-Actor: ops-engineer
```

Triggers `SettlementReconcilerJob.run()` on demand. Returns a summary with `closedAsMatched`, `remainedDiscrepant`, and `overdueSettlementsFlagged` counts.

### Batch transaction reconciliation

```
POST /api/v1/admin/reconcile-transactions
X-Actor: ops-engineer
{
  "transactionIds": [1, 2, 3],
  "mode": "MARK_MATCHED"
}
```

Allows manual batch updates of transaction reconciliation status. Modes: `MARK_MATCHED`, `MARK_EXCEPTION`.

### Audit log search

```
GET /api/v1/admin/audit-logs?entityType=webhook_event&entityId=1234
GET /api/v1/admin/audit-logs?actor=ops-engineer
```

### Payment flow event search

```
GET /api/v1/admin/payment-flow-events?providerTransactionId=pay_ABC&limit=50
GET /api/v1/admin/payment-flow-events?webhookEventId=1234
GET /api/v1/admin/payment-flow-events?userId=42
```

Traces the lifecycle of a transaction through every processing stage for debugging.

---

## 21. Dashboard and Metrics

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

Returns 30-day aggregate metrics:

| Key | Description |
|---|---|
| `transactionsProcessed` | Transaction count in last 30 days |
| `openExceptions` | Open + in-review exception count |
| `matchRate` | 30-day match rate |
| `webhookQueueDepth` | Currently 0 (placeholder for future queue depth metric) |
| `status` | Always `"ok"` |

### Activity endpoint

```
GET /api/v1/dashboard/activity?limit=8
```

Returns a merged, time-sorted activity feed combining recent transactions and exceptions. Each item includes `time`, `text` (summary), `subtext` (detail), and `type` (transaction or exception).

### Trends endpoint

```
GET /api/v1/dashboard/trends?days=7
```

Returns daily time-series data:

| Key | Description |
|---|---|
| `date` | Calendar date (ISO format) |
| `matched` | Count of matched transactions that day |
| `exceptions` | Count of exceptions created that day |
| `transactions` | Total transaction count that day |

### Prometheus metrics (Micrometer)

Available at `GET /actuator/prometheus`:

| Metric | Type | Description |
|---|---|---|
| `reconciliation.exceptions.created` | Counter | Total exceptions created by the engine |
| `reconciliation.run.duration` | Timer | Time taken for a full engine run |
| `polling.gaps.filled` | Counter | Events fetched by gap-filler polling |

---

## 22. Audit Logging

`AuditService.log()` creates an immutable `AuditLog` record for every sensitive operation.

### What gets audited

| Action | Triggered by |
|---|---|
| `webhook_replayed` | AdminService.replay() |
| `admin_poll_triggered` | AdminService.poll() |
| `settlement_reconciler_run` | SettlementReconcilerJob.run() |
| `exception_status_updated` | ExceptionQueryService.update() |
| `RESOLVE_EXCEPTION` | ExceptionController |

### AuditLog fields

| Field | Description |
|---|---|
| `actor` | Who performed the action (from `X-Actor` header or `"scheduler"`) |
| `action` | Name of the action |
| `entityType` | Type of entity affected (`webhook_event`, `exception_record`, etc.) |
| `entityId` | Primary key of the affected entity |
| `oldValue` | State before the change (JSONB) |
| `newValue` | State after the change (JSONB) |
| `ipAddress` | Client IP (stored as PostgreSQL `inet` type) |
| `createdAt` | Timestamp of the audit entry |

---

## 23. Scheduled Jobs Summary

All jobs use [db-scheduler](https://github.com/kagkarlsson/db-scheduler), a persistent distributed scheduler backed by a PostgreSQL table (`scheduled_tasks`). This ensures that in a multi-instance deployment, each job runs exactly once.

| Job | Schedule | Purpose |
|---|---|---|
| `ReconciliationJob` | Every 5 minutes | Runs all reconciliation rules |
| `GapFillerJob` | Every 15 minutes | Polls Razorpay (per-merchant) + Stripe for missed events |
| `SettlementReconcilerJob` | Daily at 2 AM | Closes settled settlements; flags overdue pending ones |
| `SettlementReportSyncJob` | Every 2 hours | Syncs provider settlement report lines into `settlement_report_lines` |
| `BankStatementCatchUpJob` | Daily at 9 AM | Retries matching for `PENDING` bank entries; flags overdue entries and settlements |

---

## 24. Data Model

Flyway manages 19 versioned migrations (V1–V19). All primary keys are `bigint GENERATED ALWAYS AS IDENTITY`. All timestamps are `timestamptz` stored in UTC.

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
| `merchant_id` | varchar(60) | Resolved from webhook signature |

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
| `event_type` | enum | `PAYMENT`, `REFUND`, `CHARGEBACK`, `ADJUSTMENT` |
| `status` | enum | `AUTHORIZED`, `CAPTURED`, `FAILED`, `REFUNDED`, `PARTIALLY_REFUNDED`, `DISPUTED`, `CANCELLED`, `PENDING`, `PENDING_SETTLEMENT` |
| `reconciliation_status` | enum | `PENDING`, `PENDING_SETTLEMENT`, `MATCHED`, `EXCEPTION`, `MANUALLY_RESOLVED`, `IGNORED` |
| `presentment_amount` | bigint | In smallest currency unit |
| `presentment_currency` | char(3) | `INR`, `USD`, etc. |
| `settlement_amount` | bigint | Amount in settlement currency |
| `settlement_currency` | char(3) | Settlement currency |
| `fee_amount` | bigint | Platform fee |
| `tax_amount` | bigint | GST or tax on fee |
| `net_amount` | bigint | `presentment - fee - tax` |
| `settlement_id` | varchar(120) | Provider's settlement ID |
| `user_id` | bigint FK | Resolved payer identity |
| `exception_id` | bigint FK | Linked exception record |
| `payer_email` | varchar | |
| `payer_phone` | varchar | |
| `payment_method` | varchar | `card`, `upi`, `netbanking` |
| `card` | varchar | Card last4 and network |
| `bank` | varchar | Bank code for netbanking |
| `vpa` | varchar | UPI VPA |
| `event_occurred_at` | timestamptz | Provider-reported event time |
| `captured_at` | timestamptz | When payment was captured |
| `refunded_at` | timestamptz | When refund was processed |
| `ingested_at` | timestamptz | When we first received this |
| `matched_at` | timestamptz | When reconciliation succeeded |
| `raw_payload` | jsonb | Original normalized payload |
| `notes` | jsonb | Merged notes map from all events |

### Table: `orders`

| Column | Type | Notes |
|---|---|---|
| `id` | bigint PK | |
| `merchant_id` | varchar(60) | |
| `order_id` | varchar(120) | Merchant's order ref, UNIQUE with merchant |
| `provider_order_id` | varchar(120) | Provider's order ID |
| `expected_amount` | bigint | Amount merchant expects to receive |
| `currency` | char(3) | |
| `order_status` | enum | `CREATED`, `PAYMENT_RECEIVED`, `OVERPAID`, `UNDERPAID`, `CANCELLED`, `REFUNDED` |
| `transaction_id` | bigint FK | Matched transaction (nullable) |
| `amount_matched` | boolean | Whether amounts matched within tolerance |
| `discrepancy_amount` | bigint | `actual - expected` amount |
| `metadata` | jsonb | Arbitrary merchant metadata |
| `matched_at` | timestamptz | When match was found |
| `created_at` | timestamptz | |
| `updated_at` | timestamptz | |

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
| `distinct_payment_methods` | jsonb | Set of methods used |
| `risk_score` | int | Computed risk score |
| `risk_flags` | jsonb | Risk flag details |

### Table: `merchants`

| Column | Type | Notes |
|---|---|---|
| `id` | bigint PK | |
| `merchant_id` | varchar(60) | UNIQUE |
| `name` | varchar(120) | Display name |
| `email` | varchar(254) | UNIQUE |
| `api_key_hash` | varchar(256) | BCrypt hash of API key |
| `password_hash` | varchar(256) | BCrypt hash of password (optional) |
| `webhook_secret` | varchar(256) | Per-merchant webhook signing secret |
| `status` | varchar(20) | Merchant account status (default `ACTIVE`) |
| `last_bank_statement_upload_at` | timestamptz | Updated on each successful bank statement upload |
| `created_at` | timestamptz | |
| `updated_at` | timestamptz | |

### Table: `provider_connections`

| Column | Type | Notes |
|---|---|---|
| `id` | bigint PK | |
| `merchant_id` | varchar(60) | UNIQUE with provider |
| `provider` | varchar(30) | `razorpay` or `stripe` |
| `api_key_encrypted` | text | AES-encrypted API key |
| `secret_encrypted` | text | AES-encrypted API secret |
| `api_key_masked` | varchar | First 4 + `****` + last 4 (for display) |
| `status` | enum | `ACTIVE` or `DISABLED` |
| `created_at` | timestamptz | |
| `updated_at` | timestamptz | |

### Table: `settlements`

| Column | Type | Notes |
|---|---|---|
| `id` | bigint PK | |
| `provider` | varchar(30) | |
| `provider_settlement_id` | varchar(120) | UNIQUE with provider |
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

### Table: `settlement_report_lines`

| Column | Type | Notes |
|---|---|---|
| `id` | bigint PK | |
| `settlement_id` | bigint FK | Parent settlement |
| `provider` | varchar(30) | |
| `provider_txn_id` | varchar(120) | Provider transaction ID this line represents |
| `entity_type` | varchar(30) | `payment`, `refund`, `fee` |
| `gross_amount` | bigint | |
| `fee_amount` | bigint | |
| `net_amount` | bigint | |
| `currency` | char(3) | |
| `match_status` | enum | `PENDING`, `MATCHED`, `AMOUNT_MISMATCH`, `NOT_FOUND_IN_DB` |
| `matched_to_txn_id` | bigint FK | Matched transaction (nullable) |

### Table: `bank_statement_uploads`

| Column | Type | Notes |
|---|---|---|
| `id` | bigint PK | |
| `upload_id` | varchar(60) | UNIQUE, e.g. `bsu_e5ce45f92a78` |
| `merchant_id` | varchar(60) | |
| `file_name` | varchar(255) | Original uploaded file name |
| `status` | varchar(20) | `ACCEPTED`, `PROCESSING`, `COMPLETED`, `FAILED` |
| `rows_parsed` | integer | Total rows parsed |
| `matched_rows` | integer | Rows matched to settlements |
| `exception_rows` | integer | Parse errors or mismatches |
| `progress` | integer | 0–100 |
| `message` | varchar(255) | Status message |
| `uploaded_at` | timestamptz | |
| `updated_at` | timestamptz | |

### Table: `bank_statement_entries`

| Column | Type | Notes |
|---|---|---|
| `id` | bigint PK | |
| `merchant_id` | varchar(60) | |
| `upload_batch_id` | varchar(60) | Groups entries from one upload |
| `entry_date` | date | |
| `amount` | bigint | In smallest currency unit (paisa) |
| `currency` | char(3) | |
| `credit_debit` | varchar(2) | `CR` or `DR` |
| `utr_number` | varchar(80) | Bank UTR (from column or extracted from narration) |
| `bank_reference` | varchar(120) | Bank's internal reference |
| `narration` | text | Free-text description |
| `provider_hint` | varchar(30) | Provider inferred from narration |
| `match_status` | enum | `PENDING`, `MATCHED`, `UNMATCHED`, `IGNORED` |
| `matched_by` | varchar(20) | `UTR`, `AMOUNT_DATE`, or `NARRATION` |
| `matched_settlement_id` | bigint FK | Matched settlement (nullable) |
| `created_at` | timestamptz | |

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

### Table: `payment_flow_events`

| Column | Type | Notes |
|---|---|---|
| `id` | bigint PK | |
| `provider_event_id` | varchar | Source event ID |
| `provider` | varchar(30) | |
| `stage` | varchar | `INGEST_RECEIVED`, `INGEST_DUPLICATE`, `INGEST_STORED`, etc. |
| `detail` | text | Stage-specific detail |
| `created_at` | timestamptz | |

---

## 25. API Reference

### Webhook endpoints (public — no JWT required)

| Method | Path | Header | Description |
|---|---|---|---|
| POST | `/webhooks/razorpay` | `X-Razorpay-Signature` | Receive Razorpay event |
| POST | `/webhooks/stripe` | `Stripe-Signature` | Receive Stripe event |

Both return `200 "received"` on success, `400 "Invalid signature"` on failure.

### Merchant Self-Service (public registration + login; JWT for others)

| Method | Path | Auth | Description |
|---|---|---|---|
| POST | `/api/v1/merchants/register` | None | Register merchant, returns API key (one-time) |
| POST | `/api/v1/merchants/login` | None | Email + password login, returns JWT |
| POST | `/api/v1/merchants/auth` | None | API key auth, returns JWT |
| POST | `/api/v1/merchants/reset-key` | None | Reset API key, returns new key (one-time) |
| POST | `/api/v1/merchants/refresh` | JWT | Refresh JWT token |
| POST | `/api/v1/merchants/set-password` | JWT | Set or update password |
| GET | `/api/v1/merchants/me` | JWT | Get merchant profile |

### Provider Connections

| Method | Path | Auth | Description |
|---|---|---|---|
| GET | `/api/v1/connections` | JWT | List connections (masked credentials) |
| POST | `/api/v1/connections` | JWT | Save/update provider connection |

### Dashboard

| Method | Path | Params | Description |
|---|---|---|---|
| GET | `/api/v1/dashboard/summary` | `days` (default 7) | Transaction and exception summary |
| GET | `/api/v1/dashboard/metrics` | — | 30-day aggregate metrics |
| GET | `/api/v1/dashboard/activity` | `limit` (default 8) | Merged activity feed |
| GET | `/api/v1/dashboard/trends` | `days` (default 7) | Daily time-series data |

### Exceptions

| Method | Path | Params / Body | Description |
|---|---|---|---|
| GET | `/api/v1/exceptions` | `fromDate`, `toDate`, `status`, `provider`, `type`, `page`, `limit` | List exceptions |
| GET | `/api/v1/exceptions/{id}` | — | Exception detail + linked transaction + audit trail |
| PATCH | `/api/v1/exceptions/{id}` | `{status, notes}` + `X-Actor` header | Update exception status |

### Transactions

| Method | Path | Params | Description |
|---|---|---|---|
| GET | `/api/v1/transactions` | `provider`, `status`, `orderId`, `from`, `to`, `page`, `limit` | List transactions |
| GET | `/api/v1/transactions/{id}` | — | Transaction detail |

### Orders

| Method | Path | Params / Body | Description |
|---|---|---|---|
| POST | `/api/v1/orders` | `{merchantId, orderId, providerOrderId, expectedAmount, currency, metadata}` | Register an expected order |
| GET | `/api/v1/orders` | `merchantId`, `status`, `page`, `limit` | List orders for a merchant |
| GET | `/api/v1/orders/{id}` | — | Order detail with matched transaction |

### Settlements

| Method | Path | Params | Description |
|---|---|---|---|
| GET | `/api/v1/settlements` | `provider`, `status`, `dateFrom`, `dateTo`, `page`, `limit` | List settlements |
| GET | `/api/v1/settlements/{id}` | — | Settlement detail with linked transaction count |
| GET | `/api/v1/settlements/{id}/transactions` | — | All transactions in a settlement |

### Bank Statements

| Method | Path | Params / Body | Description |
|---|---|---|---|
| POST | `/api/v1/bank-statements/upload` | Multipart: `file` (CSV/TSV), `currency` (default INR) | Upload bank statement; triggers immediate matching |
| GET | `/api/v1/bank-statements` | `status`, `page`, `limit` | List bank statement entries with summary |
| POST | `/api/v1/reconciliation/bank-statements/upload` | Multipart: `statement`, `source`, `provider` | Upload with tracking (status, progress) |
| GET | `/api/v1/reconciliation/bank-statements` | `limit` | List recent uploads |
| GET | `/api/v1/reconciliation/bank-statements/{uploadId}` | — | Get upload status and match counts |
| POST | `/api/v1/reconciliation/bank-statements/{uploadId}/reconcile` | — | Re-trigger matching for an upload |

### Admin (requires JWT + `X-Actor` header)

| Method | Path | Body / Params | Description |
|---|---|---|---|
| POST | `/api/v1/admin/replay` | `{webhookEventId}` | Replay a failed webhook event |
| POST | `/api/v1/admin/poll` | `{provider, from, to, merchantId}` | Backfill events for a time window |
| POST | `/api/v1/admin/settlement-reconciler/run` | — | Manually trigger settlement reconciler |
| POST | `/api/v1/admin/reconcile-transactions` | `{transactionIds, mode}` | Batch reconcile transactions |
| GET | `/api/v1/admin/audit-logs` | `entityType`, `entityId`, `actor` | Search audit logs |
| GET | `/api/v1/admin/payment-flow-events` | `providerTransactionId`, `webhookEventId`, `userId`, `limit` | Trace payment lifecycle |

---

## 26. Configuration Reference

All configuration is in `src/main/resources/application.yml`. Secrets come from environment variables.

```yaml
spring:
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5433/reconciliation_dev}
    username: ${DB_USERNAME:recon_user}
    password: ${DB_PASSWORD:recon_pass}
    hikari:
      maximum-pool-size: 10
      minimum-idle: 2
  jpa:
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        jdbc:
          time_zone: UTC
  flyway:
    enabled: true
    locations: classpath:db/migration

server:
  port: ${PORT:8080}

db-scheduler:
  enabled: true
  threads: 5
  polling-interval: 10s
  heartbeat-interval: 5m
  table-name: scheduled_tasks

app:
  merchant:
    id: ${MERCHANT_ID:merchant_001}

  razorpay:
    key-id: ${RAZORPAY_KEY_ID}
    key-secret: ${RAZORPAY_KEY_SECRET}
    webhook-secret: ${RAZORPAY_WEBHOOK_SECRET}

  stripe:
    secret-key: ${STRIPE_SECRET_KEY}
    webhook-secret: ${STRIPE_WEBHOOK_SECRET}

  jwt:
    secret: ${JWT_SECRET}
    expiry-hours: 24

  reconciliation:
    run-interval-minutes: 5
    missing-capture-threshold-hours: 24
    amount-tolerance-paisa: 100
    settlement-overdue-days: 7

  order-matching:
    amount-tolerance-paisa: 100
    payment-grace-minutes: 30
    order-grace-minutes: 15

  bank-matching:
    amount-tolerance-paisa: 500
    unmatched-entry-grace-hours: 48
    overdue-settlement-days: 7

  polling:
    gap-filler-interval-minutes: 15
    gap-filler-lookback-minutes: 30
    settlement-reconciler-cron: "0 0 2 * * *"

  encryption:
    key: ${APP_ENCRYPTION_KEY}
```

### Environment variables required in production

| Variable | Purpose |
|---|---|
| `DB_URL` | PostgreSQL JDBC URL |
| `DB_USERNAME` | Database user |
| `DB_PASSWORD` | Database password |
| `RAZORPAY_KEY_ID` | Default Razorpay API key (for polling without per-merchant creds) |
| `RAZORPAY_KEY_SECRET` | Default Razorpay API secret |
| `RAZORPAY_WEBHOOK_SECRET` | Default webhook HMAC secret (fallback when no merchant match) |
| `STRIPE_SECRET_KEY` | Stripe API key (for polling) |
| `STRIPE_WEBHOOK_SECRET` | Webhook HMAC secret |
| `JWT_SECRET` | JWT signing key (must be ≥32 chars for HS256) |
| `APP_ENCRYPTION_KEY` | AES encryption key (for ProviderConnection credentials) |
| `MERCHANT_ID` | Default merchant identifier |
| `PORT` | Server port (default 8080) |
| `SPRING_PROFILES_ACTIVE` | `local` or `prod` |

### Docker

**Build and run:**
```bash
docker build -t reconciliation:latest .
docker run -p 8080:8080 \
  -e DB_URL=jdbc:postgresql://host:5433/reconciliation_dev \
  -e JWT_SECRET=your-secret \
  reconciliation:latest
```

**Docker Compose (local development):**
```bash
docker compose up -d
```
Starts PostgreSQL 16 on port 5433 and PgAdmin on port 5050.

---

## 27. Testing Strategy

### Test philosophy

All existing tests are unit tests using Mockito mocks. There is no in-memory database dependency — tests wire real service objects with mocked repositories and external clients. This keeps tests fast and focused on business logic.

### Test files and what they cover

| Test file | Coverage |
|---|---|
| `WebhookIngestionServiceTest` | Save new event + queue processing; ignore duplicate via unique violation; PaymentFlowEvent stages recorded |
| `WebhookControllerTest` | Signature validation pass/fail; controller returns correct HTTP status |
| `RazorpaySignatureServiceTest` | HMAC-SHA256 verification logic |
| `StripeSignatureServiceTest` | Stripe signature verification |
| `TransactionProcessingServiceTest` | User resolution; refund linking; order matching triggered on CAPTURED; error path sets processingError |
| `NormalizationServiceTest` | Razorpay and Stripe payloads produce correct canonical Transaction fields |
| `TransactionServiceTest` | Upsert insert path; upsert merge path (newer wins); upsert skip path (older loses); same-time non-advancing status ignored |
| `OrderMatchingServiceTest` | Match on capture — exact, overpaid, underpaid; match on order creation; amount tolerance boundaries |
| `OrderControllerTest` | Order CRUD endpoint tests |
| `TransactionControllerTest` | Transaction query endpoint tests |
| `BankStatementIngestionServiceTest` | Standard CSV split columns; single amount+CR/DR column; Indian amount format; SBI-style metadata header with footer; TSV internal format; provider detection; malformed line handling; empty file; batch ID assignment |
| `BankStatementMatchingServiceTest` | Pass 1 UTR match; Pass 2 amount+date match; Pass 3 narration parse; amount mismatch on UTR match creates exception; non-gateway credits stay PENDING |
| `ReconciliationEngineTest` | One rule failing does not block subsequent rules |
| `RuleBehaviorTest` | All 7 rules: candidate detection + exception creation + deduplication |
| `ExceptionRecordServiceTest` | Deduplication: does not create a second exception for same (type, txnId); synthetic key dedup for order exceptions |
| `AdminServicePollTest` | Razorpay poll fetches payments+refunds; Stripe poll fetches charges+refunds; unknown provider throws; audit is called |
| `AdminPollToIngestionIntegrationTest` | Full chain: AdminService → real WebhookIngestionService → repo + processingService. Verifies event saved with correct source and provider. Tests deduplication in the chain. |
| `SettlementReconcilerJobTest` | Phase 1: MATCHED_TO_BANK when no exceptions; stays SETTLED when exceptions open. Phase 2: creates exception for overdue; skips already-flagged. Mixed batch; audit always called. |
| `ExceptionQueryServiceTest` | list() window filter; status filter; type filter; pagination; summary counts. detail() with and without linked transaction. update() for each terminal status (RESOLVED, IGNORED sets resolvedAt). |
| `FullFlowMockIntegrationTest` | End-to-end payment flow integration |
| `MerchantRazorpayBankStatementFlowIntegrationTest` | Full merchant → Razorpay webhook → bank statement matching flow |

### Running tests

```bash
./gradlew test
```

### Key patterns used in tests

**Constructing services directly:**
```java
// Services are instantiated with mocked dependencies — no Spring context needed
service = new WebhookIngestionService(
    mock(WebhookEventRepository.class),
    mock(TransactionProcessingService.class),
    mock(PaymentFlowEventService.class),
    new ObjectMapper()
);
```

**Integration test pattern (`AdminPollToIngestionIntegrationTest`):**
Real service objects are wired together, with only infrastructure mocked (repositories, external API clients). This tests that the services communicate correctly without needing a database.

**ArgumentCaptor for verifying what was saved:**
```java
ArgumentCaptor<WebhookEvent> captor = ArgumentCaptor.forClass(WebhookEvent.class);
verify(repository).save(captor.capture());
assertThat(captor.getValue().getSource()).isEqualTo("admin-poll");
```
