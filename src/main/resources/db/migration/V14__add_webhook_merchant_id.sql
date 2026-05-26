ALTER TABLE webhook_events
    ADD COLUMN merchant_id VARCHAR(60);

CREATE INDEX idx_webhook_merchant
    ON webhook_events (merchant_id, received_at DESC)
    WHERE merchant_id IS NOT NULL;
