# Reconciliation Platform Visual Diagrams

## System Architecture

```mermaid
flowchart LR
    P1["Razorpay"] --> W1["Razorpay Webhook Controller"]
    P2["Stripe"] --> W2["Stripe Webhook Controller"]

    W1 --> SIG1["Razorpay Signature Service"]
    W2 --> SIG2["Stripe Signature Service"]

    W1 --> ING["Webhook Ingestion Service"]
    W2 --> ING

    ING --> WE["Webhook Event Repository"]
    ING --> TPS["Transaction Processing Service"]

    TPS --> NORM["Normalization Service"]
    TPS --> TSVC["Transaction Service"]
    TPS --> USVC["User Identity Service"]
    TSVC --> TR["Transaction Repository"]
    USVC --> UR["User Repository"]

    JOB1["Reconciliation Job"] --> ENG["Reconciliation Engine"]
    ENG --> R1["Exact Id Match Rule"]
    ENG --> R2["Missing Capture Rule"]
    ENG --> R3["Orphan Refund Rule"]
    ENG --> R4["Duplicate Capture Rule"]
    ENG --> R5["Settlement Total Rule"]

    R1 --> EXS["Exception Record Service"]
    R2 --> EXS
    R3 --> EXS
    R4 --> EXS
    R5 --> EXS
    EXS --> EXR["Exception Record Repository"]

    JOB2["Gap Filler Job"] --> RPOLL["Razorpay Polling Service"]
    JOB2 --> SPOLL["Stripe Polling Service"]
    RPOLL --> ING
    SPOLL --> ING

    API1["Dashboard Controller"] --> DS["Dashboard Service"]
    DS --> TR
    DS --> EXR

    API2["Exception Controller"] --> EXR
    API2 --> AUD["Audit Service"]
    AUD --> AUR["Audit Log Repository"]

    API3["Admin Controller"] --> ADMS["Admin Service"]
    ADMS --> WE
    ADMS --> TPS
    ADMS --> AUD
```

## Request Security Chain

```mermaid
flowchart LR
    REQ["Incoming HTTP Request"] --> RL["Rate Limit Filter"]
    RL --> JWT["Jwt Filter"]
    JWT --> SC["Security Config Authorization"]

    SC -->|"/webhooks/** permit"| WEB["Webhook Controllers"]
    SC -->|"/actuator health and prometheus permit"| ACT["Actuator Endpoints"]
    SC -->|"/swagger and /api-docs permit"| DOC["API Docs"]
    SC -->|"all other routes require auth"| API["Protected API Controllers"]
```

## Webhook Success Flow

```mermaid
sequenceDiagram
    autonumber
    participant Provider as "Provider"
    participant Controller as "Webhook Controller"
    participant Signature as "Signature Service"
    participant Ingestion as "Webhook Ingestion Service"
    participant EventRepo as "Webhook Event Repository"
    participant Processing as "Transaction Processing Service"
    participant Norm as "Normalization Service"
    participant UserSvc as "User Identity Service"
    participant TxSvc as "Transaction Service"

    Provider->>Controller: POST webhook payload and signature
    Controller->>Signature: verify payload signature
    Signature-->>Controller: valid
    Controller->>Ingestion: ingestAsync rawBody provider webhook
    Ingestion->>EventRepo: save webhook event
    EventRepo-->>Ingestion: saved webhook event id
    Ingestion->>Processing: processAsync webhookEventId provider

    Processing->>EventRepo: findById webhookEventId
    EventRepo-->>Processing: webhook event payload
    Processing->>Norm: normalize by provider and event type
    Norm-->>Processing: canonical transaction

    alt "payer details present"
        Processing->>UserSvc: resolveUserId merchantId email phone name
        UserSvc-->>Processing: userId
    end

    alt "event type refund"
        Processing->>TxSvc: linkRefundToParent transaction payload provider
    end

    Processing->>TxSvc: upsert transaction
    TxSvc-->>Processing: persisted transaction
    Processing->>EventRepo: save processed true
```

## Duplicate Webhook Flow

```mermaid
sequenceDiagram
    autonumber
    participant Provider as "Provider"
    participant Ingestion as "Webhook Ingestion Service"
    participant EventRepo as "Webhook Event Repository"

    Provider->>Ingestion: resend same event
    Ingestion->>EventRepo: save provider and providerEventId
    EventRepo-->>Ingestion: unique key violation
    Ingestion-->>Ingestion: duplicate ignored
```

## Processing Error Flow

```mermaid
sequenceDiagram
    autonumber
    participant Processing as "Transaction Processing Service"
    participant EventRepo as "Webhook Event Repository"

    Processing->>EventRepo: findById webhookEventId
    EventRepo-->>Processing: webhook event
    Processing-->>Processing: exception during normalize or upsert
    Processing->>EventRepo: save processed true and processingError
```

## Transaction Upsert Decision

```mermaid
flowchart TD
    A["Incoming Canonical Transaction"] --> B["Find Existing by provider and providerTransactionId"]
    B -->|"not found"| C["Insert New Transaction"]
    B -->|"found"| D["Compare eventOccurredAt"]
    D -->|"incoming older or equal"| E["Skip Update Return Current"]
    D -->|"incoming newer"| F["Merge Mutable Fields"]
    F --> G["Save Updated Transaction"]
```

## Reconciliation Engine Run

```mermaid
sequenceDiagram
    autonumber
    participant Job as "Reconciliation Job"
    participant Engine as "Reconciliation Engine"
    participant Rule as "Reconciliation Rule"

    Job->>Engine: runAll
    loop "for each discovered rule"
        Engine->>Rule: evaluate
        alt "rule success"
            Rule-->>Engine: completed
        else "rule failure"
            Rule-->>Engine: exception
            Engine-->>Engine: log and continue
        end
    end
```

## Rule Outcome Pattern

```mermaid
flowchart TD
    CAND["Rule Candidate Transaction"] --> CHECK["Rule Condition Check"]
    CHECK -->|"match"| MATCHED["Set ReconciliationStatus MATCHED"]
    CHECK -->|"violation"| CREATE["Create ExceptionRecord"]
    CREATE --> EXSET["Set ReconciliationStatus EXCEPTION and exceptionId"]
    MATCHED --> SAVE["Save Transaction"]
    EXSET --> SAVE
```

## Exception Lifecycle

```mermaid
stateDiagram-v2
    [*] --> OPEN
    OPEN --> IN_REVIEW: "Ops starts review"
    IN_REVIEW --> RESOLVED: "Issue fixed"
    IN_REVIEW --> IGNORED: "Accepted as non actionable"
    OPEN --> RESOLVED: "Direct resolution"
    OPEN --> IGNORED: "Direct ignore"
    RESOLVED --> [*]
    IGNORED --> [*]
```

## Admin Replay Flow

```mermaid
sequenceDiagram
    autonumber
    participant Admin as "Admin Client"
    participant Controller as "Admin Controller"
    participant Service as "Admin Service"
    participant EventRepo as "Webhook Event Repository"
    participant Processing as "Transaction Processing Service"
    participant Audit as "Audit Service"

    Admin->>Controller: POST replay with webhookEventId
    Controller->>Service: replay webhookEventId actor ip
    Service->>EventRepo: findById
    EventRepo-->>Service: webhook event
    Service->>EventRepo: save reset processing flags
    Service->>Processing: processAsync webhookEventId provider
    Service->>Audit: log webhook_replayed
    Service-->>Controller: status queued
```

## Gap Filler Flow

```mermaid
sequenceDiagram
    autonumber
    participant GapJob as "Gap Filler Job"
    participant RP as "Razorpay Polling Service"
    participant SP as "Stripe Polling Service"
    participant Ingestion as "Webhook Ingestion Service"

    GapJob->>GapJob: compute from and to window
    GapJob->>RP: fetchPayments and fetchRefunds
    RP-->>GapJob: synthetic webhook payload bytes
    loop "razorpay payloads"
        GapJob->>Ingestion: ingestAsync payload razorpay polling
    end

    GapJob->>SP: fetchCharges and fetchRefunds
    SP-->>GapJob: synthetic webhook payload bytes
    loop "stripe payloads"
        GapJob->>Ingestion: ingestAsync payload stripe polling
    end
```

## Dashboard Metrics Flow

```mermaid
flowchart LR
    DC["Dashboard Controller"] --> DS["Dashboard Service"]
    DS --> TR1["TransactionRepository countSince"]
    DS --> TR2["TransactionRepository countByReconciliationStatusSince"]
    DS --> TR3["TransactionRepository findProviderSummarySince"]
    DS --> ER1["ExceptionRecordRepository countOpenExceptions"]
    DS --> ER2["ExceptionRecordRepository countByTypeForMerchant"]
    DS --> ER3["ExceptionRecordRepository recent page"]
    DS --> RESP["Summary and Metrics JSON Response"]
```

## Data Model Relationships

```mermaid
erDiagram
    WEBHOOK_EVENTS ||--o{ TRANSACTIONS : "drives processing"
    USERS ||--o{ TRANSACTIONS : "resolved userId"
    EXCEPTION_RECORDS ||--o{ TRANSACTIONS : "linked via exceptionId"
    SETTLEMENTS ||--o{ TRANSACTIONS : "linked by settlementId"
    AUDIT_LOGS {
      bigint id
      string actor
      string action
      string entity_type
      bigint entity_id
      json old_value
      json new_value
      inet ip_address
      datetime created_at
    }
    WEBHOOK_EVENTS {
      bigint id
      string provider
      string provider_event_id
      string event_type
      json payload
      boolean processed
      datetime received_at
      datetime processed_at
      string processing_error
      string source
    }
    TRANSACTIONS {
      bigint id
      string provider
      string provider_transaction_id
      string merchant_id
      string order_id
      string provider_order_id
      bigint parent_transaction_id
      string event_type
      string status
      string reconciliation_status
      bigint user_id
      bigint exception_id
      string settlement_id
      datetime event_occurred_at
    }
    USERS {
      bigint id
      string merchant_id
      string email
      string phone
      string name
      datetime first_seen_at
      datetime last_seen_at
    }
    EXCEPTION_RECORDS {
      bigint id
      string merchant_id
      string exception_type
      string severity
      string status
      bigint transaction_id
      bigint settlement_id
      datetime detected_at
      datetime resolved_at
    }
    SETTLEMENTS {
      bigint id
      string provider
      string provider_settlement_id
      string merchant_id
      string settlement_status
      datetime settled_at
    }
```

## Operational Views

```mermaid
flowchart LR
    OPS1["Exception Controller"] --> EX["Exception Records"]
    OPS2["Transaction Controller"] --> TX["Transactions"]
    OPS3["Settlement Controller"] --> ST["Settlements"]
    OPS4["Dashboard Controller"] --> KP["Metrics and Summary"]
    OPS5["Admin Controller"] --> RP["Replay and Poll Operations"]
    RP --> AU["Audit Logs"]
```
