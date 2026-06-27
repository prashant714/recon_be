-- OMS source tracking on orders
ALTER TABLE orders
    ADD COLUMN oms_provider VARCHAR(30),
    ADD COLUMN oms_order_status VARCHAR(60),
    ADD COLUMN oms_synced_at TIMESTAMPTZ,
    ADD COLUMN oms_raw_payload JSONB;

CREATE INDEX idx_orders_oms_provider ON orders (oms_provider, oms_synced_at)
    WHERE oms_provider IS NOT NULL;

-- OAuth2 token storage and provider type on connections
ALTER TABLE provider_connections
    ADD COLUMN refresh_token_encrypted TEXT,
    ADD COLUMN access_token_encrypted TEXT,
    ADD COLUMN token_expires_at TIMESTAMPTZ,
    ADD COLUMN organization_id VARCHAR(120),
    ADD COLUMN provider_type VARCHAR(20) NOT NULL DEFAULT 'PAYMENT';

ALTER TABLE provider_connections
    ALTER COLUMN api_key_encrypted DROP NOT NULL,
    ALTER COLUMN secret_encrypted DROP NOT NULL,
    ALTER COLUMN api_key_masked DROP NOT NULL;

CREATE INDEX idx_provider_connections_type ON provider_connections (provider_type, status);
