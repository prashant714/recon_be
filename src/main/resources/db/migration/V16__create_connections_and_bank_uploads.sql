CREATE TABLE provider_connections (
    id                  BIGSERIAL PRIMARY KEY,
    merchant_id         VARCHAR(60) NOT NULL,
    provider            VARCHAR(30) NOT NULL,
    api_key_encrypted   TEXT NOT NULL,
    secret_encrypted    TEXT NOT NULL,
    api_key_masked      VARCHAR(80) NOT NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_provider_connection UNIQUE (merchant_id, provider)
);

CREATE INDEX idx_provider_connections_merchant ON provider_connections (merchant_id);

CREATE TABLE bank_statement_uploads (
    id              BIGSERIAL PRIMARY KEY,
    upload_id       VARCHAR(60) NOT NULL UNIQUE,
    merchant_id     VARCHAR(60) NOT NULL,
    file_name       VARCHAR(255) NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'ACCEPTED',
    rows_parsed     INTEGER NOT NULL DEFAULT 0,
    matched_rows    INTEGER NOT NULL DEFAULT 0,
    exception_rows  INTEGER NOT NULL DEFAULT 0,
    progress        INTEGER NOT NULL DEFAULT 0,
    message         VARCHAR(255),
    uploaded_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_bank_uploads_merchant_uploaded ON bank_statement_uploads (merchant_id, uploaded_at DESC);
