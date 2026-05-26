CREATE TABLE bank_statement_entries (
    id                      BIGSERIAL PRIMARY KEY,
    merchant_id             VARCHAR(60) NOT NULL,
    upload_batch_id         VARCHAR(60) NOT NULL,
    entry_date              DATE NOT NULL,
    amount                  BIGINT NOT NULL,
    currency                CHAR(3) NOT NULL DEFAULT 'INR',
    credit_debit            VARCHAR(2) NOT NULL,
    utr_number              VARCHAR(80),
    bank_reference          VARCHAR(120),
    narration               TEXT,
    provider_hint           VARCHAR(30),
    match_status            VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    matched_by              VARCHAR(20),
    matched_settlement_id   BIGINT REFERENCES settlements(id),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_bank_entry_merchant_status  ON bank_statement_entries (merchant_id, match_status, entry_date DESC);
CREATE INDEX idx_bank_entry_utr              ON bank_statement_entries (utr_number) WHERE utr_number IS NOT NULL;
CREATE INDEX idx_bank_entry_date_amount      ON bank_statement_entries (entry_date, amount, credit_debit);
CREATE INDEX idx_bank_entry_batch            ON bank_statement_entries (upload_batch_id);
CREATE INDEX idx_bank_entry_settlement       ON bank_statement_entries (matched_settlement_id) WHERE matched_settlement_id IS NOT NULL;
