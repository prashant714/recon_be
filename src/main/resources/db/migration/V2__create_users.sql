CREATE TABLE users (
    id                        BIGSERIAL PRIMARY KEY,
    merchant_id               VARCHAR(60) NOT NULL,
    email                     VARCHAR(254),
    phone                     VARCHAR(20),
    name                      VARCHAR(120),
    first_seen_at             TIMESTAMPTZ,
    last_seen_at              TIMESTAMPTZ,
    total_txn_count           INTEGER NOT NULL DEFAULT 0,
    total_txn_amount          BIGINT NOT NULL DEFAULT 0,
    failed_txn_count          INTEGER NOT NULL DEFAULT 0,
    distinct_payment_methods  INTEGER NOT NULL DEFAULT 0,
    risk_score                DECIMAL(5, 4),
    risk_flags                JSONB,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_users_email_merchant
    ON users (merchant_id, email)
    WHERE email IS NOT NULL;

CREATE UNIQUE INDEX idx_users_phone_merchant
    ON users (merchant_id, phone)
    WHERE phone IS NOT NULL;
