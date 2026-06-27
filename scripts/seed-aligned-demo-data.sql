-- Clean, relationship-aligned demo data for the reconciliation flow.
--
-- What this script guarantees:
-- 1. orders.provider_order_id matches transactions.provider_order_id.
-- 2. transactions.provider_transaction_id matches webhook payload payment IDs.
-- 3. transactions.provider_event_id matches webhook_events.provider_event_id.
-- 4. settlement_report_lines.provider_txn_id matches transactions.provider_transaction_id.
-- 5. settlement_report_lines.matched_to_txn_id points to the matching transaction row.
-- 6. settlements.provider_settlement_id matches transactions.settlement_id.
-- 7. bank_statement_entries.matched_settlement_id points to the matching settlement row.
-- 8. refunds are linked to their parent payments through parent_transaction_id.
-- 9. no open exception_records are created for this demo merchant.
--
-- Run:
--   psql "$DATABASE_URL" -f scripts/seed-aligned-demo-data.sql

BEGIN;

-- Keep the cleanup scoped to this demo merchant only.
DELETE FROM exception_records
WHERE merchant_id = 'merchant_aligned_demo';

DELETE FROM settlement_report_lines
WHERE settlement_id IN (
    SELECT id FROM settlements WHERE merchant_id = 'merchant_aligned_demo'
);

DELETE FROM bank_statement_entries
WHERE merchant_id = 'merchant_aligned_demo';

DELETE FROM bank_statement_uploads
WHERE merchant_id = 'merchant_aligned_demo';

DELETE FROM orders
WHERE merchant_id = 'merchant_aligned_demo';

DELETE FROM transactions
WHERE merchant_id = 'merchant_aligned_demo';

DELETE FROM settlements
WHERE merchant_id = 'merchant_aligned_demo';

DELETE FROM webhook_events
WHERE merchant_id = 'merchant_aligned_demo';

DELETE FROM provider_connections
WHERE merchant_id = 'merchant_aligned_demo';

DELETE FROM users
WHERE merchant_id = 'merchant_aligned_demo';

DELETE FROM merchants
WHERE merchant_id = 'merchant_aligned_demo';

INSERT INTO merchants (
    merchant_id, name, email, api_key_hash, webhook_secret, status,
    created_at, updated_at, last_bank_statement_upload_at
) VALUES (
    'merchant_aligned_demo',
    'Aligned Demo Merchant',
    'ops+aligned-demo@example.com',
    'demo_api_key_hash',
    'demo_webhook_secret',
    'ACTIVE',
    NOW(),
    NOW(),
    TIMESTAMPTZ '2026-05-29 12:30:00+00'
);

INSERT INTO provider_connections (
    merchant_id, provider, api_key_encrypted, secret_encrypted,
    api_key_masked, status, created_at, updated_at
) VALUES (
    'merchant_aligned_demo',
    'razorpay',
    'encrypted_demo_key',
    'encrypted_demo_secret',
    'rzp_test_****demo',
    'ACTIVE',
    NOW(),
    NOW()
);

INSERT INTO users (
    merchant_id, email, phone, name,
    first_seen_at, last_seen_at, risk_score, risk_flags,
    created_at, updated_at
) VALUES
    (
        'merchant_aligned_demo',
        'aisha@example.com',
        '+919810000001',
        'Aisha Mehta',
        TIMESTAMPTZ '2026-05-24 10:00:00+00',
        TIMESTAMPTZ '2026-05-29 10:00:00+00',
        0.0500,
        '{"flags":[]}'::jsonb,
        NOW(),
        NOW()
    ),
    (
        'merchant_aligned_demo',
        'rohan@example.com',
        '+919810000002',
        'Rohan Iyer',
        TIMESTAMPTZ '2026-05-24 11:00:00+00',
        TIMESTAMPTZ '2026-05-28 11:00:00+00',
        0.0800,
        '{"flags":[]}'::jsonb,
        NOW(),
        NOW()
    );

INSERT INTO webhook_events (
    provider, provider_event_id, event_type, received_at, payload,
    signature_valid, source, processed, processed_at, merchant_id
) VALUES
    (
        'razorpay',
        'evt_aligned_pay_001',
        'payment.captured',
        TIMESTAMPTZ '2026-05-24 10:00:08+00',
        '{"event":"payment.captured","payload":{"payment":{"entity":{"id":"pay_aligned_001","order_id":"order_aligned_001","amount":125000,"currency":"INR","status":"captured","method":"upi","created_at":1787565600}}}}'::jsonb,
        TRUE,
        'WEBHOOK',
        TRUE,
        TIMESTAMPTZ '2026-05-24 10:00:10+00',
        'merchant_aligned_demo'
    ),
    (
        'razorpay',
        'evt_aligned_pay_002',
        'payment.captured',
        TIMESTAMPTZ '2026-05-24 11:00:08+00',
        '{"event":"payment.captured","payload":{"payment":{"entity":{"id":"pay_aligned_002","order_id":"order_aligned_002","amount":239900,"currency":"INR","status":"captured","method":"card","created_at":1787569200}}}}'::jsonb,
        TRUE,
        'WEBHOOK',
        TRUE,
        TIMESTAMPTZ '2026-05-24 11:00:10+00',
        'merchant_aligned_demo'
    ),
    (
        'razorpay',
        'evt_aligned_pay_003',
        'payment.captured',
        TIMESTAMPTZ '2026-05-25 09:30:08+00',
        '{"event":"payment.captured","payload":{"payment":{"entity":{"id":"pay_aligned_003","order_id":"order_aligned_003","amount":49900,"currency":"INR","status":"captured","method":"netbanking","created_at":1787650200}}}}'::jsonb,
        TRUE,
        'WEBHOOK',
        TRUE,
        TIMESTAMPTZ '2026-05-25 09:30:10+00',
        'merchant_aligned_demo'
    ),
    (
        'razorpay',
        'evt_aligned_pay_004',
        'payment.captured',
        TIMESTAMPTZ '2026-05-25 12:15:08+00',
        '{"event":"payment.captured","payload":{"payment":{"entity":{"id":"pay_aligned_004","order_id":"order_aligned_004","amount":75900,"currency":"INR","status":"captured","method":"upi","created_at":1787660100}}}}'::jsonb,
        TRUE,
        'WEBHOOK',
        TRUE,
        TIMESTAMPTZ '2026-05-25 12:15:10+00',
        'merchant_aligned_demo'
    ),
    (
        'razorpay',
        'evt_aligned_pay_005',
        'payment.captured',
        TIMESTAMPTZ '2026-05-26 14:20:08+00',
        '{"event":"payment.captured","payload":{"payment":{"entity":{"id":"pay_aligned_005","order_id":"order_aligned_005","amount":150000,"currency":"INR","status":"captured","method":"card","created_at":1787754000}}}}'::jsonb,
        TRUE,
        'WEBHOOK',
        TRUE,
        TIMESTAMPTZ '2026-05-26 14:20:10+00',
        'merchant_aligned_demo'
    ),
    (
        'razorpay',
        'evt_aligned_pay_006',
        'payment.captured',
        TIMESTAMPTZ '2026-05-27 16:45:08+00',
        '{"event":"payment.captured","payload":{"payment":{"entity":{"id":"pay_aligned_006","order_id":"order_aligned_006","amount":89900,"currency":"INR","status":"captured","method":"wallet","created_at":1787849100}}}}'::jsonb,
        TRUE,
        'WEBHOOK',
        TRUE,
        TIMESTAMPTZ '2026-05-27 16:45:10+00',
        'merchant_aligned_demo'
    ),
    (
        'razorpay',
        'evt_aligned_pay_007',
        'payment.captured',
        TIMESTAMPTZ '2026-05-28 09:10:08+00',
        '{"event":"payment.captured","payload":{"payment":{"entity":{"id":"pay_aligned_007","order_id":"order_aligned_007","amount":319900,"currency":"INR","status":"captured","method":"upi","created_at":1787908200}}}}'::jsonb,
        TRUE,
        'WEBHOOK',
        TRUE,
        TIMESTAMPTZ '2026-05-28 09:10:10+00',
        'merchant_aligned_demo'
    ),
    (
        'razorpay',
        'evt_aligned_pay_008',
        'payment.captured',
        TIMESTAMPTZ '2026-05-29 10:00:08+00',
        '{"event":"payment.captured","payload":{"payment":{"entity":{"id":"pay_aligned_008","order_id":"order_aligned_008","amount":99000,"currency":"INR","status":"captured","method":"card","created_at":1787997600}}}}'::jsonb,
        TRUE,
        'WEBHOOK',
        TRUE,
        TIMESTAMPTZ '2026-05-29 10:00:10+00',
        'merchant_aligned_demo'
    ),
    (
        'razorpay',
        'evt_aligned_refund_002',
        'refund.processed',
        TIMESTAMPTZ '2026-05-28 15:00:08+00',
        '{"event":"refund.processed","payload":{"refund":{"entity":{"id":"rfnd_aligned_002","payment_id":"pay_aligned_002","amount":50000,"currency":"INR","status":"processed","created_at":1787929200}}}}'::jsonb,
        TRUE,
        'WEBHOOK',
        TRUE,
        TIMESTAMPTZ '2026-05-28 15:00:10+00',
        'merchant_aligned_demo'
    );

INSERT INTO orders (
    merchant_id, order_id, provider_order_id, expected_amount, currency,
    order_status, amount_matched, discrepancy_amount, metadata,
    created_at, updated_at
) VALUES
    ('merchant_aligned_demo', 'ORD-ALIGNED-001', 'order_aligned_001', 125000, 'INR', 'PAYMENT_RECEIVED', TRUE, 0, '{"channel":"web","scenario":"matched"}'::jsonb, TIMESTAMPTZ '2026-05-24 09:58:00+00', NOW()),
    ('merchant_aligned_demo', 'ORD-ALIGNED-002', 'order_aligned_002', 239900, 'INR', 'REFUNDED', TRUE, 0, '{"channel":"app","scenario":"partial_refund"}'::jsonb, TIMESTAMPTZ '2026-05-24 10:58:00+00', NOW()),
    ('merchant_aligned_demo', 'ORD-ALIGNED-003', 'order_aligned_003', 49900,  'INR', 'PAYMENT_RECEIVED', TRUE, 0, '{"channel":"web","scenario":"matched"}'::jsonb, TIMESTAMPTZ '2026-05-25 09:28:00+00', NOW()),
    ('merchant_aligned_demo', 'ORD-ALIGNED-004', 'order_aligned_004', 75900,  'INR', 'PAYMENT_RECEIVED', TRUE, 0, '{"channel":"app","scenario":"matched"}'::jsonb, TIMESTAMPTZ '2026-05-25 12:13:00+00', NOW()),
    ('merchant_aligned_demo', 'ORD-ALIGNED-005', 'order_aligned_005', 150000, 'INR', 'PAYMENT_RECEIVED', TRUE, 0, '{"channel":"web","scenario":"matched"}'::jsonb, TIMESTAMPTZ '2026-05-26 14:18:00+00', NOW()),
    ('merchant_aligned_demo', 'ORD-ALIGNED-006', 'order_aligned_006', 89900,  'INR', 'PAYMENT_RECEIVED', TRUE, 0, '{"channel":"pos","scenario":"matched"}'::jsonb, TIMESTAMPTZ '2026-05-27 16:43:00+00', NOW()),
    ('merchant_aligned_demo', 'ORD-ALIGNED-007', 'order_aligned_007', 319900, 'INR', 'PAYMENT_RECEIVED', TRUE, 0, '{"channel":"web","scenario":"matched"}'::jsonb, TIMESTAMPTZ '2026-05-28 09:08:00+00', NOW()),
    ('merchant_aligned_demo', 'ORD-ALIGNED-008', 'order_aligned_008', 99000,  'INR', 'PAYMENT_RECEIVED', TRUE, 0, '{"channel":"app","scenario":"matched"}'::jsonb, TIMESTAMPTZ '2026-05-29 09:58:00+00', NOW());

INSERT INTO settlements (
    provider, provider_settlement_id, merchant_id,
    gross_amount, total_fees, total_tax, net_amount, currency,
    bank_credit_amount, bank_credit_date, utr_number,
    settlement_status, transaction_count, settled_at, created_at, updated_at
) VALUES (
    'razorpay',
    'setl_aligned_001',
    'merchant_aligned_demo',
    1149500,
    26230,
    4722,
    1118548,
    'INR',
    1118548,
    DATE '2026-05-30',
    'UTR-ALIGNED-001',
    'MATCHED_TO_BANK',
    8,
    TIMESTAMPTZ '2026-05-30 08:30:00+00',
    NOW(),
    NOW()
);

INSERT INTO transactions (
    provider, provider_transaction_id, provider_event_id, merchant_id,
    order_id, provider_order_id, event_type, status,
    presentment_amount, presentment_currency, settlement_amount, settlement_currency,
    fee_amount, tax_amount, net_amount,
    event_occurred_at, captured_at, ingested_at,
    settlement_id, settlement_date, utr_number,
    payment_method, payment_method_detail, card_last4, card_network, bank, vpa,
    user_id, payer_email, payer_phone, payer_name,
    reconciliation_status, matched_at, raw_payload, notes,
    created_at, updated_at
) VALUES
    ('razorpay', 'pay_aligned_001', 'evt_aligned_pay_001', 'merchant_aligned_demo', 'ORD-ALIGNED-001', 'order_aligned_001', 'PAYMENT', 'CAPTURED', 125000, 'INR', 125000, 'INR', 2850, 513, 121637, TIMESTAMPTZ '2026-05-24 10:00:00+00', TIMESTAMPTZ '2026-05-24 10:00:00+00', TIMESTAMPTZ '2026-05-24 10:00:10+00', 'setl_aligned_001', DATE '2026-05-30', 'UTR-ALIGNED-001', 'upi', 'collect', NULL, NULL, NULL, 'aisha@okicici', (SELECT id FROM users WHERE merchant_id = 'merchant_aligned_demo' AND email = 'aisha@example.com'), 'aisha@example.com', '+919810000001', 'Aisha Mehta', 'MATCHED', TIMESTAMPTZ '2026-05-24 10:00:12+00', '{"id":"pay_aligned_001","order_id":"order_aligned_001"}'::jsonb, '{"seed":"aligned-demo"}'::jsonb, NOW(), NOW()),
    ('razorpay', 'pay_aligned_002', 'evt_aligned_pay_002', 'merchant_aligned_demo', 'ORD-ALIGNED-002', 'order_aligned_002', 'PAYMENT', 'PARTIALLY_REFUNDED', 239900, 'INR', 239900, 'INR', 5470, 985, 233445, TIMESTAMPTZ '2026-05-24 11:00:00+00', TIMESTAMPTZ '2026-05-24 11:00:00+00', TIMESTAMPTZ '2026-05-24 11:00:10+00', 'setl_aligned_001', DATE '2026-05-30', 'UTR-ALIGNED-001', 'card', 'visa', '4242', 'VISA', NULL, NULL, (SELECT id FROM users WHERE merchant_id = 'merchant_aligned_demo' AND email = 'rohan@example.com'), 'rohan@example.com', '+919810000002', 'Rohan Iyer', 'MATCHED', TIMESTAMPTZ '2026-05-24 11:00:12+00', '{"id":"pay_aligned_002","order_id":"order_aligned_002"}'::jsonb, '{"seed":"aligned-demo"}'::jsonb, NOW(), NOW()),
    ('razorpay', 'pay_aligned_003', 'evt_aligned_pay_003', 'merchant_aligned_demo', 'ORD-ALIGNED-003', 'order_aligned_003', 'PAYMENT', 'CAPTURED', 49900, 'INR', 49900, 'INR', 1138, 205, 48557, TIMESTAMPTZ '2026-05-25 09:30:00+00', TIMESTAMPTZ '2026-05-25 09:30:00+00', TIMESTAMPTZ '2026-05-25 09:30:10+00', 'setl_aligned_001', DATE '2026-05-30', 'UTR-ALIGNED-001', 'netbanking', 'hdfc', NULL, NULL, 'HDFC', NULL, (SELECT id FROM users WHERE merchant_id = 'merchant_aligned_demo' AND email = 'aisha@example.com'), 'aisha@example.com', '+919810000001', 'Aisha Mehta', 'MATCHED', TIMESTAMPTZ '2026-05-25 09:30:12+00', '{"id":"pay_aligned_003","order_id":"order_aligned_003"}'::jsonb, '{"seed":"aligned-demo"}'::jsonb, NOW(), NOW()),
    ('razorpay', 'pay_aligned_004', 'evt_aligned_pay_004', 'merchant_aligned_demo', 'ORD-ALIGNED-004', 'order_aligned_004', 'PAYMENT', 'CAPTURED', 75900, 'INR', 75900, 'INR', 1730, 311, 73859, TIMESTAMPTZ '2026-05-25 12:15:00+00', TIMESTAMPTZ '2026-05-25 12:15:00+00', TIMESTAMPTZ '2026-05-25 12:15:10+00', 'setl_aligned_001', DATE '2026-05-30', 'UTR-ALIGNED-001', 'upi', 'intent', NULL, NULL, NULL, 'demo@upi', (SELECT id FROM users WHERE merchant_id = 'merchant_aligned_demo' AND email = 'rohan@example.com'), 'rohan@example.com', '+919810000002', 'Rohan Iyer', 'MATCHED', TIMESTAMPTZ '2026-05-25 12:15:12+00', '{"id":"pay_aligned_004","order_id":"order_aligned_004"}'::jsonb, '{"seed":"aligned-demo"}'::jsonb, NOW(), NOW()),
    ('razorpay', 'pay_aligned_005', 'evt_aligned_pay_005', 'merchant_aligned_demo', 'ORD-ALIGNED-005', 'order_aligned_005', 'PAYMENT', 'CAPTURED', 150000, 'INR', 150000, 'INR', 3420, 616, 145964, TIMESTAMPTZ '2026-05-26 14:20:00+00', TIMESTAMPTZ '2026-05-26 14:20:00+00', TIMESTAMPTZ '2026-05-26 14:20:10+00', 'setl_aligned_001', DATE '2026-05-30', 'UTR-ALIGNED-001', 'card', 'mastercard', '5555', 'MASTERCARD', NULL, NULL, (SELECT id FROM users WHERE merchant_id = 'merchant_aligned_demo' AND email = 'aisha@example.com'), 'aisha@example.com', '+919810000001', 'Aisha Mehta', 'MATCHED', TIMESTAMPTZ '2026-05-26 14:20:12+00', '{"id":"pay_aligned_005","order_id":"order_aligned_005"}'::jsonb, '{"seed":"aligned-demo"}'::jsonb, NOW(), NOW()),
    ('razorpay', 'pay_aligned_006', 'evt_aligned_pay_006', 'merchant_aligned_demo', 'ORD-ALIGNED-006', 'order_aligned_006', 'PAYMENT', 'CAPTURED', 89900, 'INR', 89900, 'INR', 2050, 369, 87481, TIMESTAMPTZ '2026-05-27 16:45:00+00', TIMESTAMPTZ '2026-05-27 16:45:00+00', TIMESTAMPTZ '2026-05-27 16:45:10+00', 'setl_aligned_001', DATE '2026-05-30', 'UTR-ALIGNED-001', 'wallet', 'paytm', NULL, NULL, NULL, NULL, (SELECT id FROM users WHERE merchant_id = 'merchant_aligned_demo' AND email = 'rohan@example.com'), 'rohan@example.com', '+919810000002', 'Rohan Iyer', 'MATCHED', TIMESTAMPTZ '2026-05-27 16:45:12+00', '{"id":"pay_aligned_006","order_id":"order_aligned_006"}'::jsonb, '{"seed":"aligned-demo"}'::jsonb, NOW(), NOW()),
    ('razorpay', 'pay_aligned_007', 'evt_aligned_pay_007', 'merchant_aligned_demo', 'ORD-ALIGNED-007', 'order_aligned_007', 'PAYMENT', 'CAPTURED', 319900, 'INR', 319900, 'INR', 7294, 1313, 311293, TIMESTAMPTZ '2026-05-28 09:10:00+00', TIMESTAMPTZ '2026-05-28 09:10:00+00', TIMESTAMPTZ '2026-05-28 09:10:10+00', 'setl_aligned_001', DATE '2026-05-30', 'UTR-ALIGNED-001', 'upi', 'collect', NULL, NULL, NULL, 'aisha@okicici', (SELECT id FROM users WHERE merchant_id = 'merchant_aligned_demo' AND email = 'aisha@example.com'), 'aisha@example.com', '+919810000001', 'Aisha Mehta', 'MATCHED', TIMESTAMPTZ '2026-05-28 09:10:12+00', '{"id":"pay_aligned_007","order_id":"order_aligned_007"}'::jsonb, '{"seed":"aligned-demo"}'::jsonb, NOW(), NOW()),
    ('razorpay', 'pay_aligned_008', 'evt_aligned_pay_008', 'merchant_aligned_demo', 'ORD-ALIGNED-008', 'order_aligned_008', 'PAYMENT', 'CAPTURED', 99000, 'INR', 99000, 'INR', 2278, 410, 96312, TIMESTAMPTZ '2026-05-29 10:00:00+00', TIMESTAMPTZ '2026-05-29 10:00:00+00', TIMESTAMPTZ '2026-05-29 10:00:10+00', 'setl_aligned_001', DATE '2026-05-30', 'UTR-ALIGNED-001', 'card', 'rupay', '1111', 'RUPAY', NULL, NULL, (SELECT id FROM users WHERE merchant_id = 'merchant_aligned_demo' AND email = 'aisha@example.com'), 'aisha@example.com', '+919810000001', 'Aisha Mehta', 'MATCHED', TIMESTAMPTZ '2026-05-29 10:00:12+00', '{"id":"pay_aligned_008","order_id":"order_aligned_008"}'::jsonb, '{"seed":"aligned-demo"}'::jsonb, NOW(), NOW());

INSERT INTO transactions (
    provider, provider_transaction_id, provider_event_id, merchant_id,
    order_id, provider_order_id, event_type, status, parent_transaction_id,
    presentment_amount, presentment_currency, settlement_amount, settlement_currency,
    fee_amount, tax_amount, net_amount,
    event_occurred_at, refunded_at, ingested_at,
    payment_method, user_id, payer_email, payer_phone, payer_name,
    reconciliation_status, matched_at, raw_payload, notes,
    created_at, updated_at
) VALUES (
    'razorpay',
    'rfnd_aligned_002',
    'evt_aligned_refund_002',
    'merchant_aligned_demo',
    'ORD-ALIGNED-002',
    'order_aligned_002',
    'REFUND',
    'REFUNDED',
    (SELECT id FROM transactions WHERE merchant_id = 'merchant_aligned_demo' AND provider_transaction_id = 'pay_aligned_002'),
    50000,
    'INR',
    NULL,
    NULL,
    0,
    0,
    -50000,
    TIMESTAMPTZ '2026-05-28 15:00:00+00',
    TIMESTAMPTZ '2026-05-28 15:00:00+00',
    TIMESTAMPTZ '2026-05-28 15:00:10+00',
    'card',
    (SELECT id FROM users WHERE merchant_id = 'merchant_aligned_demo' AND email = 'rohan@example.com'),
    'rohan@example.com',
    '+919810000002',
    'Rohan Iyer',
    'MATCHED',
    TIMESTAMPTZ '2026-05-28 15:00:12+00',
    '{"id":"rfnd_aligned_002","payment_id":"pay_aligned_002"}'::jsonb,
    '{"seed":"aligned-demo","parent":"pay_aligned_002"}'::jsonb,
    NOW(),
    NOW()
);

UPDATE orders o
SET transaction_id = t.id,
    matched_at = t.matched_at,
    updated_at = NOW()
FROM transactions t
WHERE o.merchant_id = 'merchant_aligned_demo'
  AND t.merchant_id = o.merchant_id
  AND t.provider_order_id = o.provider_order_id
  AND t.event_type = 'PAYMENT';

INSERT INTO settlement_report_lines (
    settlement_id, provider, provider_txn_id, entity_type,
    gross_amount, fee_amount, net_amount, currency,
    match_status, matched_to_txn_id, created_at, updated_at
)
SELECT
    s.id,
    t.provider,
    t.provider_transaction_id,
    'PAYMENT',
    t.presentment_amount,
    COALESCE(t.fee_amount, 0) + COALESCE(t.tax_amount, 0),
    t.net_amount,
    t.presentment_currency,
    'MATCHED',
    t.id,
    NOW(),
    NOW()
FROM transactions t
JOIN settlements s
  ON s.merchant_id = t.merchant_id
 AND s.provider_settlement_id = t.settlement_id
WHERE t.merchant_id = 'merchant_aligned_demo'
  AND t.event_type = 'PAYMENT';

INSERT INTO bank_statement_uploads (
    upload_id, merchant_id, file_name, status, rows_parsed,
    matched_rows, exception_rows, progress, message,
    uploaded_at, updated_at
) VALUES (
    'upload_aligned_001',
    'merchant_aligned_demo',
    'aligned-demo-bank-statement.csv',
    'COMPLETED',
    1,
    1,
    0,
    100,
    'Aligned demo bank statement loaded and matched',
    TIMESTAMPTZ '2026-05-30 12:00:00+00',
    NOW()
);

INSERT INTO bank_statement_entries (
    merchant_id, upload_batch_id, entry_date, amount, currency,
    credit_debit, utr_number, bank_reference, narration,
    provider_hint, match_status, matched_by, matched_settlement_id,
    created_at, updated_at
) VALUES (
    'merchant_aligned_demo',
    'upload_aligned_001',
    DATE '2026-05-30',
    1118548,
    'INR',
    'CR',
    'UTR-ALIGNED-001',
    'BANK-REF-ALIGNED-001',
    'RAZORPAY SETTLEMENT UTR-ALIGNED-001',
    'razorpay',
    'MATCHED',
    'UTR',
    (SELECT id FROM settlements WHERE merchant_id = 'merchant_aligned_demo' AND provider_settlement_id = 'setl_aligned_001'),
    NOW(),
    NOW()
);

UPDATE users u
SET total_txn_count = stats.total_txn_count,
    total_txn_amount = stats.total_txn_amount,
    failed_txn_count = stats.failed_txn_count,
    distinct_payment_methods = stats.distinct_payment_methods,
    first_seen_at = stats.first_seen_at,
    last_seen_at = stats.last_seen_at,
    updated_at = NOW()
FROM (
    SELECT
        user_id,
        COUNT(*) FILTER (WHERE event_type = 'PAYMENT')::integer AS total_txn_count,
        COALESCE(SUM(presentment_amount) FILTER (WHERE event_type = 'PAYMENT'), 0) AS total_txn_amount,
        COUNT(*) FILTER (WHERE status = 'FAILED')::integer AS failed_txn_count,
        COUNT(DISTINCT payment_method)::integer AS distinct_payment_methods,
        MIN(event_occurred_at) AS first_seen_at,
        MAX(event_occurred_at) AS last_seen_at
    FROM transactions
    WHERE merchant_id = 'merchant_aligned_demo'
      AND user_id IS NOT NULL
    GROUP BY user_id
) stats
WHERE u.id = stats.user_id;

-- Guardrails: fail the script if the aligned demo data is internally inconsistent.
DO $$
DECLARE
    problem_count integer;
BEGIN
    SELECT COUNT(*) INTO problem_count
    FROM orders o
    LEFT JOIN transactions t
      ON t.id = o.transaction_id
     AND t.merchant_id = o.merchant_id
     AND t.provider_order_id = o.provider_order_id
    WHERE o.merchant_id = 'merchant_aligned_demo'
      AND t.id IS NULL;

    IF problem_count > 0 THEN
        RAISE EXCEPTION 'Aligned demo validation failed: % orders are not linked to matching transactions', problem_count;
    END IF;

    SELECT COUNT(*) INTO problem_count
    FROM settlement_report_lines l
    JOIN settlements s ON s.id = l.settlement_id
    LEFT JOIN transactions t
      ON t.id = l.matched_to_txn_id
     AND t.provider_transaction_id = l.provider_txn_id
     AND t.settlement_id = s.provider_settlement_id
    WHERE s.merchant_id = 'merchant_aligned_demo'
      AND t.id IS NULL;

    IF problem_count > 0 THEN
        RAISE EXCEPTION 'Aligned demo validation failed: % settlement report lines are not linked to matching transactions', problem_count;
    END IF;

    SELECT COUNT(*) INTO problem_count
    FROM settlements s
    LEFT JOIN (
        SELECT settlement_id, SUM(net_amount) AS transaction_net_amount
        FROM transactions
        WHERE merchant_id = 'merchant_aligned_demo'
          AND event_type = 'PAYMENT'
        GROUP BY settlement_id
    ) tx ON tx.settlement_id = s.provider_settlement_id
    WHERE s.merchant_id = 'merchant_aligned_demo'
      AND s.net_amount <> COALESCE(tx.transaction_net_amount, 0);

    IF problem_count > 0 THEN
        RAISE EXCEPTION 'Aligned demo validation failed: % settlements do not equal linked payment net totals', problem_count;
    END IF;

    SELECT COUNT(*) INTO problem_count
    FROM transactions r
    LEFT JOIN transactions p ON p.id = r.parent_transaction_id
    WHERE r.merchant_id = 'merchant_aligned_demo'
      AND r.event_type = 'REFUND'
      AND p.id IS NULL;

    IF problem_count > 0 THEN
        RAISE EXCEPTION 'Aligned demo validation failed: % refunds are missing parent payment links', problem_count;
    END IF;

    SELECT COUNT(*) INTO problem_count
    FROM exception_records
    WHERE merchant_id = 'merchant_aligned_demo'
      AND status IN ('OPEN', 'IN_REVIEW');

    IF problem_count > 0 THEN
        RAISE EXCEPTION 'Aligned demo validation failed: % active exceptions exist for merchant_aligned_demo', problem_count;
    END IF;
END $$;

COMMIT;
