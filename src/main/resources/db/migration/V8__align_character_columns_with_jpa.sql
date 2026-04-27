ALTER TABLE transactions
    ALTER COLUMN presentment_currency TYPE VARCHAR(3),
    ALTER COLUMN settlement_currency TYPE VARCHAR(3),
    ALTER COLUMN card_last4 TYPE VARCHAR(4);

ALTER TABLE settlements
    ALTER COLUMN currency TYPE VARCHAR(3);

ALTER TABLE exception_records
    ALTER COLUMN currency TYPE VARCHAR(3);
