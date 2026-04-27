# Reconciliation Platform: Flow + File Guide

This guide explains the codebase in a learning-first way.
It is designed to help you understand:
- the end-to-end runtime flow
- why each module exists
- what each major file is responsible for
- advanced backend concepts used in this project

## 1) Big Picture

This service ingests payment events (webhooks now, polling later), normalizes provider payloads into a single internal transaction model, stores/upserts data idempotently, then runs reconciliation rules to classify transactions as matched or exceptions.

Core subsystems:
1. Ingestion: verify signature, store event, dispatch async processing
2. Processing: normalize event -> transaction -> user linking -> refund parent linking -> upsert
3. Reconciliation: periodic rule engine to detect and classify anomalies
4. Query/Operations: dashboard, exception management, admin actions, audit logs
5. Cross-cutting: security, rate limiting, async execution, metrics, encryption support

## 2) End-to-End Runtime Flow

### A) Webhook request flow
1. Provider hits webhook endpoint (`/webhooks/razorpay` or `/webhooks/stripe`)
2. Signature service verifies authenticity
3. `WebhookIngestionService` stores raw event as `WebhookEvent` (idempotent insert)
4. Async `TransactionProcessingService.processAsync(...)` is triggered
5. Processing chooses normalization method by `provider + eventType`
6. Transaction is enriched (user identity + refund parent link)
7. Transaction is upserted with out-of-order guard
8. Event marked processed (or failed with error)

### B) Reconciliation flow
1. `ReconciliationJob` runs every 5 minutes (db-scheduler)
2. `ReconciliationEngine` discovers all `ReconciliationRule` beans
3. Rules run sequentially; one failure does not block others
4. Rules update transaction reconciliation status and create exception records

### C) Gap-fill / polling flow (partially wired)
- `GapFillerJob` is scheduled and designed to fetch missed provider events,
  wrap them as webhook-like payloads, and pass to `WebhookIngestionService`.
- Polling is intentionally left partial right now (as discussed).

## 3) Module-by-Module File Guide

## 3.1 Application Bootstrap

- `src/main/java/com/reconciliation/ReconciliationApplication.java`
  - Spring Boot entrypoint.
  - Enables transaction management.

## 3.2 Config Layer

### Async and Scheduler
- `src/main/java/com/reconciliation/config/AsyncConfig.java`
  - Defines `webhookProcessingExecutor` thread pool used by async processing.
- `src/main/java/com/reconciliation/config/SchedulerConfig.java`
  - db-scheduler integration config.

### Security and Auth
- `src/main/java/com/reconciliation/config/SecurityConfig.java`
  - Spring Security filter chain.
  - Permits webhook/health/swagger routes; authenticates other routes.
  - Injects `JwtFilter` before username/password filter.
- `src/main/java/com/reconciliation/config/JwtFilter.java`
  - Extracts `Authorization: Bearer ...`, validates token, sets auth context.
- `src/main/java/com/reconciliation/config/JwtConfig.java`
  - JWT generation/validation/parsing utilities.

### Web and Serialization
- `src/main/java/com/reconciliation/config/JacksonConfig.java`
  - ObjectMapper customizations (if any).
- `src/main/java/com/reconciliation/config/WebConfig.java`
  - MVC/web-level customization hook.

### Properties and merchants
- `src/main/java/com/reconciliation/config/MerchantConfig.java`
- `src/main/java/com/reconciliation/config/MerchantProperties.java`
  - Typed property binding for merchant config.

### Rate limiting (recently added)
- `src/main/java/com/reconciliation/config/RateLimitConfig.java`
  - Servlet filter using Bucket4j.
  - Limits `/webhooks/**` by IP.

## 3.3 Common Package

### Enums
- `src/main/java/com/reconciliation/common/enums/*`
  - Shared domain enums (provider, event type, status families, severity).

### Exceptions
- `src/main/java/com/reconciliation/common/exception/*`
  - Domain and API exception types + global exception handler.

### Utilities
- `src/main/java/com/reconciliation/common/util/AmountUtils.java`
- `src/main/java/com/reconciliation/common/util/CurrencyUtils.java`
- `src/main/java/com/reconciliation/common/util/TimestampUtils.java`
- `src/main/java/com/reconciliation/common/util/EncryptionService.java`
  - `EncryptionService` provides AES-256-GCM encrypt/decrypt helpers for sensitive fields.
  - Currently support is present and ready to be wired deeper where needed.

## 3.4 Webhook Layer

### Controllers
- `src/main/java/com/reconciliation/webhook/controller/RazorpayWebhookController.java`
- `src/main/java/com/reconciliation/webhook/controller/StripeWebhookController.java`
  - Read raw body.
  - Verify provider signature.
  - Call `WebhookIngestionService.ingestAsync(...)`.

### Signature services
- `src/main/java/com/reconciliation/webhook/service/RazorpaySignatureService.java`
- `src/main/java/com/reconciliation/webhook/service/StripeSignatureService.java`
  - HMAC verification logic per provider requirements.

### Ingestion and processing
- `src/main/java/com/reconciliation/webhook/service/WebhookIngestionService.java`
  - Parses raw payload.
  - Extracts provider event id + event type.
  - Stores `WebhookEvent` (duplicate-safe).
  - Triggers async transaction processing.
- `src/main/java/com/reconciliation/webhook/service/TransactionProcessingService.java`
  - Loads persisted raw event.
  - Routes to normalization method.
  - Resolves user identity.
  - Links refunds to parent payments.
  - Upserts transaction.
  - Marks event processed/failed.

## 3.5 Webhook Event Persistence

- `src/main/java/com/reconciliation/webhook_event/entity/WebhookEvent.java`
  - Persistent record for raw provider events + processing status.
- `src/main/java/com/reconciliation/webhook_event/repository/WebhookEventRepository.java`
  - CRUD + helper update queries for processed/failed flags.

## 3.6 Transaction Domain

### Entity + repository
- `src/main/java/com/reconciliation/transaction/entity/Transaction.java`
  - Canonical internal transaction model across providers.
- `src/main/java/com/reconciliation/transaction/repository/TransactionRepository.java`
  - Query methods used by processing, reconciliation, dashboard.
  - Includes aggregate methods for provider summary + counts.

### Services
- `src/main/java/com/reconciliation/transaction/service/NormalizationService.java`
  - Provider-specific payload mapping into canonical `Transaction`.
  - Contains event-specific normalizers.
- `src/main/java/com/reconciliation/transaction/service/TransactionService.java`
  - Upsert logic with timestamp guard.
  - Refund-to-parent linking.
- `src/main/java/com/reconciliation/transaction/service/TransactionQueryService.java`
  - Read-side filtering/detail APIs.

### API controller
- `src/main/java/com/reconciliation/transaction/controller/TransactionController.java`
  - List/detail read endpoints.

## 3.7 User Identity Domain

- `src/main/java/com/reconciliation/user/entity/User.java`
- `src/main/java/com/reconciliation/user/repository/UserRepository.java`
- `src/main/java/com/reconciliation/user/service/UserIdentityService.java`
  - Resolves a stable user identity from payer email/phone.
  - Handles race-prone creation with transaction boundaries.
  - Maintains aggregate counters.

## 3.8 Settlement Domain

- `src/main/java/com/reconciliation/settlement/entity/Settlement.java`
- `src/main/java/com/reconciliation/settlement/repository/SettlementRepository.java`
- `src/main/java/com/reconciliation/settlement/service/SettlementService.java`
- `src/main/java/com/reconciliation/settlement/controller/SettlementController.java`
  - Settlement listing/detail/linked transactions.

## 3.9 Exception Tracking Domain

- `src/main/java/com/reconciliation/exception_record/entity/ExceptionRecord.java`
- `src/main/java/com/reconciliation/exception_record/repository/ExceptionRecordRepository.java`
- `src/main/java/com/reconciliation/exception_record/service/ExceptionRecordService.java`
- `src/main/java/com/reconciliation/exception_record/service/ExceptionQueryService.java`
- `src/main/java/com/reconciliation/exception_record/controller/ExceptionController.java`
  - Exception lifecycle (open/review/resolved/ignored).
  - Query endpoints with status/time filters.

## 3.10 Reconciliation Engine + Rules

### Engine and matching
- `src/main/java/com/reconciliation/reconciliation/service/ReconciliationEngine.java`
  - Rule orchestrator.
- `src/main/java/com/reconciliation/reconciliation/service/MatchingService.java`
  - Matching-related helper logic.

### Rule contract
- `src/main/java/com/reconciliation/reconciliation/rules/ReconciliationRule.java`
  - Interface each rule implements.

### Rule implementations
- `src/main/java/com/reconciliation/reconciliation/rules/ExactIdMatchRule.java`
  - Promotes reconciled transactions when order linkage exists.
- `src/main/java/com/reconciliation/reconciliation/rules/MissingCaptureRule.java`
  - Flags authorizations that never became captures in threshold window.
- `src/main/java/com/reconciliation/reconciliation/rules/OrphanRefundRule.java`
  - Flags refunds that cannot be linked to a payment.
- `src/main/java/com/reconciliation/reconciliation/rules/DuplicateCaptureRule.java`
  - Detects duplicate captured payments per order.
- `src/main/java/com/reconciliation/reconciliation/rules/SettlementTotalRule.java`
  - Validates settlement totals against transaction sums.

### Scheduled jobs
- `src/main/java/com/reconciliation/reconciliation/job/ReconciliationJob.java`
  - Periodic reconciliation run.
- `src/main/java/com/reconciliation/reconciliation/job/GapFillerJob.java`
  - Polling-based recovery for webhook misses (partially wired).
- `src/main/java/com/reconciliation/reconciliation/job/SettlementReconcilerJob.java`
  - Settlement-level periodic checks.

## 3.11 Polling Layer (Partially Implemented)

- `src/main/java/com/reconciliation/polling/service/RazorpayPollingService.java`
- `src/main/java/com/reconciliation/polling/service/StripePollingService.java`
  - Build synthetic webhook-like envelopes from polled data.
- `src/main/java/com/reconciliation/polling/client/RazorpayApiClient.java`
- `src/main/java/com/reconciliation/polling/client/StripeApiClient.java`
  - Placeholder client wrappers (currently minimal).

## 3.12 Dashboard / Admin / Audit

### Dashboard
- `src/main/java/com/reconciliation/dashboard/controller/DashboardController.java`
- `src/main/java/com/reconciliation/dashboard/service/DashboardService.java`
  - Summary and metrics endpoints.
  - Uses DB-level aggregate queries for better scalability.

### Admin
- `src/main/java/com/reconciliation/admin/controller/AdminController.java`
- `src/main/java/com/reconciliation/admin/service/AdminService.java`
  - Replay endpoint: reset+requeue webhook event processing.
  - Poll endpoint exists, but business behavior is intentionally stubbed now.

### Audit
- `src/main/java/com/reconciliation/audit/entity/AuditLog.java`
- `src/main/java/com/reconciliation/audit/repository/AuditLogRepository.java`
- `src/main/java/com/reconciliation/audit/service/AuditService.java`
  - Immutable operational trace for sensitive actions.

## 4) Advanced Concepts Used (with Context)

### 4.1 Idempotency at ingestion boundary
Why: Providers retry webhooks; duplicates are normal.
How here:
- Event-level uniqueness via `(provider, provider_event_id)` persistence constraint.
- `WebhookIngestionService` catches `DataIntegrityViolationException` and ignores duplicates.

### 4.2 Out-of-order event handling
Why: `payment.captured` may arrive before/after related updates due to network timing.
How here:
- `TransactionService.upsert(...)` only applies updates if incoming `eventOccurredAt` is newer.
- Prevents stale events from rolling back newer state.

### 4.3 Canonical data model / anti-corruption layer
Why: Razorpay and Stripe payloads are structurally different.
How here:
- `NormalizationService` maps provider-specific fields into one internal `Transaction` schema.
- Business logic downstream remains provider-agnostic.

### 4.4 Async handoff for webhook responsiveness
Why: Provider webhooks should return quickly; heavy processing causes timeouts/retries.
How here:
- Controller validates signature and delegates.
- `WebhookIngestionService` persists then calls async `processAsync(...)`.
- Processing uses named executor from `AsyncConfig`.

### 4.5 Rule engine as composable strategy pattern
Why: reconciliation checks evolve independently.
How here:
- `ReconciliationRule` interface + multiple beans.
- `ReconciliationEngine` auto-runs all rule beans.
- Failure isolation: one rule exception does not stop others.

### 4.6 Exception-driven reconciliation state machine
Why: unmatched scenarios need an explicit operational queue.
How here:
- Rules create `ExceptionRecord` and set `transaction.reconciliationStatus = EXCEPTION`.
- Exception lifecycle is exposed via exception APIs for operations teams.

### 4.7 Eventual consistency by design
Why: full reconciliation depends on later events (settlement, refunds, disputes).
How here:
- Transactions can stay in `PENDING_SETTLEMENT` / `PENDING` until later jobs/events arrive.
- Scheduled jobs progressively close gaps.

### 4.8 Security layering
Why: external write endpoints and internal ops endpoints have different trust boundaries.
How here:
- Webhook signature verification for authenticity.
- JWT filter for authenticated API access.
- Rate limiting for webhook abuse/flood resistance.

### 4.9 Metrics and observability
Why: reconciliation systems fail silently without visibility.
How here:
- Micrometer counters/timers in engine and jobs.
- Actuator + Prometheus endpoints configured in app properties.

## 5) Data Model Relationships (Conceptual)

- `WebhookEvent` -> drives -> `Transaction` creation/update
- `Transaction` may reference:
  - `userId` (resolved identity)
  - `parentTransactionId` (refund to payment link)
  - `exceptionId` (current unresolved exception)
  - settlement fields (for settlement reconciliation)
- `ExceptionRecord` captures reconciliation problems, severity, and resolution metadata
- `Settlement` represents provider payout/settlement batches
- `AuditLog` records privileged operations (e.g., replay)

## 6) Where to Start Reading (Recommended Path)

If you want the fastest understanding curve, read in this order:
1. Webhook entry and ingestion
   - `webhook/controller/*`
   - `webhook/service/WebhookIngestionService.java`
2. Processing + normalization
   - `webhook/service/TransactionProcessingService.java`
   - `transaction/service/NormalizationService.java`
   - `transaction/service/TransactionService.java`
3. Reconciliation and exception creation
   - `reconciliation/service/ReconciliationEngine.java`
   - `reconciliation/rules/*`
   - `exception_record/service/ExceptionRecordService.java`
4. Ops and read APIs
   - `dashboard/*`, `admin/*`, `exception_record/controller/*`, `transaction/controller/*`
5. Cross-cutting infra
   - `config/*` and `common/*`

## 7) Current Known Implementation Notes

- Polling path is intentionally not fully implemented yet (by your choice for now).
- `AdminService.poll(...)` currently returns accepted stub responses.
- Polling client wrappers are placeholders.
- Core webhook -> normalization -> upsert -> reconciliation flow is implemented and build-valid.

## 8) Test Coverage Map (High-Level)

Main tests currently cover:
- signature verification
- webhook controller behavior
- ingestion + processing behavior
- normalization behavior
- transaction service behavior
- reconciliation engine/rules behavior
- exception record service behavior

See:
- `src/test/java/com/reconciliation/webhook/*`
- `src/test/java/com/reconciliation/transaction/*`
- `src/test/java/com/reconciliation/reconciliation/*`
- `src/test/java/com/reconciliation/exception_record/*`

## 9) Practical Learning Exercises

Use these to internalize system behavior:
1. Trace a single `payment.captured` payload from controller to final DB update.
2. Add a new reconciliation rule implementing `ReconciliationRule` and register no extra config.
3. Introduce a synthetic duplicate webhook and verify idempotent ignore behavior.
4. Send an older event timestamp and observe upsert timestamp guard behavior.
5. Replay a failed webhook from admin API and inspect `AuditLog` entry.

---

If you want, I can also generate a second companion doc with sequence diagrams (Mermaid) for:
- webhook success path
- duplicate webhook path
- refund orphan detection path
- replay flow
