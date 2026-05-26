CREATE TABLE merchants (
    id              BIGSERIAL PRIMARY KEY,
    merchant_id     VARCHAR(60) NOT NULL,
    name            VARCHAR(120) NOT NULL,
    email           VARCHAR(254) NOT NULL,
    api_key_hash    VARCHAR(256) NOT NULL,
    webhook_secret  VARCHAR(256),
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_merchant_merchant_id UNIQUE (merchant_id),
    CONSTRAINT uq_merchant_email UNIQUE (email)
);

CREATE INDEX idx_merchant_status ON merchants (status);
