CREATE TABLE webhook_events (
    id                BIGSERIAL PRIMARY KEY,
    provider          VARCHAR(30) NOT NULL,
    provider_event_id VARCHAR(120) NOT NULL,
    event_type        VARCHAR(100) NOT NULL,
    received_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    payload           JSONB NOT NULL,
    signature_valid   BOOLEAN NOT NULL,
    source            VARCHAR(20) NOT NULL,
    processed         BOOLEAN NOT NULL DEFAULT FALSE,
    processed_at      TIMESTAMPTZ,
    processing_error  TEXT,
    CONSTRAINT uq_webhook_provider_event UNIQUE (provider, provider_event_id)
);

CREATE INDEX idx_webhook_unprocessed
    ON webhook_events (processed, received_at)
    WHERE processed = FALSE;

CREATE INDEX idx_webhook_provider_event
    ON webhook_events (provider, provider_event_id);
