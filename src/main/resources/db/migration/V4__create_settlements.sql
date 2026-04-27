CREATE TABLE settlements (
    id                      BIGSERIAL PRIMARY KEY,
    provider                VARCHAR(30) NOT NULL,
    provider_settlement_id  VARCHAR(120) NOT NULL,
    merchant_id             VARCHAR(60) NOT NULL,
    gross_amount            BIGINT NOT NULL,
    total_fees              BIGINT NOT NULL DEFAULT 0,
    total_tax               BIGINT NOT NULL DEFAULT 0,
    net_amount              BIGINT NOT NULL,
    currency                CHAR(3) NOT NULL,
    bank_credit_amount      BIGINT,
    bank_credit_date        DATE,
    utr_number              VARCHAR(60),
    settlement_status       VARCHAR(40) NOT NULL DEFAULT 'PENDING',
    transaction_count       INTEGER,
    settled_at              TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_settlement_provider_id UNIQUE (provider, provider_settlement_id)
);

CREATE INDEX idx_settlement_merchant ON settlements (merchant_id, settled_at DESC);
CREATE INDEX idx_settlement_utr ON settlements (utr_number) WHERE utr_number IS NOT NULL;
CREATE INDEX idx_settlement_status ON settlements (settlement_status);
