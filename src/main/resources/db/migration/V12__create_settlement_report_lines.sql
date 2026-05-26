CREATE TABLE settlement_report_lines (
    id                  BIGSERIAL PRIMARY KEY,
    settlement_id       BIGINT NOT NULL REFERENCES settlements(id),
    provider            VARCHAR(30) NOT NULL,
    provider_txn_id     VARCHAR(120) NOT NULL,
    entity_type         VARCHAR(30) NOT NULL,
    gross_amount        BIGINT NOT NULL,
    fee_amount          BIGINT NOT NULL DEFAULT 0,
    net_amount          BIGINT NOT NULL,
    currency            CHAR(3) NOT NULL,
    match_status        VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    matched_to_txn_id   BIGINT REFERENCES transactions(id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_report_line UNIQUE (settlement_id, provider_txn_id)
);

CREATE INDEX idx_report_line_settlement   ON settlement_report_lines (settlement_id, match_status);
CREATE INDEX idx_report_line_provider_txn ON settlement_report_lines (provider, provider_txn_id);
CREATE INDEX idx_report_line_match_status ON settlement_report_lines (match_status, created_at DESC);
