ALTER TABLE bank_statement_entries
    ALTER COLUMN currency TYPE VARCHAR(3);

ALTER TABLE orders
    ALTER COLUMN currency TYPE VARCHAR(3);

ALTER TABLE settlement_report_lines
    ALTER COLUMN currency TYPE VARCHAR(3);
