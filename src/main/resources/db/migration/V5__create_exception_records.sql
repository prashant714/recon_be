CREATE TABLE exception_records (
    id                  BIGSERIAL PRIMARY KEY,
    merchant_id         VARCHAR(60) NOT NULL,
    exception_type      VARCHAR(40) NOT NULL,
    severity            VARCHAR(20) NOT NULL DEFAULT 'MEDIUM',
    transaction_id      BIGINT REFERENCES transactions(id),
    settlement_id       BIGINT REFERENCES settlements(id),
    expected_amount     BIGINT,
    actual_amount       BIGINT,
    discrepancy_amount  BIGINT,
    currency            CHAR(3),
    description         TEXT NOT NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    resolved_by         VARCHAR(60),
    resolved_at         TIMESTAMPTZ,
    resolution_notes    TEXT,
    detected_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_exception_merchant_status
    ON exception_records (merchant_id, status, detected_at DESC);
CREATE INDEX idx_exception_type
    ON exception_records (exception_type, detected_at DESC);
CREATE INDEX idx_exception_severity
    ON exception_records (severity, status);
