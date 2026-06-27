ALTER TABLE exception_records
    ADD COLUMN bank_entry_id BIGINT REFERENCES bank_statement_entries(id);

CREATE INDEX idx_exception_bank_entry
    ON exception_records (exception_type, bank_entry_id, status)
    WHERE bank_entry_id IS NOT NULL;
