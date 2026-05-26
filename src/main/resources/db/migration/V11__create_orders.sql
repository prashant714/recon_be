CREATE TABLE orders (
    id                  BIGSERIAL PRIMARY KEY,
    merchant_id         VARCHAR(60) NOT NULL,
    order_id            VARCHAR(120) NOT NULL,
    provider_order_id   VARCHAR(120),
    expected_amount     BIGINT NOT NULL,
    currency            CHAR(3) NOT NULL,
    order_status        VARCHAR(30) NOT NULL DEFAULT 'CREATED',
    transaction_id      BIGINT REFERENCES transactions(id),
    amount_matched      BOOLEAN NOT NULL DEFAULT FALSE,
    discrepancy_amount  BIGINT,
    metadata            JSONB,
    matched_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_order_merchant_order_id UNIQUE (merchant_id, order_id)
);

CREATE INDEX idx_orders_merchant_status ON orders (merchant_id, order_status, created_at DESC);
CREATE INDEX idx_orders_provider_order  ON orders (provider_order_id) WHERE provider_order_id IS NOT NULL;
CREATE INDEX idx_orders_transaction     ON orders (transaction_id) WHERE transaction_id IS NOT NULL;
CREATE INDEX idx_orders_created         ON orders (created_at DESC);
