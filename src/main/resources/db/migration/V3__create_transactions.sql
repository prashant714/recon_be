CREATE TABLE transactions (
    id                      BIGSERIAL PRIMARY KEY,
    provider                VARCHAR(30) NOT NULL,
    provider_transaction_id VARCHAR(120) NOT NULL,
    provider_event_id       VARCHAR(120),
    merchant_id             VARCHAR(60) NOT NULL,
    order_id                VARCHAR(120),
    provider_order_id       VARCHAR(120),
    event_type              VARCHAR(40) NOT NULL,
    status                  VARCHAR(40) NOT NULL,
    parent_transaction_id   BIGINT REFERENCES transactions(id),
    presentment_amount      BIGINT NOT NULL,
    presentment_currency    CHAR(3) NOT NULL,
    settlement_amount       BIGINT,
    settlement_currency     CHAR(3),
    fee_amount              BIGINT,
    tax_amount              BIGINT,
    net_amount              BIGINT,
    event_occurred_at       TIMESTAMPTZ NOT NULL,
    captured_at             TIMESTAMPTZ,
    refunded_at             TIMESTAMPTZ,
    ingested_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    settlement_id           VARCHAR(120),
    settlement_date         DATE,
    utr_number              VARCHAR(60),
    payment_method          VARCHAR(30),
    payment_method_detail   VARCHAR(60),
    card_last4              CHAR(4),
    card_network            VARCHAR(20),
    bank                    VARCHAR(60),
    vpa                     VARCHAR(120),
    user_id                 BIGINT REFERENCES users(id),
    payer_email             VARCHAR(254),
    payer_phone             VARCHAR(20),
    payer_name              VARCHAR(120),
    reconciliation_status   VARCHAR(40) NOT NULL DEFAULT 'PENDING',
    matched_at              TIMESTAMPTZ,
    exception_id            BIGINT,
    raw_payload             JSONB,
    notes                   JSONB,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_txn_provider_id UNIQUE (provider, provider_transaction_id)
);

CREATE INDEX idx_txn_merchant_order ON transactions (merchant_id, order_id);
CREATE INDEX idx_txn_settlement ON transactions (settlement_id);
CREATE INDEX idx_txn_recon_status ON transactions (reconciliation_status, event_occurred_at);
CREATE INDEX idx_txn_user_id ON transactions (user_id) WHERE user_id IS NOT NULL;
CREATE INDEX idx_txn_event_occurred ON transactions (event_occurred_at DESC);
CREATE INDEX idx_txn_parent ON transactions (parent_transaction_id) WHERE parent_transaction_id IS NOT NULL;
CREATE INDEX idx_txn_status ON transactions (status, event_occurred_at);
