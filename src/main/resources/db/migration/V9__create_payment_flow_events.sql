CREATE TABLE payment_flow_events (
    id                      BIGSERIAL PRIMARY KEY,
    provider                VARCHAR(30) NOT NULL,
    provider_event_id       VARCHAR(120),
    provider_transaction_id VARCHAR(120),
    webhook_event_id        BIGINT,
    user_id                 BIGINT,
    source                  VARCHAR(30),
    step                    VARCHAR(60) NOT NULL,
    status                  VARCHAR(30) NOT NULL,
    message                 TEXT,
    metadata                JSONB,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_payment_flow_provider_txn
    ON payment_flow_events (provider, provider_transaction_id, created_at DESC);

CREATE INDEX idx_payment_flow_webhook_event
    ON payment_flow_events (webhook_event_id, created_at DESC);

CREATE INDEX idx_payment_flow_user
    ON payment_flow_events (user_id, created_at DESC);
