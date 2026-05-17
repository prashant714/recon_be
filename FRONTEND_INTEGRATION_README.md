# Frontend Integration README

This document lists the backend API contract needed by the frontend team: endpoints, auth, query params, request bodies, response shapes, enum values, and integration notes.

## Base URL

Local backend default:

```text
http://localhost:8080
```

API docs are also exposed by the backend:

```text
http://localhost:8080/swagger-ui.html
http://localhost:8080/api-docs
```

## Authentication

All `/api/v1/**` endpoints require JWT authentication unless the backend security config is changed.

Send:

```http
Authorization: Bearer <jwt>
```

Admin and exception mutation endpoints should also send:

```http
X-Actor: <user-or-admin-identifier>
```

Public endpoints:

```text
POST /webhooks/razorpay
POST /webhooks/stripe
GET  /actuator/health
GET  /actuator/prometheus
GET  /swagger-ui.html
GET  /api-docs/**
```

Current backend note: there is JWT validation support, but no frontend login/token-issue endpoint is implemented in this codebase. The frontend needs a JWT from whichever auth system issues it.

## Common Formats

Dates and timestamps:

```text
OffsetDateTime: 2026-05-17T10:30:00Z
OffsetDateTime with offset: 2026-05-17T16:00:00+05:30
LocalDate: 2026-05-17
```

Money amounts:

```text
All amount fields are integer minor units.
INR example: 10000 = Rs. 100.00
USD example: 2000 = $20.00
```

Pagination response pattern:

```json
{
  "items": [],
  "page": 0,
  "limit": 20,
  "total": 145
}
```

Errors from the global handler:

```json
{
  "error": "INTERNAL_ERROR",
  "message": "An unexpected error occurred",
  "timestamp": "2026-05-17T10:30:00Z"
}
```

Known error responses:

| Status | Body | When |
|---|---|---|
| `400` | `"Invalid signature"` | Invalid webhook signature |
| `400` | `{ "error": "SIGNATURE_INVALID", ... }` | Signature verification exception |
| `409` | `{ "error": "DUPLICATE_EVENT", ... }` | Duplicate webhook event |
| `500` | `{ "error": "INTERNAL_ERROR", ... }` | Unhandled error, including missing records in some current endpoints |

No CORS configuration is currently defined in `WebConfig`, so the frontend may need backend CORS setup before browser calls work from a different origin.

## Enum Values

Provider strings:

```text
razorpay
stripe
```

The API filters compare providers case-insensitively. Stored values may appear as lowercase provider names.

Transaction status:

```text
AUTHORIZED
CAPTURED
FAILED
REFUNDED
PARTIALLY_REFUNDED
DISPUTED
CANCELLED
PENDING
PENDING_SETTLEMENT
```

Transaction event type:

```text
PAYMENT
REFUND
CHARGEBACK
ADJUSTMENT
```

Reconciliation status:

```text
PENDING
PENDING_SETTLEMENT
MATCHED
EXCEPTION
MANUALLY_RESOLVED
IGNORED
```

Settlement status:

```text
PENDING
SETTLED
MATCHED_TO_BANK
DISCREPANT
ON_HOLD
```

Exception type:

```text
UNMATCHED_PAYMENT
UNMATCHED_REFUND
AMOUNT_MISMATCH
MISSING_CAPTURE
DUPLICATE_CAPTURE
SETTLEMENT_DISCREPANCY
ORPHAN_REFUND
STATUS_MISMATCH
FEE_DISCREPANCY
```

Exception status:

```text
OPEN
IN_REVIEW
RESOLVED
IGNORED
```

Severity:

```text
LOW
MEDIUM
HIGH
CRITICAL
```

## Shared Object Shapes

### Transaction

```json
{
  "id": 1,
  "provider": "razorpay",
  "providerTransactionId": "pay_RZP_CAP_001",
  "providerEventId": "payment.captured:pay_RZP_CAP_001",
  "merchantId": "merchant_001",
  "orderId": "MO-1001",
  "providerOrderId": "order_RZP_001",
  "eventType": "PAYMENT",
  "status": "CAPTURED",
  "parentTransactionId": null,
  "presentmentAmount": 10000,
  "presentmentCurrency": "INR",
  "settlementAmount": 10000,
  "settlementCurrency": "INR",
  "feeAmount": 236,
  "taxAmount": 36,
  "netAmount": 9728,
  "eventOccurredAt": "2024-03-09T16:10:00Z",
  "capturedAt": "2024-03-09T16:10:00Z",
  "refundedAt": null,
  "ingestedAt": "2026-05-17T10:30:00Z",
  "settlementId": "setl_123",
  "settlementDate": "2024-03-10",
  "utrNumber": "UTR123456",
  "paymentMethod": "upi",
  "paymentMethodDetail": null,
  "cardLast4": null,
  "cardNetwork": null,
  "bank": null,
  "vpa": "customer@upi",
  "userId": 10,
  "payerEmail": "payer@example.com",
  "payerPhone": "9999999999",
  "payerName": "Test User",
  "reconciliationStatus": "MATCHED",
  "matchedAt": "2026-05-17T10:35:00Z",
  "exceptionId": null,
  "rawPayload": {},
  "notes": {},
  "createdAt": "2026-05-17T10:30:00Z",
  "updatedAt": "2026-05-17T10:35:00Z"
}
```

### Settlement

```json
{
  "id": 1,
  "provider": "stripe",
  "providerSettlementId": "po_STRIPE_001",
  "merchantId": "merchant_001",
  "grossAmount": 150000,
  "totalFees": 2500,
  "totalTax": 0,
  "netAmount": 147500,
  "currency": "USD",
  "bankCreditAmount": 147500,
  "bankCreditDate": "2024-03-10",
  "utrNumber": "UTR123456",
  "settlementStatus": "MATCHED_TO_BANK",
  "transactionCount": 25,
  "settledAt": "2024-03-10T00:00:00Z",
  "createdAt": "2026-05-17T10:30:00Z",
  "updatedAt": "2026-05-17T10:35:00Z"
}
```

### Exception Record

```json
{
  "id": 1,
  "merchantId": "merchant_001",
  "exceptionType": "AMOUNT_MISMATCH",
  "severity": "HIGH",
  "transactionId": 12,
  "settlementId": null,
  "expectedAmount": 10000,
  "actualAmount": 9900,
  "discrepancyAmount": -100,
  "currency": "INR",
  "description": "Expected amount does not match provider amount.",
  "status": "OPEN",
  "resolvedBy": null,
  "resolvedAt": null,
  "resolutionNotes": null,
  "detectedAt": "2026-05-17T10:30:00Z",
  "updatedAt": "2026-05-17T10:30:00Z"
}
```

### Audit Log

```json
{
  "id": 1,
  "actor": "ops@example.com",
  "action": "exception_status_updated",
  "entityType": "exception_record",
  "entityId": 1,
  "oldValue": {},
  "newValue": {},
  "ipAddress": "127.0.0.1",
  "createdAt": "2026-05-17T10:30:00Z"
}
```

## Dashboard Endpoints

### GET `/api/v1/dashboard/summary`

Use this for top-level dashboard cards, provider summary, exception charts, and recent exception widgets.

Query params:

| Name | Type | Required | Default | Notes |
|---|---|---:|---:|---|
| `days` | number | No | `7` | Look-back window from current backend time |

Example:

```http
GET /api/v1/dashboard/summary?days=7
Authorization: Bearer <jwt>
```

Response:

```json
{
  "days": 7,
  "totalTransactions": 120,
  "matched": 110,
  "openExceptions": 5,
  "matchRate": 91.6666666667,
  "byProvider": {
    "razorpay": {
      "total": 80,
      "exceptions": 3
    },
    "stripe": {
      "total": 40,
      "exceptions": 2
    }
  },
  "byExceptionType": {
    "AMOUNT_MISMATCH": 2,
    "MISSING_CAPTURE": 3
  },
  "recentExceptions": [
    {
      "id": 1,
      "type": "AMOUNT_MISMATCH",
      "severity": "HIGH",
      "status": "OPEN"
    }
  ]
}
```

### GET `/api/v1/dashboard/metrics`

Use this for lightweight health/status counters.

```http
GET /api/v1/dashboard/metrics
Authorization: Bearer <jwt>
```

Response:

```json
{
  "transactionsProcessed": 120,
  "openExceptions": 5,
  "matchRate": 91.6666666667,
  "webhookQueueDepth": 0,
  "status": "ok"
}
```

## Transaction Endpoints

### GET `/api/v1/transactions`

Use this for the transactions table.

Query params:

| Name | Type | Required | Default | Notes |
|---|---|---:|---:|---|
| `provider` | string | No | - | `razorpay` or `stripe` |
| `status` | string | No | - | Transaction status enum |
| `orderId` | string | No | - | Exact match, case-insensitive |
| `payerEmail` | string | No | - | Exact match, case-insensitive |
| `dateFrom` | OffsetDateTime | No | - | Filters `eventOccurredAt >= dateFrom` |
| `dateTo` | OffsetDateTime | No | - | Filters `eventOccurredAt <= dateTo` |
| `page` | number | No | `0` | Zero-based |
| `limit` | number | No | `20` | Minimum coerced to `1` |

Example:

```http
GET /api/v1/transactions?provider=razorpay&status=CAPTURED&page=0&limit=20
Authorization: Bearer <jwt>
```

Response:

```json
{
  "items": [
    {
      "id": 1,
      "provider": "razorpay",
      "providerTransactionId": "pay_RZP_CAP_001",
      "providerEventId": "payment.captured:pay_RZP_CAP_001",
      "merchantId": "merchant_001",
      "orderId": "MO-1001",
      "providerOrderId": "order_RZP_001",
      "eventType": "PAYMENT",
      "status": "CAPTURED",
      "parentTransactionId": null,
      "presentmentAmount": 10000,
      "presentmentCurrency": "INR",
      "settlementAmount": 10000,
      "settlementCurrency": "INR",
      "feeAmount": 236,
      "taxAmount": 36,
      "netAmount": 9728,
      "eventOccurredAt": "2024-03-09T16:10:00Z",
      "capturedAt": "2024-03-09T16:10:00Z",
      "refundedAt": null,
      "ingestedAt": "2026-05-17T10:30:00Z",
      "settlementId": "setl_123",
      "settlementDate": "2024-03-10",
      "utrNumber": null,
      "paymentMethod": "upi",
      "paymentMethodDetail": null,
      "cardLast4": null,
      "cardNetwork": null,
      "bank": null,
      "vpa": "customer@upi",
      "userId": 10,
      "payerEmail": null,
      "payerPhone": null,
      "payerName": null,
      "reconciliationStatus": "PENDING_SETTLEMENT",
      "matchedAt": null,
      "exceptionId": null,
      "rawPayload": {},
      "notes": {},
      "createdAt": "2026-05-17T10:30:00Z",
      "updatedAt": "2026-05-17T10:30:00Z"
    }
  ],
  "page": 0,
  "limit": 20,
  "total": 1
}
```

### GET `/api/v1/transactions/{id}`

Use this for transaction detail pages and refund/parent-child views.

```http
GET /api/v1/transactions/1
Authorization: Bearer <jwt>
```

Response:

```json
{
  "transaction": {
    "id": 1,
    "provider": "razorpay",
    "providerTransactionId": "pay_RZP_CAP_001",
    "eventType": "PAYMENT",
    "status": "CAPTURED",
    "presentmentAmount": 10000,
    "presentmentCurrency": "INR",
    "reconciliationStatus": "MATCHED"
  },
  "relatedTransactions": [
    {
      "id": 2,
      "provider": "razorpay",
      "providerTransactionId": "rfnd_RZP_001",
      "eventType": "REFUND",
      "status": "REFUNDED",
      "parentTransactionId": 1
    }
  ]
}
```

`transaction` and `relatedTransactions[]` use the full Transaction shape.

## Settlement Endpoints

### GET `/api/v1/settlements`

Use this for the settlements table.

Query params:

| Name | Type | Required | Default | Notes |
|---|---|---:|---:|---|
| `provider` | string | No | - | `razorpay` or `stripe` |
| `status` | string | No | - | Settlement status enum |
| `dateFrom` | LocalDate | No | - | Filters `bankCreditDate >= dateFrom` |
| `dateTo` | LocalDate | No | - | Filters `bankCreditDate <= dateTo` |
| `page` | number | No | `0` | Zero-based |
| `limit` | number | No | `20` | Minimum coerced to `1` |

Example:

```http
GET /api/v1/settlements?provider=stripe&status=MATCHED_TO_BANK&page=0&limit=20
Authorization: Bearer <jwt>
```

Response:

```json
{
  "items": [
    {
      "id": 1,
      "provider": "stripe",
      "providerSettlementId": "po_STRIPE_001",
      "merchantId": "merchant_001",
      "grossAmount": 150000,
      "totalFees": 2500,
      "totalTax": 0,
      "netAmount": 147500,
      "currency": "USD",
      "bankCreditAmount": 147500,
      "bankCreditDate": "2024-03-10",
      "utrNumber": "UTR123456",
      "settlementStatus": "MATCHED_TO_BANK",
      "transactionCount": 25,
      "settledAt": "2024-03-10T00:00:00Z",
      "createdAt": "2026-05-17T10:30:00Z",
      "updatedAt": "2026-05-17T10:35:00Z"
    }
  ],
  "page": 0,
  "limit": 20,
  "total": 1
}
```

### GET `/api/v1/settlements/{id}`

Use this for settlement detail summary cards.

```http
GET /api/v1/settlements/1
Authorization: Bearer <jwt>
```

Response:

```json
{
  "settlement": {
    "id": 1,
    "provider": "stripe",
    "providerSettlementId": "po_STRIPE_001",
    "settlementStatus": "MATCHED_TO_BANK",
    "netAmount": 147500,
    "currency": "USD"
  },
  "linkedTransactionCount": 25,
  "transactionsNetAmount": 147500
}
```

`settlement` uses the full Settlement shape.

### GET `/api/v1/settlements/{id}/transactions`

Use this for the transactions included in a settlement.

```http
GET /api/v1/settlements/1/transactions
Authorization: Bearer <jwt>
```

Response:

```json
[
  {
    "id": 1,
    "provider": "stripe",
    "providerTransactionId": "ch_STRIPE_001",
    "eventType": "PAYMENT",
    "status": "CAPTURED",
    "settlementId": "po_STRIPE_001",
    "netAmount": 147500
  }
]
```

Each item uses the full Transaction shape.

## Exception Endpoints

### GET `/api/v1/exceptions`

Use this for exception queues, review pages, and exception status tabs.

Query params:

| Name | Type | Required | Default | Notes |
|---|---|---:|---:|---|
| `days` | number | No | `7` | Look-back window from current backend time |
| `status` | string | No | - | Exception status enum |
| `provider` | string | No | - | Matches provider through linked transaction |
| `type` | string | No | - | Exception type enum |
| `page` | number | No | `0` | Zero-based |
| `limit` | number | No | `50` | Minimum coerced to `1` |

Example:

```http
GET /api/v1/exceptions?status=OPEN&type=AMOUNT_MISMATCH&page=0&limit=50
Authorization: Bearer <jwt>
```

Response:

```json
{
  "summary": {
    "open": 4,
    "inReview": 1,
    "resolved": 8
  },
  "items": [
    {
      "id": 1,
      "merchantId": "merchant_001",
      "exceptionType": "AMOUNT_MISMATCH",
      "severity": "HIGH",
      "transactionId": 12,
      "settlementId": null,
      "expectedAmount": 10000,
      "actualAmount": 9900,
      "discrepancyAmount": -100,
      "currency": "INR",
      "description": "Expected amount does not match provider amount.",
      "status": "OPEN",
      "resolvedBy": null,
      "resolvedAt": null,
      "resolutionNotes": null,
      "detectedAt": "2026-05-17T10:30:00Z",
      "updatedAt": "2026-05-17T10:30:00Z"
    }
  ],
  "page": 0,
  "limit": 50,
  "total": 1
}
```

### GET `/api/v1/exceptions/{id}`

Use this for exception detail pages.

```http
GET /api/v1/exceptions/1
Authorization: Bearer <jwt>
```

Response:

```json
{
  "exception": {
    "id": 1,
    "exceptionType": "AMOUNT_MISMATCH",
    "severity": "HIGH",
    "status": "OPEN",
    "transactionId": 12
  },
  "transaction": {
    "id": 12,
    "provider": "razorpay",
    "providerTransactionId": "pay_RZP_CAP_001",
    "status": "CAPTURED"
  },
  "auditLogs": [
    {
      "id": 1,
      "actor": "ops@example.com",
      "action": "exception_status_updated",
      "entityType": "exception_record",
      "entityId": 1,
      "oldValue": {},
      "newValue": {},
      "ipAddress": "127.0.0.1",
      "createdAt": "2026-05-17T10:30:00Z"
    }
  ]
}
```

`exception` uses the full Exception Record shape. `transaction` is nullable and uses the full Transaction shape when present.

### PATCH `/api/v1/exceptions/{id}`

Use this for marking an exception in review, resolved, or ignored.

Headers:

```http
Authorization: Bearer <jwt>
X-Actor: ops@example.com
Content-Type: application/json
```

Request body:

```json
{
  "status": "RESOLVED",
  "notes": "Confirmed manually against bank statement."
}
```

Allowed `status` values:

```text
OPEN
IN_REVIEW
RESOLVED
IGNORED
```

Response:

```json
{
  "id": 1,
  "merchantId": "merchant_001",
  "exceptionType": "AMOUNT_MISMATCH",
  "severity": "HIGH",
  "transactionId": 12,
  "settlementId": null,
  "expectedAmount": 10000,
  "actualAmount": 9900,
  "discrepancyAmount": -100,
  "currency": "INR",
  "description": "Expected amount does not match provider amount.",
  "status": "RESOLVED",
  "resolvedBy": "ops@example.com",
  "resolvedAt": "2026-05-17T10:35:00Z",
  "resolutionNotes": "Confirmed manually against bank statement.",
  "detectedAt": "2026-05-17T10:30:00Z",
  "updatedAt": "2026-05-17T10:35:00Z"
}
```

## Admin Endpoints

These endpoints require `Authorization` and should send `X-Actor`.

### POST `/api/v1/admin/poll`

Use this from an admin/back-office screen to backfill provider events for a time window.

Request body:

```json
{
  "provider": "razorpay",
  "from": "2026-05-17T00:00:00Z",
  "to": "2026-05-17T23:59:59Z"
}
```

Response:

```json
{
  "status": "accepted",
  "provider": "razorpay",
  "from": "2026-05-17T00:00:00Z",
  "to": "2026-05-17T23:59:59Z",
  "fetched": 12
}
```

### POST `/api/v1/admin/replay`

Use this to replay a stored webhook event by backend webhook event ID.

Request body:

```json
{
  "webhookEventId": 123
}
```

Response:

```json
{
  "status": "queued",
  "webhookEventId": 123,
  "provider": "stripe",
  "eventType": "charge.succeeded"
}
```

### POST `/api/v1/admin/settlement-reconciler/run`

Use this to manually trigger the settlement reconciliation job.

Request body:

```json
{}
```

The controller does not require a body. Sending an empty body is fine.

Response:

```json
{
  "closedAsMatched": 10,
  "remainedDiscrepant": 2,
  "overdueSettlementsFlagged": 1
}
```

### GET `/api/v1/admin/audit-logs`

Use this for admin audit history.

Query params:

| Name | Type | Required | Notes |
|---|---|---:|---|
| `entityType` | string | No | Example: `exception_record`, `webhook_event`, `provider`, `settlement` |
| `entityId` | number | No | Backend database ID |
| `actor` | string | No | Exact match, case-insensitive |

Example:

```http
GET /api/v1/admin/audit-logs?entityType=exception_record&entityId=1
Authorization: Bearer <jwt>
```

Response:

```json
{
  "items": [
    {
      "id": 1,
      "actor": "ops@example.com",
      "action": "exception_status_updated",
      "entityType": "exception_record",
      "entityId": 1,
      "oldValue": {
        "status": "OPEN"
      },
      "newValue": {
        "status": "RESOLVED"
      },
      "ipAddress": "127.0.0.1",
      "createdAt": "2026-05-17T10:35:00Z"
    }
  ]
}
```

## Webhook Endpoints

These are provider-to-backend endpoints and are usually not called by the frontend. They are listed here only so the frontend team knows they exist.

### POST `/webhooks/razorpay`

Headers:

```http
X-Razorpay-Signature: <signature>
Content-Type: application/json
```

Body: raw Razorpay webhook payload.

Success response:

```text
received
```

Invalid signature response:

```text
Invalid signature
```

### POST `/webhooks/stripe`

Headers:

```http
Stripe-Signature: <signature>
Content-Type: application/json
```

Body: raw Stripe webhook payload.

Success response:

```text
received
```

Invalid signature response:

```text
Invalid signature
```

## Frontend Integration Checklist

- Configure `API_BASE_URL`, defaulting locally to `http://localhost:8080`.
- Attach `Authorization: Bearer <jwt>` to all `/api/v1/**` calls.
- Attach `X-Actor` for admin actions and exception status updates.
- Treat all amount fields as minor units and format in the UI based on currency.
- Use `dateFrom` and `dateTo` for transaction timestamp filters.
- Use `dateFrom` and `dateTo` as `YYYY-MM-DD` for settlement bank credit date filters.
- Handle nullable fields across payment method, settlement, payer, and resolution data.
- Use zero-based pagination.
- Expect list responses to include `items`, `page`, `limit`, and `total`.
- Expect exception list responses to additionally include `summary`.
- Do not call webhook endpoints from browser UI.
- Ask backend to enable CORS if frontend and backend run on different origins.
