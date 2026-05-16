# Payment Reconciliation Platform — MVP Design Document
**Version 2.0 | Scope: Razorpay + Stripe | April 2026**

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [What We Are Building (And What We Are Not)](#2-what-we-are-building)
3. [Provider Integration — Razorpay & Stripe](#3-provider-integration)
4. [The Golden Rule: Webhooks + Polling Fallback](#4-the-golden-rule)
5. [Universal Data Schema](#5-universal-data-schema)
6. [System Architecture](#6-system-architecture)
7. [Webhook Ingestion — Implementation Detail](#7-webhook-ingestion)
8. [Polling Fallback — Implementation Detail](#8-polling-fallback)
9. [Settlements as a First-Class Entity](#9-settlements)
10. [Reconciliation Engine](#10-reconciliation-engine)
11. [User-Centric Schema for Future Fraud Detection](#11-user-centric-schema)
12. [API Design](#12-api-design)
13. [Frontend / Dashboard](#13-frontend-dashboard)
14. [Tech Stack Recommendation](#14-tech-stack)
15. [Security Checklist](#15-security)
16. [Operational Features](#16-operational-features)
17. [MVP Roadmap — Phased Plan](#17-mvp-roadmap)
18. [Known Risks & Mitigation](#18-risks)

---

## 1. Executive Summary

This document describes the revised architecture for a real-time payment reconciliation platform, scoped to **Razorpay and Stripe** for the MVP. The platform ingests payment events via webhooks supplemented by a mandatory polling fallback, normalizes them into a unified schema, and applies deterministic matching rules to detect exceptions: unmatched payments, refund mismatches, amount discrepancies, and missing captures.

Key design changes from v1.0:

- **Webhooks are not enough.** We mandate a polling fallback against provider APIs because webhooks are inherently unreliable. Both Razorpay and Stripe can silently drop or delay events under load.
- **Settlements are a first-class entity.** Reconciliation happens at the settlement level — matching a batch of transactions to a single bank credit — not just at the individual transaction level.
- **Schema is user-centric from day one.** We add `user_id` as a nullable indexed field now, even though fraud detection is a future feature. Migration later is expensive.
- **Fees are known only at settlement, not at capture.** The matching engine must handle a "pending settlement" state for transactions that have not yet been settled.
- **State machine ordering.** Events can arrive out of order. We apply state transitions based on event timestamps, not ingestion time.

---

## 2. What We Are Building (And What We Are Not)

### MVP Scope

| In Scope | Out of Scope (Phase 2+) |
|---|---|
| Razorpay webhook ingestion | PayU, Paytm, PayPal, Adyen |
| Stripe webhook ingestion | Bank statement CSV upload |
| API polling fallback for both providers | Multi-currency FX reconciliation |
| Settlement-level reconciliation | ML-based fraud detection |
| Exception dashboard (1/7/30-day) | Chargeback workflow management |
| Manual exception resolution with audit trail | ERP / accounting system integration |
| User identity table (schema only, no logic) | Automated bank reconciliation |
| Monitoring, alerting, replay | White-label / multi-tenant SaaS |

### The Core User Story

A merchant processes thousands of payments per day across Razorpay and Stripe. Every T+2 days, Razorpay settles funds to their bank account. Every few days, Stripe does the same. The merchant's finance team needs to know:

1. Did every payment gateway transaction match an order in our system?
2. Did the settlement amount we received in our bank match what the gateway said we'd get?
3. Are there any unmatched refunds, failed-but-charged transactions, or amount discrepancies?
4. When an exception is found, who reviewed it and when?

This platform answers all four questions.

---

## 3. Provider Integration — Razorpay & Stripe

### 3.1 Razorpay

**Webhook Events to Subscribe (MVP)**

| Event | Trigger | Key Fields |
|---|---|---|
| `payment.authorized` | Payment authorized, not yet captured | `id`, `amount`, `currency`, `status`, `order_id`, `method`, `created_at` |
| `payment.captured` | Payment fully captured | `id`, `amount`, `currency`, `fee`, `tax`, `order_id`, `captured_at` |
| `payment.failed` | Payment attempt failed | `id`, `amount`, `error_code`, `error_description`, `order_id` |
| `order.paid` | Order marked fully paid | `id`, `amount`, `amount_paid`, `status` |
| `refund.processed` | Refund successfully processed | `id`, `amount`, `payment_id`, `status`, `speed_processed` |
| `refund.failed` | Refund attempt failed | `id`, `payment_id`, `amount`, `status` |
| `dispute.created` | Chargeback/dispute opened | `id`, `payment_id`, `amount`, `reason_code`, `respond_by` |
| `settlement.processed` | Settlement created by Razorpay | `id`, `amount`, `utr`, `created_at` |

**Important Razorpay Behaviors**

- Signature is in header `X-Razorpay-Signature`. Computed as HMAC-SHA256 of raw request body using the webhook secret.
- Must use the **raw body** for signature verification. Do not parse to JSON first.
- Razorpay retries failed webhooks for up to **24 hours** with exponential backoff.
- Webhook IPs are published by Razorpay; whitelist them at the infrastructure layer.
- Settlement cycle is **T+2 working days** by default. Each settlement has a unique `settlement_id` and a bank **UTR number** for tracking.
- `fee` and `tax` in the `payment.captured` payload represent the gateway fee and GST on that fee respectively. The `net_amount = amount - fee - tax`.
- The `fee` field is **not available** in `payment.authorized` — only after capture.

**Razorpay Polling API (Fallback)**

```
GET https://api.razorpay.com/v1/payments?from={unix_ts}&to={unix_ts}&count=100
GET https://api.razorpay.com/v1/refunds?from={unix_ts}&to={unix_ts}&count=100
GET https://api.razorpay.com/v1/settlements?from={unix_ts}&to={unix_ts}&count=100
GET https://api.razorpay.com/v1/settlements/recon/combined?year={Y}&month={M}&day={D}
```

The settlement recon endpoint is critical — it returns every individual transaction (payment, refund, adjustment) that was bundled into a settlement, along with the exact fee and net amounts. This is the ground truth for settlement reconciliation.

---

### 3.2 Stripe

**Webhook Events to Subscribe (MVP)**

| Event | Trigger | Key Fields |
|---|---|---|
| `payment_intent.succeeded` | Payment fully succeeded | `id`, `amount`, `currency`, `status`, `customer`, `payment_method`, `created` |
| `payment_intent.payment_failed` | Payment intent failed | `id`, `last_payment_error.code`, `last_payment_error.message` |
| `charge.succeeded` | Charge captured | `id`, `amount`, `currency`, `payment_intent`, `balance_transaction` |
| `charge.refunded` | Full or partial refund applied | `id`, `amount_refunded`, `refunds` |
| `charge.dispute.created` | Chargeback opened | `id`, `amount`, `charge`, `reason`, `status` |
| `refund.created` | Refund object created | `id`, `amount`, `charge`, `status` |
| `payout.paid` | Stripe payout landed in bank | `id`, `amount`, `arrival_date`, `bank_account` |
| `balance.available` | Balance updated (use for drift monitoring) | `available`, `pending` |

**Important Stripe Behaviors**

- Signature is in header `Stripe-Signature`. Stripe uses a timestamp+signature format: `t=timestamp,v1=signature`.
- Must use the **raw body** for verification. Must verify within **5 minutes of the timestamp** to prevent replay attacks — Stripe enforces this.
- Stripe retries for up to **3 days** with exponential backoff.
- Stripe events can arrive **out of order**. A `charge.succeeded` may arrive before the corresponding `payment_intent.succeeded`. Use the event's `created` field, not ingestion time, for ordering.
- To get fee details, call `GET /v1/balance_transactions/{id}` using the `balance_transaction` field on the charge. This is a separate API call — fees are not embedded in the webhook payload.
- Stripe's settlement equivalent is a **payout**. A single payout can bundle many charges. The `payout_id` on a balance transaction links it back to the payout.

**Stripe Polling API (Fallback)**

```
GET https://api.stripe.com/v1/charges?created[gte]={ts}&created[lte]={ts}&limit=100
GET https://api.stripe.com/v1/refunds?created[gte]={ts}&limit=100
GET https://api.stripe.com/v1/balance/history?type=charge&limit=100  (for fees)
GET https://api.stripe.com/v1/payouts?created[gte]={ts}&limit=100
```

---

## 4. The Golden Rule: Webhooks + Polling Fallback

**Webhooks are at-most-once delivery with best-effort retries. They are not a reliable audit log. You must poll.**

### Why Webhooks Alone Fail

- Provider infrastructure outages silently drop events
- Your endpoint returning a non-2xx causes retries, but only for the retry window (24h Razorpay, 3 days Stripe)
- Events can be delivered out of order; a `refund.processed` can arrive before `payment.captured`
- During your own deployments or downtime, you miss events that never retry
- Razorpay explicitly says: "If a critical user-facing flow requires instant status and the webhook has not arrived, perform an immediate API fetch"

### Polling Strategy

We run two types of polling jobs:

**Type 1 — Gap Filler (runs every 15 minutes)**

Fetches all transactions from the provider APIs for the last 30 minutes (overlapping window). Compares against what's already in our database by `provider_event_id`. Inserts anything missing. This catches events we never received.

**Type 2 — Settlement Reconciler (runs once daily, at 2 AM)**

Fetches the full settlement report for the previous day from the provider. For Razorpay, uses the `/settlements/recon/combined` endpoint. For Stripe, fetches all balance transactions grouped by `payout_id`. This is the source of truth for fee amounts and net credits.

```
Polling Job Pseudocode (Gap Filler):

every 15 minutes:
  for each provider in [razorpay, stripe]:
    window_start = now() - 30 minutes
    window_end = now()
    
    events = provider.fetch_payments(from=window_start, to=window_end)
    for event in events:
      if not db.exists(provider=provider, provider_event_id=event.id):
        normalize_and_insert(event, source="polling")
        log("Gap filled: {provider} {event.id}")
      else:
        // Already exists from webhook — update if status changed
        update_if_newer(event)
```

### Deduplication Between Webhook and Poll

Both sources write to the same table. Deduplication key is `(provider, provider_event_id)`. Use an `ON CONFLICT DO UPDATE` (upsert) pattern, updating only if the incoming event has a newer timestamp than what we have. This handles the case where a webhook and a poll bring the same event in different states.

---

## 5. Universal Data Schema

### 5.1 Core Tables

#### `webhook_events` (raw ingestion log)

Every incoming webhook is stored here immediately, before any processing. This is the audit log and replay source.

```sql
CREATE TABLE webhook_events (
  id                BIGSERIAL PRIMARY KEY,
  provider          VARCHAR(30)  NOT NULL,         -- 'razorpay' | 'stripe'
  provider_event_id VARCHAR(120) NOT NULL,
  event_type        VARCHAR(100) NOT NULL,          -- e.g. 'payment.captured'
  received_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  payload           JSONB        NOT NULL,
  signature_valid   BOOLEAN      NOT NULL,
  source            VARCHAR(20)  NOT NULL,          -- 'webhook' | 'polling'
  processed         BOOLEAN      NOT NULL DEFAULT FALSE,
  processed_at      TIMESTAMPTZ,
  processing_error  TEXT,
  UNIQUE (provider, provider_event_id)
);

CREATE INDEX idx_webhook_events_unprocessed ON webhook_events (processed, received_at) WHERE processed = FALSE;
CREATE INDEX idx_webhook_events_provider_event ON webhook_events (provider, provider_event_id);
```

This table has a unique constraint on `(provider, provider_event_id)`. If the same event arrives twice (retry or poll overlap), the insert fails gracefully — we do not process it twice.

---

#### `transactions` (normalized, unified)

```sql
CREATE TABLE transactions (
  -- Identity
  id                      BIGSERIAL PRIMARY KEY,
  provider                VARCHAR(30)    NOT NULL,
  provider_transaction_id VARCHAR(120)   NOT NULL,   -- Razorpay pay_xxx / Stripe ch_xxx
  provider_event_id       VARCHAR(120),               -- Linked webhook event ID
  merchant_id             VARCHAR(60)    NOT NULL,    -- Our internal merchant identifier
  
  -- Order Reference
  order_id                VARCHAR(120),               -- Merchant's order/reference ID
  provider_order_id       VARCHAR(120),               -- Provider's order ID (e.g. Razorpay order_xxx)
  
  -- Type and State
  event_type              VARCHAR(30)    NOT NULL,    -- 'payment' | 'refund' | 'chargeback' | 'adjustment'
  status                  VARCHAR(30)    NOT NULL,    -- See status enum below
  parent_transaction_id   BIGINT REFERENCES transactions(id),  -- For refunds → original payment
  
  -- Amounts (always stored in smallest currency unit: paisa / cents)
  presentment_amount      BIGINT         NOT NULL,    -- What customer paid (in presentment_currency)
  presentment_currency    CHAR(3)        NOT NULL,
  settlement_amount       BIGINT,                     -- What merchant receives (in settlement_currency)
  settlement_currency     CHAR(3),
  fee_amount              BIGINT,                     -- Gateway fee (in settlement_currency)
  tax_amount              BIGINT,                     -- GST/tax on fee
  net_amount              BIGINT,                     -- settlement_amount - fee_amount - tax_amount
  
  -- Timestamps (all UTC)
  event_occurred_at       TIMESTAMPTZ    NOT NULL,    -- When event happened at provider
  captured_at             TIMESTAMPTZ,
  refunded_at             TIMESTAMPTZ,
  ingested_at             TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
  
  -- Settlement Link
  settlement_id           VARCHAR(120),               -- Foreign key to settlements table
  settlement_date         DATE,
  utr_number              VARCHAR(60),                -- Bank UTR for bank reconciliation
  
  -- Payment Method
  payment_method          VARCHAR(30),               -- 'card' | 'upi' | 'netbanking' | 'wallet'
  payment_method_detail   VARCHAR(60),               -- e.g. 'visa' | 'gpay' | 'sbi_netbanking'
  card_last4              CHAR(4),
  card_network            VARCHAR(20),               -- 'visa' | 'mastercard' | 'rupay'
  bank                    VARCHAR(60),
  vpa                     VARCHAR(120),              -- UPI VPA (e.g. user@okicici)
  
  -- Payer Identity (for future fraud detection — populated when available)
  user_id                 BIGINT REFERENCES users(id),    -- NULL until fraud module is built
  payer_email             VARCHAR(254),
  payer_phone             VARCHAR(20),
  payer_name              VARCHAR(120),
  
  -- Reconciliation State
  reconciliation_status   VARCHAR(30)    NOT NULL DEFAULT 'pending',
  -- 'pending' | 'matched' | 'exception' | 'manually_resolved' | 'ignored'
  matched_at              TIMESTAMPTZ,
  exception_id            BIGINT,                    -- FK to exceptions table if flagged
  
  -- Metadata
  raw_payload             JSONB,                     -- Original normalized payload (prunable after 90 days)
  notes                   JSONB,                     -- Provider-specific extra fields
  
  -- Audit
  created_at              TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
  updated_at              TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
  
  UNIQUE (provider, provider_transaction_id)
);

-- Status enum values:
-- authorized | captured | failed | refunded | partially_refunded | disputed | cancelled | pending_settlement

-- Indexes
CREATE INDEX idx_txn_provider_id ON transactions (provider, provider_transaction_id);
CREATE INDEX idx_txn_merchant_order ON transactions (merchant_id, order_id);
CREATE INDEX idx_txn_settlement ON transactions (settlement_id);
CREATE INDEX idx_txn_reconciliation_status ON transactions (reconciliation_status, event_occurred_at);
CREATE INDEX idx_txn_user_id ON transactions (user_id) WHERE user_id IS NOT NULL;
CREATE INDEX idx_txn_event_occurred ON transactions (event_occurred_at DESC);
CREATE INDEX idx_txn_parent ON transactions (parent_transaction_id) WHERE parent_transaction_id IS NOT NULL;
```

---

#### `settlements` (settlement as a first-class entity)

```sql
CREATE TABLE settlements (
  id                    BIGSERIAL PRIMARY KEY,
  provider              VARCHAR(30)    NOT NULL,
  provider_settlement_id VARCHAR(120)  NOT NULL,
  merchant_id           VARCHAR(60)    NOT NULL,
  
  -- What the gateway says we should receive
  gross_amount          BIGINT         NOT NULL,   -- Sum of all captured payments
  total_fees            BIGINT         NOT NULL,   -- Total gateway fees
  total_tax             BIGINT         NOT NULL,   -- GST on fees
  net_amount            BIGINT         NOT NULL,   -- gross - fees - tax (should match bank credit)
  currency              CHAR(3)        NOT NULL,
  
  -- What actually arrived in the bank
  bank_credit_amount    BIGINT,                    -- Populated after bank reconciliation
  bank_credit_date      DATE,
  utr_number            VARCHAR(60),               -- Bank UTR — used to match bank statement
  
  -- Reconciliation State
  settlement_status     VARCHAR(30)    NOT NULL DEFAULT 'pending',
  -- 'pending' | 'settled' | 'matched_to_bank' | 'discrepant' | 'on_hold'
  
  -- Metadata
  settled_at            TIMESTAMPTZ,
  transaction_count     INTEGER,
  created_at            TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
  updated_at            TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
  
  UNIQUE (provider, provider_settlement_id)
);

CREATE INDEX idx_settlement_merchant ON settlements (merchant_id, settled_at DESC);
CREATE INDEX idx_settlement_utr ON settlements (utr_number) WHERE utr_number IS NOT NULL;
```

---

#### `exceptions` (flagged mismatches)

```sql
CREATE TABLE exceptions (
  id                  BIGSERIAL PRIMARY KEY,
  merchant_id         VARCHAR(60)    NOT NULL,
  exception_type      VARCHAR(50)    NOT NULL,
  -- 'unmatched_payment' | 'unmatched_refund' | 'amount_mismatch' |
  -- 'missing_capture' | 'duplicate' | 'settlement_discrepancy' |
  -- 'orphan_refund' | 'status_mismatch' | 'fee_discrepancy'
  
  severity            VARCHAR(20)    NOT NULL DEFAULT 'medium',
  -- 'low' | 'medium' | 'high' | 'critical'
  
  transaction_id      BIGINT REFERENCES transactions(id),
  settlement_id       BIGINT REFERENCES settlements(id),
  
  -- What we expected vs what we got
  expected_amount     BIGINT,
  actual_amount       BIGINT,
  discrepancy_amount  BIGINT,        -- abs(expected - actual)
  currency            CHAR(3),
  
  description         TEXT           NOT NULL,
  
  -- Resolution
  status              VARCHAR(30)    NOT NULL DEFAULT 'open',
  -- 'open' | 'in_review' | 'resolved' | 'ignored'
  resolved_by         VARCHAR(60),   -- User who resolved it
  resolved_at         TIMESTAMPTZ,
  resolution_notes    TEXT,
  
  detected_at         TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
  updated_at          TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_exceptions_merchant_status ON exceptions (merchant_id, status, detected_at DESC);
CREATE INDEX idx_exceptions_type ON exceptions (exception_type, detected_at DESC);
```

---

#### `users` (user identity — schema-ready for fraud detection)

```sql
CREATE TABLE users (
  id                BIGSERIAL PRIMARY KEY,
  merchant_id       VARCHAR(60)   NOT NULL,
  
  -- Canonical identity fields (deduplicated)
  email             VARCHAR(254),
  phone             VARCHAR(20),
  name              VARCHAR(120),
  
  -- Aggregated signals (updated incrementally via events)
  first_seen_at     TIMESTAMPTZ,
  last_seen_at      TIMESTAMPTZ,
  total_txn_count   INTEGER       NOT NULL DEFAULT 0,
  total_txn_amount  BIGINT        NOT NULL DEFAULT 0,   -- In paisa/cents
  failed_txn_count  INTEGER       NOT NULL DEFAULT 0,
  distinct_payment_methods INTEGER NOT NULL DEFAULT 0,
  
  -- Risk (populated by fraud module later — NULL until then)
  risk_score        DECIMAL(5,4),
  risk_flags        JSONB,
  
  created_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
  updated_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_users_email_merchant ON users (merchant_id, email) WHERE email IS NOT NULL;
CREATE UNIQUE INDEX idx_users_phone_merchant ON users (merchant_id, phone) WHERE phone IS NOT NULL;
```

---

#### `audit_logs`

```sql
CREATE TABLE audit_logs (
  id              BIGSERIAL PRIMARY KEY,
  actor           VARCHAR(60)   NOT NULL,   -- 'system' or user email
  action          VARCHAR(60)   NOT NULL,   -- 'exception_resolved' | 'exception_ignored' | etc.
  entity_type     VARCHAR(30),              -- 'exception' | 'transaction' | 'settlement'
  entity_id       BIGINT,
  old_value       JSONB,
  new_value       JSONB,
  ip_address      INET,
  created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_entity ON audit_logs (entity_type, entity_id);
CREATE INDEX idx_audit_created ON audit_logs (created_at DESC);
```

---

### 5.2 Status Normalization Map

| Razorpay Status | Stripe Status | Normalized Status |
|---|---|---|
| `authorized` | `requires_capture` | `authorized` |
| `captured` | `succeeded` | `captured` |
| `failed` | `canceled` / `payment_failed` | `failed` |
| — | `processing` | `pending` |
| `refunded` | `refunded` | `refunded` |
| `dispute` | `disputed` | `disputed` |

---

## 6. System Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        INGESTION LAYER                          │
│                                                                 │
│  Razorpay ──webhook──►  POST /webhooks/razorpay                 │
│  Stripe   ──webhook──►  POST /webhooks/stripe    ◄── HTTPS     │
│                              │                                  │
│                      ┌───────▼────────┐                        │
│                      │ Webhook Handler │                        │
│                      │ 1. Verify sig  │                        │
│                      │ 2. Store raw   │                        │
│                      │ 3. Return 200  │                        │
│                      └───────┬────────┘                        │
│                              │ publish                         │
│                      ┌───────▼────────┐                        │
│                      │  Message Queue │  (BullMQ / Redis)      │
│                      │  webhook_events│                        │
│                      └───────┬────────┘                        │
│                              │ consume                         │
│                      ┌───────▼────────┐                        │
│                      │ Worker Pool    │                        │
│                      │ • Normalize    │                        │
│                      │ • Deduplicate  │                        │
│                      │ • Upsert txn  │                        │
│                      │ • Link user   │                        │
│                      └───────┬────────┘                        │
│                              │                                 │
└──────────────────────────────┼──────────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────────┐
│                      STORAGE LAYER                              │
│                                                                 │
│   PostgreSQL                                                    │
│   ┌──────────────┐  ┌──────────────┐  ┌──────────────┐        │
│   │webhook_events│  │ transactions │  │ settlements  │        │
│   └──────────────┘  └──────────────┘  └──────────────┘        │
│   ┌──────────────┐  ┌──────────────┐  ┌──────────────┐        │
│   │  exceptions  │  │    users     │  │  audit_logs  │        │
│   └──────────────┘  └──────────────┘  └──────────────┘        │
│                                                                 │
└──────────────────────────────┬──────────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────────┐
│                   RECONCILIATION LAYER                          │
│                                                                 │
│   Reconciliation Engine (scheduled job, every 5 mins)          │
│   ┌────────────────────────────────────────────┐               │
│   │  1. Exact ID Match (payment ↔ provider)    │               │
│   │  2. Refund ↔ Original Payment Match        │               │
│   │  3. Settlement → Transaction Grouping      │               │
│   │  4. Fee Reconciliation (post-settlement)   │               │
│   │  5. Amount Mismatch Detection              │               │
│   │  6. Status Drift Detection                 │               │
│   └────────────────────────────────────────────┘               │
│                                                                 │
│   Polling Fallback Jobs                                         │
│   ┌───────────────────┐  ┌──────────────────────┐             │
│   │ Gap Filler        │  │ Settlement Reconciler │             │
│   │ (every 15 mins)   │  │ (daily 2 AM)          │             │
│   └───────────────────┘  └──────────────────────┘             │
│                                                                 │
└──────────────────────────────┬──────────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────────┐
│                     API + DASHBOARD LAYER                       │
│                                                                 │
│  REST API (Node.js/Express or FastAPI)                         │
│  React Dashboard                                               │
│  Exception Management UI                                       │
│  Monitoring / Metrics (Prometheus + Grafana or Datadog)        │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘

┌──────────────────────────┐
│  POLLING FALLBACK (separate process)                           │
│  Razorpay API poll ──────►                                     │
│  Stripe API poll  ──────►  Same Queue ──► Same Workers        │
│  (every 15 mins)          source="polling"                     │
└──────────────────────────┘
```

---

## 7. Webhook Ingestion — Implementation Detail

### Step-by-Step Webhook Handler

```
POST /webhooks/razorpay
POST /webhooks/stripe
```

The handler does **only these things**, in this order:

```
1. Read raw body as bytes (do NOT parse JSON yet)
2. Extract signature header
3. Verify signature:
   Razorpay: HMAC-SHA256(raw_body, webhook_secret) == X-Razorpay-Signature
   Stripe:   verify timestamp + v1 signature (within 5-minute window)
4. If signature invalid → return HTTP 400, log the failure
5. Parse JSON
6. Extract provider_event_id
7. INSERT INTO webhook_events (... signature_valid=true, processed=false ...)
   ON CONFLICT (provider, provider_event_id) DO NOTHING
8. If insert succeeded (not duplicate) → publish event_id to queue
9. Return HTTP 200 immediately
   (Do NOT wait for queue publish or DB write to complete response)
```

**Critical: Return 200 before any heavy work.** Stripe times out at 20 seconds; any processing delay will trigger retries. Respond immediately after inserting the raw event.

### Signature Verification — Razorpay

```javascript
const crypto = require('crypto');

function verifyRazorpaySignature(rawBody, signatureHeader, secret) {
  const expectedSig = crypto
    .createHmac('sha256', secret)
    .update(rawBody)  // rawBody must be Buffer, not string
    .digest('hex');
  
  return crypto.timingSafeEqual(
    Buffer.from(expectedSig, 'hex'),
    Buffer.from(signatureHeader, 'hex')
  );
}
```

### Signature Verification — Stripe

```javascript
const stripe = require('stripe')(process.env.STRIPE_SECRET_KEY);

function verifyStripeSignature(rawBody, signatureHeader, endpointSecret) {
  try {
    const event = stripe.webhooks.constructEvent(
      rawBody,           // Must be raw bytes/buffer
      signatureHeader,   // 'Stripe-Signature' header value
      endpointSecret     // whsec_... from dashboard
    );
    return event;  // Returns constructed event if valid
  } catch (err) {
    throw new Error(`Stripe signature verification failed: ${err.message}`);
  }
}
// Note: constructEvent also validates the 5-minute timestamp window automatically
```

### Worker Processing

The worker consumes from the queue and does the actual normalization:

```
Worker Process:
  1. Read webhook_event from queue
  2. Mark webhook_event.processed = true (optimistic, to prevent double-processing)
  3. Normalize payload → unified transaction schema
  4. Resolve user_id (lookup by email/phone, create user record if new)
  5. UPSERT into transactions ON CONFLICT (provider, provider_transaction_id)
     DO UPDATE SET status=..., updated_at=NOW()
     WHERE EXCLUDED.event_occurred_at > transactions.event_occurred_at
     (Only update if incoming event is newer — prevents out-of-order overwrites)
  6. Update webhook_event.processed_at = NOW()
  7. Trigger reconciliation check for this transaction's order_id
```

### Idempotency Layers

There are three independent idempotency checks:

1. **Webhook level**: `UNIQUE (provider, provider_event_id)` on `webhook_events`. Same event never queued twice.
2. **Transaction level**: `UNIQUE (provider, provider_transaction_id)` on `transactions`. Upsert with timestamp guard.
3. **Business level**: Reconciliation marks transactions as matched. Re-running reconciliation on already-matched transactions is a no-op.

---

## 8. Polling Fallback — Implementation Detail

### Gap Filler (Every 15 Minutes)

```javascript
async function razorpayGapFiller() {
  const windowEnd = Math.floor(Date.now() / 1000);
  const windowStart = windowEnd - (30 * 60);  // 30 minutes overlap
  
  // Paginate through all results
  let skip = 0;
  const count = 100;
  
  while (true) {
    const response = await razorpayClient.payments.all({
      from: windowStart,
      to: windowEnd,
      count,
      skip,
    });
    
    for (const payment of response.items) {
      await db.query(`
        INSERT INTO webhook_events 
          (provider, provider_event_id, event_type, payload, signature_valid, source, received_at)
        VALUES 
          ('razorpay', $1, $2, $3, true, 'polling', NOW())
        ON CONFLICT (provider, provider_event_id) DO NOTHING
      `, [payment.id, `payment.${payment.status}`, JSON.stringify(payment)]);
    }
    
    if (response.items.length < count) break;
    skip += count;
  }
}
```

### Settlement Reconciler (Daily)

```javascript
async function razorpaySettlementReconciler(date) {
  // date = yesterday's date
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  
  const reconData = await razorpayClient.settlements.fetchReconDetails({
    year, month, day, count: 1000
  });
  
  for (const item of reconData.items) {
    // Update settlement link and fee data on the transaction
    await db.query(`
      UPDATE transactions 
      SET 
        settlement_id = $1,
        fee_amount = $2,
        tax_amount = $3,
        net_amount = $4,
        settlement_date = $5,
        utr_number = $6,
        updated_at = NOW()
      WHERE provider = 'razorpay' 
        AND provider_transaction_id = $7
        AND settlement_id IS NULL
    `, [
      item.settlement_id,
      item.fee,
      item.tax,
      item.amount - item.fee - item.tax,
      date,
      item.settlement_utr,
      item.entity_id
    ]);
  }
  
  // Now check if settlement total matches sum of transactions
  await reconcileSettlementTotal(item.settlement_id);
}
```

---

## 9. Settlements as a First-Class Entity

### Why Settlements Matter More Than Individual Transactions

The bank doesn't send you money per transaction. It sends one wire transfer every T+2 days that bundles hundreds of transactions. When a merchant checks their bank statement, they see: "Razorpay: ₹82,450.00 — 14 April 2026". They do not see 400 individual line items.

Reconciliation must therefore work at **two levels**:

**Level 1 — Transaction Reconciliation**: Does every payment/refund event in our system match a record in the merchant's order system? (This is what most of the original document describes.)

**Level 2 — Settlement Reconciliation**: Does the settlement total from the gateway match what actually arrived in the bank? (This is what makes the product actually useful to a CFO.)

### Settlement Reconciliation Flow

```
1. Razorpay sends settlement.processed webhook (or we detect it via daily poll)
2. We create a settlements record with gateway's net_amount
3. We fetch all transactions linked to this settlement_id
4. Sum their net_amounts → should equal settlement.net_amount
5. If sum ≠ settlement.net_amount → flag exception type: 'settlement_discrepancy'
6. Later: merchant uploads bank statement (or we integrate bank API)
7. We find the UTR number in the bank data → bank_credit_amount
8. If bank_credit_amount ≠ settlement.net_amount → flag exception
9. If match → mark settlement as 'matched_to_bank'
```

### Settlement Timeline Awareness

Do not flag a transaction as an exception just because `fee_amount` is NULL. Fees are only known after settlement. A transaction should be in status `pending_settlement` while waiting. The reconciliation engine should skip fee checks for transactions in this state.

```
Transaction lifecycle:
payment.authorized → [fee: NULL, status: authorized, recon: pending]
payment.captured   → [fee: NULL, status: captured, recon: pending_settlement]
settlement event   → [fee: populated, status: captured, recon: ready_to_match]
reconciliation run → [matched or exception]
```

---

## 10. Reconciliation Engine

The reconciliation engine runs every 5 minutes as a scheduled job, and also gets triggered per-order when a new transaction is ingested.

### Rule Set (MVP)

**Rule 1: Exact Provider ID Match**

For every captured payment from the provider, check if there is a corresponding order in the merchant system with the same `order_id`. If yes and amounts match → `matched`. If amounts differ → `amount_mismatch` exception.

```sql
-- Find unmatched captured payments older than 5 minutes
SELECT t.* FROM transactions t
WHERE t.status = 'captured'
  AND t.reconciliation_status = 'pending'
  AND t.event_occurred_at < NOW() - INTERVAL '5 minutes'
  AND t.order_id IS NOT NULL;
```

**Rule 2: Refund ↔ Parent Payment Match**

Every refund event must link to an existing captured payment via `parent_transaction_id`. If no parent found → `orphan_refund` exception. If refund amount > original payment amount → `refund_amount_overflow` exception.

```sql
-- Find refunds with no parent
SELECT t.* FROM transactions t
WHERE t.event_type = 'refund'
  AND t.parent_transaction_id IS NULL
  AND t.ingested_at < NOW() - INTERVAL '10 minutes';
```

**Rule 3: Status Drift Detection**

If our database has a transaction as `captured` but the provider API returns it as `failed` (detected by polling fallback) → flag `status_mismatch` exception. This is a serious exception.

**Rule 4: Settlement Total Check**

After a settlement is created, sum the `net_amount` of all transactions linked to that `settlement_id`. Compare to `settlements.net_amount`. If difference > configurable threshold (default: 1 rupee) → `settlement_discrepancy` exception.

**Rule 5: Missing Capture Detection**

If a payment is in `authorized` state for more than 24 hours without being captured → flag `missing_capture` exception. This is a revenue risk: authorized-but-uncaptured payments auto-expire.

```sql
-- Authorized payments older than 24h
SELECT * FROM transactions
WHERE status = 'authorized'
  AND event_occurred_at < NOW() - INTERVAL '24 hours'
  AND reconciliation_status = 'pending';
```

**Rule 6: Duplicate Detection**

If two transactions from the same provider have the same `order_id` and both are `captured` → flag `duplicate_capture` exception. This is common when retries result in double charges.

### Exception Severity Matrix

| Exception Type | Severity | Action |
|---|---|---|
| `missing_capture` | High | Alert immediately — revenue risk |
| `settlement_discrepancy` | Critical | Escalate — actual money difference |
| `duplicate_capture` | Critical | Block and alert — customer overcharged |
| `orphan_refund` | High | Review — money went out with no matching payment |
| `status_mismatch` | High | Investigate — data integrity issue |
| `amount_mismatch` | Medium | Review — could be discount/coupon difference |
| `unmatched_payment` | Medium | 48h window before flagging — might be delayed order creation |
| `fee_discrepancy` | Low | Monitor — usually rounding |

---

## 11. User-Centric Schema for Future Fraud Detection

### Why We Add This Now

Adding `user_id` to the transactions table later means a migration on a table that may have millions of rows, with downtime or complex online migration strategies. The cost of adding it now (a nullable indexed column) is near zero. The cost of adding it later is high.

### Identity Resolution Logic

When a new transaction is ingested, we attempt to find or create a user:

```javascript
async function resolveUserId(merchantId, payerEmail, payerPhone) {
  // Try to find existing user by email
  if (payerEmail) {
    const existing = await db.query(
      'SELECT id FROM users WHERE merchant_id = $1 AND email = $2',
      [merchantId, payerEmail.toLowerCase()]
    );
    if (existing.rows.length > 0) return existing.rows[0].id;
  }
  
  // Try by phone
  if (payerPhone) {
    const existing = await db.query(
      'SELECT id FROM users WHERE merchant_id = $1 AND phone = $2',
      [merchantId, normalizePhone(payerPhone)]
    );
    if (existing.rows.length > 0) return existing.rows[0].id;
  }
  
  // Create new user
  const result = await db.query(`
    INSERT INTO users (merchant_id, email, phone, name, first_seen_at, last_seen_at)
    VALUES ($1, $2, $3, $4, NOW(), NOW())
    ON CONFLICT DO NOTHING
    RETURNING id
  `, [merchantId, payerEmail, payerPhone, payerName]);
  
  return result.rows[0]?.id || null;
}
```

### UPI Identity Challenge

UPI transactions from Razorpay often provide only a **VPA** (Virtual Payment Address) like `user@okicici`, with no name or phone number. Store the VPA in the `vpa` column on transactions. When building fraud detection, VPA-to-user mapping becomes its own entity. For now, store it and don't try to resolve it to a user — the VPA may be shared across accounts or may change per transaction.

### Fraud-Ready Aggregates

The `users` table maintains running totals updated via a trigger or application-level update on every transaction insert:

```sql
-- Update user aggregates on new transaction
UPDATE users SET
  total_txn_count = total_txn_count + 1,
  total_txn_amount = total_txn_amount + $amount,
  failed_txn_count = failed_txn_count + CASE WHEN $status = 'failed' THEN 1 ELSE 0 END,
  last_seen_at = NOW(),
  updated_at = NOW()
WHERE id = $user_id;
```

When you are ready to build fraud detection (Phase 2+), these aggregates are already computed. You add a `payment_methods` table and a `user_device_fingerprints` table, and you build velocity rules on top of the existing data.

---

## 12. API Design

### Endpoints (MVP)

```
# Webhooks (incoming from providers)
POST  /webhooks/razorpay         — Razorpay webhook receiver
POST  /webhooks/stripe           — Stripe webhook receiver

# Exceptions
GET   /api/v1/exceptions         — List exceptions (filters: days, provider, type, status)
GET   /api/v1/exceptions/:id     — Exception detail
PATCH /api/v1/exceptions/:id     — Resolve / ignore with notes

# Transactions
GET   /api/v1/transactions              — List (filters: provider, status, date range, order_id)
GET   /api/v1/transactions/:id          — Single transaction detail with all linked events
GET   /api/v1/transactions/:id/events   — All events for this transaction ID

# Settlements
GET   /api/v1/settlements               — List settlements
GET   /api/v1/settlements/:id           — Settlement detail with linked transactions
GET   /api/v1/settlements/:id/transactions — All txns in a settlement

# Dashboard
GET   /api/v1/dashboard/summary         — Counts: total txns, matched, exceptions by type
GET   /api/v1/dashboard/metrics         — Webhook delivery rate, match rate, queue depth

# Admin
POST  /api/v1/admin/replay              — Replay failed webhook events from DLQ
POST  /api/v1/admin/poll                — Manually trigger polling job for a date range
GET   /api/v1/admin/audit-logs          — Audit trail
```

### Query Patterns for Exception Listing

```
GET /api/v1/exceptions?days=7&provider=razorpay&type=amount_mismatch&status=open&page=1&limit=50

Response:
{
  "summary": {
    "total": 23,
    "by_type": { "amount_mismatch": 10, "orphan_refund": 8, "missing_capture": 5 },
    "by_severity": { "critical": 2, "high": 8, "medium": 13 }
  },
  "exceptions": [
    {
      "id": 1042,
      "type": "amount_mismatch",
      "severity": "medium",
      "provider": "razorpay",
      "transaction_id": "pay_OKxyz123",
      "order_id": "ORD-9821",
      "expected_amount": 50000,
      "actual_amount": 48500,
      "discrepancy_amount": 1500,
      "currency": "INR",
      "description": "Payment amount ₹485.00 does not match expected order amount ₹500.00",
      "status": "open",
      "detected_at": "2026-04-14T08:23:11Z"
    }
  ],
  "pagination": { "page": 1, "limit": 50, "total_pages": 1 }
}
```

---

## 13. Frontend / Dashboard

### Views Required for MVP

**View 1: Summary Dashboard**

- Header: Total transactions | Matched | Open exceptions | Match rate %
- Tabs: Last 1 day / 7 days / 30 days
- Breakdown by provider (Razorpay vs Stripe)
- Exception count by type (bar chart)
- Settlement status summary (pending / matched / discrepant)

**View 2: Exception List**

- Searchable, filterable table
- Filters: Provider, Exception Type, Severity, Status (open/resolved), Date Range
- Columns: Detected At | Provider | Type | Severity | Order ID | Transaction ID | Discrepancy Amount | Status
- Row click → Exception Detail

**View 3: Exception Detail**

- Full exception information
- Linked transaction data (both sides if applicable)
- Raw provider payload (expandable/collapsible)
- Resolution panel: Status dropdown + Notes text area + Submit
- Audit trail: who changed what and when

**View 4: Transaction Lookup**

- Search by Transaction ID, Order ID, email
- Shows full event history for a transaction
- Shows linked settlement
- Shows reconciliation status and matched records

**View 5: Settlement View**

- List of settlements with status
- Settlement detail: expected vs bank credit
- Linked transaction count and list

---

## 14. Tech Stack Recommendation

### Why This Stack

We recommend a stack that prioritizes: operational simplicity for a small team, strong financial data integrity (ACID compliance), and fast time-to-production.

| Layer | Choice | Reason |
|---|---|---|
| **Backend API** | Node.js + TypeScript + Express | Fast iteration, strong Stripe/Razorpay SDK support, team familiarity |
| **Database** | PostgreSQL 16 | ACID transactions, JSONB for payloads, excellent index support, industry standard for fintech |
| **Queue** | BullMQ + Redis | Simple to operate, excellent for webhook processing, built-in DLQ, retry logic, job monitoring UI (Bull Board) |
| **ORM / Query** | Prisma or raw `pg` | Prisma for migrations and type safety; raw pg for performance-critical reconciliation queries |
| **Frontend** | React + TypeScript + TanStack Query | Solid data fetching, good for filter-heavy tables |
| **UI Components** | shadcn/ui or Ant Design | Table-heavy finance UI benefits from pre-built data grid components |
| **Monitoring** | Datadog or Grafana + Prometheus | Webhook delivery rate, queue depth, match rate metrics |
| **Hosting** | Railway or Render (MVP) → AWS (scale) | Low ops overhead for MVP; migrate to ECS/RDS when volumes demand it |
| **Secrets** | AWS Secrets Manager or Doppler | Never put webhook secrets in env files |

### Infrastructure for MVP (Keep It Simple)

```
1 x App Server (Node.js API + Webhook Handler)
1 x Worker Server (Queue Consumer + Reconciliation Jobs)
1 x PostgreSQL (managed, e.g. Railway Postgres or Supabase)
1 x Redis (managed, e.g. Upstash or Railway Redis)
```

This is enough to handle hundreds of thousands of transactions per month. Scale the worker horizontally when needed — it's stateless.

### Do NOT Over-Engineer for MVP

The original document mentioned Kafka. **Do not use Kafka for MVP.** Kafka requires operational expertise and is built for millions of events per second. BullMQ on Redis handles tens of thousands of jobs per minute and is operationally trivial. Switch to Kafka only when you have >10M events/day and a dedicated DevOps engineer.

---

## 15. Security Checklist

### Webhook Security

- [ ] Use raw body for signature verification (never re-serialize parsed JSON)
- [ ] Use `crypto.timingSafeEqual` for signature comparison (prevents timing attacks)
- [ ] Validate Stripe's 5-minute timestamp window
- [ ] Whitelist Razorpay's published IP ranges at load balancer/firewall level
- [ ] Use separate webhook secrets per environment (test vs live)
- [ ] Rotate webhook secrets every 90 days (Stripe supports zero-downtime rotation with dual secrets)
- [ ] Log all signature failures with source IP (potential attack indicator)

### API Security

- [ ] All endpoints require authentication (JWT or API key)
- [ ] Scope access by `merchant_id` — a merchant must never see another merchant's data
- [ ] Rate limit webhook endpoints (max 1000 req/min per IP)
- [ ] Enable HTTPS only — no HTTP
- [ ] CORS restricted to your frontend domain only

### Data Security

- [ ] Do not log full card numbers or CVVs (they shouldn't be in payloads, but add a payload scrubber)
- [ ] Encrypt PII at rest (email, phone) using column-level encryption or AES-256 encryption before storage
- [ ] `raw_payload` column: prune sensitive fields before storing (Razorpay and Stripe mask card numbers in webhooks, but validate this)
- [ ] Database: no public access, VPC-only, credentials in secrets manager
- [ ] Enable PostgreSQL row-level security if you go multi-tenant
- [ ] Regular backups with point-in-time recovery

### Compliance Considerations

- You are handling payment data but NOT card numbers (those stay with the gateways). However, PII (email, phone) is present.
- India: Review RBI's data localization guidelines — payment data about Indian customers may need to be stored in India.
- PCI-DSS: You are not a payment processor, but review scope with a legal advisor.

---

## 16. Operational Features

### Monitoring Metrics to Track

| Metric | Alert Threshold |
|---|---|
| Webhook delivery failure rate | > 5% over 5 minutes |
| Queue depth (unprocessed jobs) | > 500 for > 2 minutes |
| Reconciliation job duration | > 10 minutes |
| Daily exception rate | > 20% of transaction volume |
| Settlement discrepancy amount | Any amount > ₹100 |
| Polling fallback gaps filled | > 50 gaps/hour (signals webhook reliability issue) |
| Stripe/Razorpay API error rate | > 2% on polling calls |

### Replay and Recovery

**Scenario 1: Our webhook endpoint was down for 2 hours**

Recovery: Razorpay will auto-retry for 24h. Stripe for 3 days. For anything that fell through: run the gap filler polling job manually for the affected time window.

```
POST /api/v1/admin/poll
{
  "provider": "razorpay",
  "from": "2026-04-14T10:00:00Z",
  "to": "2026-04-14T12:00:00Z"
}
```

**Scenario 2: A worker bug processed events incorrectly**

Recovery: Raw events are stored in `webhook_events`. Mark them as `processed = false` and requeue. The upsert logic ensures correct data wins.

**Scenario 3: Razorpay lets you replay specific events via dashboard**

Razorpay allows replaying any event up to 15 days old from their dashboard. For Stripe, use `stripe events resend {event_id}` via CLI.

### Dead Letter Queue

Any webhook that fails processing more than 5 times goes to the DLQ. The admin UI shows DLQ contents. An engineer can inspect, fix the bug, and requeue.

---

## 17. MVP Roadmap — Phased Plan

### Phase 1: Foundation (Weeks 1–3)

**Goal**: Working webhook ingestion with raw storage and basic normalization.

| Task | Priority | Effort |
|---|---|---|
| Set up PostgreSQL schema (all tables above) | P0 | 2 days |
| Razorpay webhook handler with signature verification | P0 | 1 day |
| Stripe webhook handler with signature verification | P0 | 1 day |
| BullMQ queue setup with worker pool | P0 | 1 day |
| Normalization layer (provider → unified schema) | P0 | 3 days |
| Idempotency (unique constraints + upsert logic) | P0 | 1 day |
| Polling gap filler (Razorpay + Stripe) | P0 | 2 days |
| Basic health check API | P0 | 0.5 days |

**Exit Criteria**: Webhook events from both providers land in DB with no duplicates. Polling fills gaps. Raw payloads preserved.

---

### Phase 2: Reconciliation Core (Weeks 4–5)

**Goal**: Working exception detection for the most important cases.

| Task | Priority | Effort |
|---|---|---|
| Reconciliation engine — Rule 1: ID match | P0 | 2 days |
| Reconciliation engine — Rule 2: Orphan refunds | P0 | 1 day |
| Reconciliation engine — Rule 5: Missing captures | P0 | 1 day |
| Reconciliation engine — Rule 6: Duplicate detection | P0 | 1 day |
| Settlement entity creation from webhooks + polling | P0 | 2 days |
| Daily settlement reconciler job | P0 | 2 days |
| Exceptions table population | P0 | 1 day |

**Exit Criteria**: Exceptions are being created for real mismatches. Settlement totals are being checked daily.

---

### Phase 3: Dashboard MVP (Weeks 6–7)

**Goal**: Usable UI for finance team.

| Task | Priority | Effort |
|---|---|---|
| REST API for exceptions (list, filter, detail) | P0 | 2 days |
| REST API for dashboard summary stats | P0 | 1 day |
| Exception list UI with filters | P0 | 2 days |
| Exception detail + manual resolution UI | P0 | 2 days |
| Transaction lookup UI | P1 | 1 day |
| Audit log API + UI | P1 | 1 day |

**Exit Criteria**: Finance team can log in, see exceptions, and resolve them with notes.

---

### Phase 4: Hardening (Week 8)

**Goal**: Production-ready reliability and security.

| Task | Priority | Effort |
|---|---|---|
| Monitoring dashboards (queue depth, match rate) | P0 | 1 day |
| Alerting for critical exceptions | P0 | 1 day |
| DLQ visibility in admin UI | P0 | 1 day |
| Security review (secrets, CORS, auth, PII scrubbing) | P0 | 2 days |
| Load testing (simulate 10K webhooks/day) | P1 | 1 day |
| Runbook: recovery procedures | P1 | 0.5 days |

**Exit Criteria**: System runs unattended for 48 hours with no data loss. Alerts fire correctly.

---

### Phase 5: User Identity Foundation (Week 9+)

**Goal**: User resolution running, data quality improving, ready for fraud detection later.

| Task | Priority | Effort |
|---|---|---|
| User identity resolution on ingestion | P1 | 2 days |
| User aggregate updates (txn count, amounts) | P1 | 1 day |
| UPI VPA storage and indexing | P1 | 0.5 days |
| Settlement → bank statement matching (manual upload) | P2 | 3 days |

---

## 18. Known Risks & Mitigation

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Webhook silent drop (provider outage) | Medium | High | Polling fallback — mandatory, not optional |
| Out-of-order event processing overwrites newer state | High | Medium | State machine: only update if incoming event is newer |
| Fee data missing at reconciliation time | High | Low | Pending settlement state; don't flag as exception until settled |
| UPI VPA not resolving to user identity | High | Low | Store VPA, skip user resolution for UPI until Phase 5 |
| Razorpay/Stripe API rate limits hit during polling | Low | Medium | Respect rate limits with exponential backoff; paginate at 100/request |
| Stripe 5-minute signature replay window missed | Medium | Medium | Verify AND store raw event in same transaction before returning 200 |
| PII data stored unencrypted | Low | Critical | Encrypt email/phone columns in Phase 4 hardening |
| Multi-tenant data leak (merchant A sees merchant B's data) | Low | Critical | Row-level security + mandatory `merchant_id` filter on all queries |
| Settlement discrepancy due to partial capture | Medium | Medium | Track `captured_amount` separately from `authorized_amount` |

---

*Document Version 2.0 — Covers Razorpay + Stripe MVP*
*Authors: Engineering Team*
*Next Review: After Phase 3 completion*
