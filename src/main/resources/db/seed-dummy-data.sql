-- ============================================================================
-- Seed Script: Dummy data for merchant_001
-- Run: psql -h localhost -p 5433 -U recon_user -d reconciliation_dev -f src/main/resources/db/seed-dummy-data.sql
-- ============================================================================

BEGIN;

-- ============================================================================
-- 1. USERS (30 dummy payers)
-- ============================================================================
INSERT INTO users (merchant_id, email, phone, name, first_seen_at, last_seen_at,
    total_txn_count, total_txn_amount, failed_txn_count, distinct_payment_methods,
    risk_score, risk_flags, created_at, updated_at)
VALUES
('merchant_001', 'rahul.sharma@gmail.com',   '9876543210', 'Rahul Sharma',     NOW() - INTERVAL '90 days', NOW() - INTERVAL '2 days',  12, 4500000, 1, 3, 0.12, '{}', NOW() - INTERVAL '90 days', NOW()),
('merchant_001', 'priya.patel@yahoo.com',    '9876543211', 'Priya Patel',      NOW() - INTERVAL '85 days', NOW() - INTERVAL '1 day',   8,  2300000, 0, 2, 0.05, '{}', NOW() - INTERVAL '85 days', NOW()),
('merchant_001', 'amit.kumar@outlook.com',   '9876543212', 'Amit Kumar',       NOW() - INTERVAL '80 days', NOW() - INTERVAL '3 days',  15, 7800000, 2, 4, 0.22, '{"velocity_spike": true}', NOW() - INTERVAL '80 days', NOW()),
('merchant_001', 'sneha.gupta@gmail.com',    '9876543213', 'Sneha Gupta',      NOW() - INTERVAL '75 days', NOW() - INTERVAL '5 days',  5,  1200000, 0, 1, 0.03, '{}', NOW() - INTERVAL '75 days', NOW()),
('merchant_001', 'vikram.singh@gmail.com',   '9876543214', 'Vikram Singh',     NOW() - INTERVAL '70 days', NOW() - INTERVAL '4 days',  9,  3400000, 1, 2, 0.15, '{}', NOW() - INTERVAL '70 days', NOW()),
('merchant_001', 'ananya.reddy@gmail.com',   '9876543215', 'Ananya Reddy',     NOW() - INTERVAL '65 days', NOW() - INTERVAL '6 days',  7,  2100000, 0, 3, 0.08, '{}', NOW() - INTERVAL '65 days', NOW()),
('merchant_001', 'rajesh.verma@hotmail.com', '9876543216', 'Rajesh Verma',     NOW() - INTERVAL '60 days', NOW() - INTERVAL '2 days',  11, 5600000, 3, 2, 0.35, '{"multiple_failures": true}', NOW() - INTERVAL '60 days', NOW()),
('merchant_001', 'deepika.joshi@gmail.com',  '9876543217', 'Deepika Joshi',    NOW() - INTERVAL '55 days', NOW() - INTERVAL '8 days',  4,  980000,  0, 1, 0.02, '{}', NOW() - INTERVAL '55 days', NOW()),
('merchant_001', 'arjun.nair@gmail.com',     '9876543218', 'Arjun Nair',       NOW() - INTERVAL '50 days', NOW() - INTERVAL '1 day',   6,  1800000, 1, 2, 0.10, '{}', NOW() - INTERVAL '50 days', NOW()),
('merchant_001', 'kavita.iyer@yahoo.com',    '9876543219', 'Kavita Iyer',      NOW() - INTERVAL '45 days', NOW() - INTERVAL '3 days',  3,  750000,  0, 1, 0.04, '{}', NOW() - INTERVAL '45 days', NOW()),
('merchant_001', 'manish.tiwari@gmail.com',  '9876543220', 'Manish Tiwari',    NOW() - INTERVAL '42 days', NOW() - INTERVAL '7 days',  10, 4200000, 2, 3, 0.18, '{}', NOW() - INTERVAL '42 days', NOW()),
('merchant_001', 'pooja.mishra@gmail.com',   '9876543221', 'Pooja Mishra',     NOW() - INTERVAL '40 days', NOW() - INTERVAL '2 days',  6,  1500000, 0, 2, 0.06, '{}', NOW() - INTERVAL '40 days', NOW()),
('merchant_001', 'suresh.menon@gmail.com',   '9876543222', 'Suresh Menon',     NOW() - INTERVAL '38 days', NOW() - INTERVAL '5 days',  8,  3200000, 1, 2, 0.14, '{}', NOW() - INTERVAL '38 days', NOW()),
('merchant_001', 'nisha.agarwal@gmail.com',  '9876543223', 'Nisha Agarwal',    NOW() - INTERVAL '35 days', NOW() - INTERVAL '1 day',   4,  1100000, 0, 1, 0.03, '{}', NOW() - INTERVAL '35 days', NOW()),
('merchant_001', 'karan.mehta@outlook.com',  '9876543224', 'Karan Mehta',      NOW() - INTERVAL '32 days', NOW() - INTERVAL '4 days',  7,  2800000, 0, 3, 0.09, '{}', NOW() - INTERVAL '32 days', NOW()),
('merchant_001', 'ritu.das@gmail.com',       '9876543225', 'Ritu Das',         NOW() - INTERVAL '30 days', NOW() - INTERVAL '2 days',  5,  1600000, 1, 2, 0.11, '{}', NOW() - INTERVAL '30 days', NOW()),
('merchant_001', 'sanjay.chopra@gmail.com',  '9876543226', 'Sanjay Chopra',    NOW() - INTERVAL '28 days', NOW() - INTERVAL '6 days',  9,  4100000, 0, 2, 0.07, '{}', NOW() - INTERVAL '28 days', NOW()),
('merchant_001', 'meera.bhat@yahoo.com',     '9876543227', 'Meera Bhat',       NOW() - INTERVAL '25 days', NOW() - INTERVAL '3 days',  3,  900000,  0, 1, 0.02, '{}', NOW() - INTERVAL '25 days', NOW()),
('merchant_001', 'aditya.saxena@gmail.com',  '9876543228', 'Aditya Saxena',    NOW() - INTERVAL '22 days', NOW() - INTERVAL '1 day',   6,  2400000, 1, 2, 0.13, '{}', NOW() - INTERVAL '22 days', NOW()),
('merchant_001', 'lakshmi.pillai@gmail.com', '9876543229', 'Lakshmi Pillai',   NOW() - INTERVAL '20 days', NOW() - INTERVAL '5 days',  4,  1050000, 0, 1, 0.04, '{}', NOW() - INTERVAL '20 days', NOW()),
('merchant_001', 'harsh.pandey@gmail.com',   '9876543230', 'Harsh Pandey',     NOW() - INTERVAL '18 days', NOW() - INTERVAL '2 days',  8,  3600000, 2, 3, 0.20, '{}', NOW() - INTERVAL '18 days', NOW()),
('merchant_001', 'swati.kapoor@gmail.com',   '9876543231', 'Swati Kapoor',     NOW() - INTERVAL '15 days', NOW() - INTERVAL '1 day',   5,  1350000, 0, 2, 0.05, '{}', NOW() - INTERVAL '15 days', NOW()),
('merchant_001', 'rohit.malhotra@gmail.com', '9876543232', 'Rohit Malhotra',   NOW() - INTERVAL '12 days', NOW() - INTERVAL '3 days',  7,  2900000, 1, 2, 0.16, '{}', NOW() - INTERVAL '12 days', NOW()),
('merchant_001', 'divya.rao@outlook.com',    '9876543233', 'Divya Rao',        NOW() - INTERVAL '10 days', NOW() - INTERVAL '2 days',  3,  680000,  0, 1, 0.03, '{}', NOW() - INTERVAL '10 days', NOW()),
('merchant_001', 'nikhil.bansal@gmail.com',  '9876543234', 'Nikhil Bansal',    NOW() - INTERVAL '8 days',  NOW() - INTERVAL '1 day',   6,  2200000, 0, 2, 0.07, '{}', NOW() - INTERVAL '8 days',  NOW()),
('merchant_001', 'anjali.sinha@gmail.com',   '9876543235', 'Anjali Sinha',     NOW() - INTERVAL '7 days',  NOW() - INTERVAL '1 day',   4,  1150000, 0, 1, 0.04, '{}', NOW() - INTERVAL '7 days',  NOW()),
('merchant_001', 'vivek.chauhan@gmail.com',  '9876543236', 'Vivek Chauhan',    NOW() - INTERVAL '5 days',  NOW() - INTERVAL '1 day',   2,  450000,  1, 1, 0.25, '{"high_value_first_txn": true}', NOW() - INTERVAL '5 days', NOW()),
('merchant_001', 'isha.dubey@yahoo.com',     '9876543237', 'Isha Dubey',       NOW() - INTERVAL '4 days',  NOW() - INTERVAL '1 day',   3,  820000,  0, 2, 0.06, '{}', NOW() - INTERVAL '4 days',  NOW()),
('merchant_001', 'gaurav.sethi@gmail.com',   '9876543238', 'Gaurav Sethi',     NOW() - INTERVAL '3 days',  NOW() - INTERVAL '1 day',   5,  1900000, 0, 2, 0.08, '{}', NOW() - INTERVAL '3 days',  NOW()),
('merchant_001', 'tanvi.bhatt@gmail.com',    '9876543239', 'Tanvi Bhatt',      NOW() - INTERVAL '2 days',  NOW() - INTERVAL '1 day',   2,  520000,  0, 1, 0.03, '{}', NOW() - INTERVAL '2 days',  NOW())
ON CONFLICT DO NOTHING;

-- ============================================================================
-- 2. SETTLEMENTS (15 settlement batches over last 90 days)
-- ============================================================================
INSERT INTO settlements (provider, provider_settlement_id, merchant_id, gross_amount,
    total_fees, total_tax, net_amount, currency, bank_credit_amount, bank_credit_date,
    utr_number, settlement_status, transaction_count, settled_at, created_at, updated_at)
VALUES
('razorpay', 'setl_seed_001', 'merchant_001', 5200000,  104000, 18720, 5077280,  'INR', 5077280,  (CURRENT_DATE - 87), 'UTR2025030101', 'MATCHED_TO_BANK', 12, NOW() - INTERVAL '88 days', NOW() - INTERVAL '88 days', NOW()),
('razorpay', 'setl_seed_002', 'merchant_001', 3800000,  76000,  13680, 3710320,  'INR', 3710320,  (CURRENT_DATE - 80), 'UTR2025030801', 'MATCHED_TO_BANK', 9,  NOW() - INTERVAL '81 days', NOW() - INTERVAL '81 days', NOW()),
('razorpay', 'setl_seed_003', 'merchant_001', 6100000,  122000, 21960, 5956040,  'INR', 5956040,  (CURRENT_DATE - 73), 'UTR2025031501', 'MATCHED_TO_BANK', 14, NOW() - INTERVAL '74 days', NOW() - INTERVAL '74 days', NOW()),
('razorpay', 'setl_seed_004', 'merchant_001', 4500000,  90000,  16200, 4393800,  'INR', 4393800,  (CURRENT_DATE - 66), 'UTR2025032201', 'MATCHED_TO_BANK', 10, NOW() - INTERVAL '67 days', NOW() - INTERVAL '67 days', NOW()),
('razorpay', 'setl_seed_005', 'merchant_001', 7200000,  144000, 25920, 7030080,  'INR', 7030080,  (CURRENT_DATE - 59), 'UTR2025032901', 'MATCHED_TO_BANK', 16, NOW() - INTERVAL '60 days', NOW() - INTERVAL '60 days', NOW()),
('razorpay', 'setl_seed_006', 'merchant_001', 3200000,  64000,  11520, 3124480,  'INR', 3124480,  (CURRENT_DATE - 52), 'UTR2025040501', 'MATCHED_TO_BANK', 8,  NOW() - INTERVAL '53 days', NOW() - INTERVAL '53 days', NOW()),
('razorpay', 'setl_seed_007', 'merchant_001', 5800000,  116000, 20880, 5663120,  'INR', 5663120,  (CURRENT_DATE - 45), 'UTR2025041201', 'MATCHED_TO_BANK', 13, NOW() - INTERVAL '46 days', NOW() - INTERVAL '46 days', NOW()),
('razorpay', 'setl_seed_008', 'merchant_001', 4100000,  82000,  14760, 4003240,  'INR', 4003240,  (CURRENT_DATE - 38), 'UTR2025041901', 'MATCHED_TO_BANK', 10, NOW() - INTERVAL '39 days', NOW() - INTERVAL '39 days', NOW()),
('razorpay', 'setl_seed_009', 'merchant_001', 6800000,  136000, 24480, 6639520,  'INR', 6639520,  (CURRENT_DATE - 31), 'UTR2025042601', 'MATCHED_TO_BANK', 15, NOW() - INTERVAL '32 days', NOW() - INTERVAL '32 days', NOW()),
('razorpay', 'setl_seed_010', 'merchant_001', 3500000,  70000,  12600, 3417400,  'INR', 3417400,  (CURRENT_DATE - 24), 'UTR2025050301', 'SETTLED',         9,  NOW() - INTERVAL '25 days', NOW() - INTERVAL '25 days', NOW()),
('razorpay', 'setl_seed_011', 'merchant_001', 5500000,  110000, 19800, 5370200,  'INR', 5370200,  (CURRENT_DATE - 17), 'UTR2025051001', 'SETTLED',         12, NOW() - INTERVAL '18 days', NOW() - INTERVAL '18 days', NOW()),
('razorpay', 'setl_seed_012', 'merchant_001', 4800000,  96000,  17280, 4686720,  'INR', 4686720,  (CURRENT_DATE - 10), 'UTR2025051701', 'SETTLED',         11, NOW() - INTERVAL '11 days', NOW() - INTERVAL '11 days', NOW()),
('razorpay', 'setl_seed_013', 'merchant_001', 6300000,  126000, 22680, 6151320,  'INR', NULL,      NULL,                NULL,            'PENDING',         14, NOW() - INTERVAL '4 days',  NOW() - INTERVAL '4 days',  NOW()),
('razorpay', 'setl_seed_014', 'merchant_001', 2900000,  58000,  10440, 2831560,  'INR', NULL,      NULL,                NULL,            'PENDING',         7,  NOW() - INTERVAL '2 days',  NOW() - INTERVAL '2 days',  NOW()),
('razorpay', 'setl_seed_015', 'merchant_001', 1800000,  36000,  6480,  1757520,  'INR', NULL,      NULL,                NULL,            'PENDING',         5,  NOW() - INTERVAL '1 day',   NOW() - INTERVAL '1 day',   NOW())
ON CONFLICT (provider, provider_settlement_id) DO NOTHING;

-- ============================================================================
-- 3. ORDERS (200 orders)
-- ============================================================================
INSERT INTO orders (merchant_id, order_id, provider_order_id, expected_amount, currency,
    order_status, amount_matched, discrepancy_amount, metadata, created_at, updated_at)
SELECT
    'merchant_001',
    'ORD-SEED-' || LPAD(i::text, 4, '0'),
    'order_rzp_seed_' || LPAD(i::text, 4, '0'),
    amount,
    'INR',
    CASE
        WHEN i <= 150 THEN 'PAYMENT_RECEIVED'
        WHEN i <= 170 THEN 'CREATED'
        WHEN i <= 185 THEN 'CANCELLED'
        WHEN i <= 195 THEN 'REFUNDED'
        ELSE 'OVERPAID'
    END::varchar,
    CASE WHEN i <= 150 THEN true ELSE false END,
    CASE WHEN i > 195 THEN (random() * 5000 + 100)::bigint ELSE 0 END,
    json_build_object('source', 'web', 'ip', '192.168.1.' || (i % 254 + 1))::jsonb,
    NOW() - (INTERVAL '1 day' * (200 - i + (random() * 5)::int)),
    NOW() - (INTERVAL '1 day' * (200 - i + (random() * 3)::int))
FROM (
    SELECT
        i,
        (ARRAY[25000, 49900, 99900, 149900, 199900, 249900, 349900, 499900, 599900, 799900,
               999900, 1499900, 1999900, 2499900, 50000, 75000, 125000, 175000, 225000, 299900])[((i - 1) % 20) + 1] AS amount
    FROM generate_series(1, 200) AS s(i)
) sub
ON CONFLICT (merchant_id, order_id) DO NOTHING;

-- ============================================================================
-- 4. TRANSACTIONS (200 payment transactions)
-- ============================================================================

-- Payment methods and banks for realistic distribution
INSERT INTO transactions (
    provider, provider_transaction_id, provider_event_id, merchant_id, order_id,
    provider_order_id, event_type, status, presentment_amount, presentment_currency,
    settlement_amount, settlement_currency, fee_amount, tax_amount, net_amount,
    event_occurred_at, captured_at, ingested_at,
    settlement_id, settlement_date, utr_number,
    payment_method, payment_method_detail, card_last4, card_network, bank, vpa,
    payer_email, payer_phone, payer_name,
    reconciliation_status, raw_payload, notes, created_at, updated_at
)
SELECT
    'razorpay',
    'pay_seed_' || LPAD(i::text, 4, '0'),
    'evt_seed_' || LPAD(i::text, 4, '0'),
    'merchant_001',
    'ORD-SEED-' || LPAD(i::text, 4, '0'),
    'order_rzp_seed_' || LPAD(i::text, 4, '0'),
    'PAYMENT',
    -- Status distribution: 75% CAPTURED, 10% FAILED, 5% AUTHORIZED, 5% REFUNDED, 5% PENDING
    CASE
        WHEN i <= 150 THEN 'CAPTURED'
        WHEN i <= 170 THEN 'FAILED'
        WHEN i <= 180 THEN 'AUTHORIZED'
        WHEN i <= 190 THEN 'REFUNDED'
        ELSE 'PENDING'
    END::varchar,
    amount,
    'INR',
    CASE WHEN i <= 150 THEN amount ELSE NULL END,
    CASE WHEN i <= 150 THEN 'INR' ELSE NULL END,
    CASE WHEN i <= 150 THEN (amount * 0.02)::bigint ELSE NULL END,
    CASE WHEN i <= 150 THEN (amount * 0.0036)::bigint ELSE NULL END,
    CASE WHEN i <= 150 THEN (amount - (amount * 0.02)::bigint - (amount * 0.0036)::bigint) ELSE NULL END,
    event_ts,
    CASE WHEN i <= 150 THEN event_ts + INTERVAL '30 seconds' ELSE NULL END,
    event_ts + INTERVAL '1 second',
    -- Assign settlements (roughly 13-14 txns per settlement for first 150)
    CASE
        WHEN i <= 12  THEN 'setl_seed_001'
        WHEN i <= 21  THEN 'setl_seed_002'
        WHEN i <= 35  THEN 'setl_seed_003'
        WHEN i <= 45  THEN 'setl_seed_004'
        WHEN i <= 61  THEN 'setl_seed_005'
        WHEN i <= 69  THEN 'setl_seed_006'
        WHEN i <= 82  THEN 'setl_seed_007'
        WHEN i <= 92  THEN 'setl_seed_008'
        WHEN i <= 107 THEN 'setl_seed_009'
        WHEN i <= 116 THEN 'setl_seed_010'
        WHEN i <= 128 THEN 'setl_seed_011'
        WHEN i <= 139 THEN 'setl_seed_012'
        WHEN i <= 150 THEN 'setl_seed_013'
        ELSE NULL
    END,
    CASE
        WHEN i <= 12  THEN (CURRENT_DATE - 88)
        WHEN i <= 21  THEN (CURRENT_DATE - 81)
        WHEN i <= 35  THEN (CURRENT_DATE - 74)
        WHEN i <= 45  THEN (CURRENT_DATE - 67)
        WHEN i <= 61  THEN (CURRENT_DATE - 60)
        WHEN i <= 69  THEN (CURRENT_DATE - 53)
        WHEN i <= 82  THEN (CURRENT_DATE - 46)
        WHEN i <= 92  THEN (CURRENT_DATE - 39)
        WHEN i <= 107 THEN (CURRENT_DATE - 32)
        WHEN i <= 116 THEN (CURRENT_DATE - 25)
        WHEN i <= 128 THEN (CURRENT_DATE - 18)
        WHEN i <= 139 THEN (CURRENT_DATE - 11)
        WHEN i <= 150 THEN (CURRENT_DATE - 4)
        ELSE NULL
    END,
    CASE
        WHEN i <= 12  THEN 'UTR2025030101'
        WHEN i <= 21  THEN 'UTR2025030801'
        WHEN i <= 35  THEN 'UTR2025031501'
        WHEN i <= 45  THEN 'UTR2025032201'
        WHEN i <= 61  THEN 'UTR2025032901'
        WHEN i <= 69  THEN 'UTR2025040501'
        WHEN i <= 82  THEN 'UTR2025041201'
        WHEN i <= 92  THEN 'UTR2025041901'
        WHEN i <= 107 THEN 'UTR2025042601'
        WHEN i <= 116 THEN 'UTR2025050301'
        WHEN i <= 128 THEN 'UTR2025051001'
        WHEN i <= 139 THEN 'UTR2025051701'
        ELSE NULL
    END,
    -- Payment methods: 40% UPI, 30% card, 15% netbanking, 10% wallet, 5% emandate
    CASE
        WHEN i % 20 IN (0,1,2,3,4,5,6,7) THEN 'upi'
        WHEN i % 20 IN (8,9,10,11,12,13) THEN 'card'
        WHEN i % 20 IN (14,15,16) THEN 'netbanking'
        WHEN i % 20 IN (17,18) THEN 'wallet'
        ELSE 'emandate'
    END,
    -- Payment method detail
    CASE
        WHEN i % 20 IN (0,1,2,3,4,5,6,7) THEN 'upi'
        WHEN i % 20 IN (8,9,10) THEN 'credit'
        WHEN i % 20 IN (11,12,13) THEN 'debit'
        WHEN i % 20 IN (14,15,16) THEN (ARRAY['HDFC', 'ICICI', 'SBI'])[((i-1) % 3) + 1]
        WHEN i % 20 IN (17,18) THEN (ARRAY['paytm', 'phonepe'])[((i-1) % 2) + 1]
        ELSE 'nach'
    END,
    -- card_last4
    CASE WHEN i % 20 IN (8,9,10,11,12,13) THEN LPAD(((i * 1234) % 10000)::text, 4, '0') ELSE NULL END,
    -- card_network
    CASE
        WHEN i % 20 IN (8,9,10) THEN (ARRAY['Visa', 'Mastercard', 'RuPay'])[((i-1) % 3) + 1]
        WHEN i % 20 IN (11,12,13) THEN (ARRAY['Visa', 'RuPay', 'Mastercard'])[((i-1) % 3) + 1]
        ELSE NULL
    END,
    -- bank
    (ARRAY['HDFC', 'ICICI', 'SBI', 'Axis', 'Kotak', 'PNB', 'BOB', 'Yes Bank', 'IndusInd', 'Federal'])[((i-1) % 10) + 1],
    -- vpa
    CASE WHEN i % 20 IN (0,1,2,3,4,5,6,7) THEN 'user' || i || '@' || (ARRAY['upi', 'ybl', 'okaxis', 'paytm', 'ibl'])[((i-1) % 5) + 1] ELSE NULL END,
    -- payer info (cycle through the 30 users)
    (ARRAY['rahul.sharma@gmail.com','priya.patel@yahoo.com','amit.kumar@outlook.com','sneha.gupta@gmail.com','vikram.singh@gmail.com',
           'ananya.reddy@gmail.com','rajesh.verma@hotmail.com','deepika.joshi@gmail.com','arjun.nair@gmail.com','kavita.iyer@yahoo.com',
           'manish.tiwari@gmail.com','pooja.mishra@gmail.com','suresh.menon@gmail.com','nisha.agarwal@gmail.com','karan.mehta@outlook.com',
           'ritu.das@gmail.com','sanjay.chopra@gmail.com','meera.bhat@yahoo.com','aditya.saxena@gmail.com','lakshmi.pillai@gmail.com',
           'harsh.pandey@gmail.com','swati.kapoor@gmail.com','rohit.malhotra@gmail.com','divya.rao@outlook.com','nikhil.bansal@gmail.com',
           'anjali.sinha@gmail.com','vivek.chauhan@gmail.com','isha.dubey@yahoo.com','gaurav.sethi@gmail.com','tanvi.bhatt@gmail.com'])[((i-1) % 30) + 1],
    '98765' || LPAD(((i-1) % 30 + 43210)::text, 5, '0'),
    (ARRAY['Rahul Sharma','Priya Patel','Amit Kumar','Sneha Gupta','Vikram Singh',
           'Ananya Reddy','Rajesh Verma','Deepika Joshi','Arjun Nair','Kavita Iyer',
           'Manish Tiwari','Pooja Mishra','Suresh Menon','Nisha Agarwal','Karan Mehta',
           'Ritu Das','Sanjay Chopra','Meera Bhat','Aditya Saxena','Lakshmi Pillai',
           'Harsh Pandey','Swati Kapoor','Rohit Malhotra','Divya Rao','Nikhil Bansal',
           'Anjali Sinha','Vivek Chauhan','Isha Dubey','Gaurav Sethi','Tanvi Bhatt'])[((i-1) % 30) + 1],
    -- reconciliation_status
    CASE
        WHEN i <= 139 THEN 'MATCHED'
        WHEN i <= 150 THEN 'PENDING_SETTLEMENT'
        WHEN i <= 170 THEN 'PENDING'
        WHEN i <= 180 THEN 'PENDING'
        WHEN i <= 185 THEN 'EXCEPTION'
        WHEN i <= 190 THEN 'MATCHED'
        ELSE 'PENDING'
    END::varchar,
    -- raw_payload
    json_build_object(
        'id', 'pay_seed_' || LPAD(i::text, 4, '0'),
        'entity', 'payment',
        'amount', amount,
        'currency', 'INR',
        'method', CASE WHEN i % 20 IN (0,1,2,3,4,5,6,7) THEN 'upi' WHEN i % 20 IN (8,9,10,11,12,13) THEN 'card' WHEN i % 20 IN (14,15,16) THEN 'netbanking' ELSE 'wallet' END,
        'description', 'Payment for order ORD-SEED-' || LPAD(i::text, 4, '0')
    )::jsonb,
    CASE WHEN i % 10 = 0 THEN json_build_object('internal_ref', 'REF-' || i)::jsonb ELSE NULL END,
    event_ts,
    event_ts + INTERVAL '1 minute'
FROM (
    SELECT
        i,
        (ARRAY[25000, 49900, 99900, 149900, 199900, 249900, 349900, 499900, 599900, 799900,
               999900, 1499900, 1999900, 2499900, 50000, 75000, 125000, 175000, 225000, 299900])[((i - 1) % 20) + 1] AS amount,
        NOW() - (INTERVAL '1 day' * (200 - i)) - (INTERVAL '1 hour' * (i % 12)) AS event_ts
    FROM generate_series(1, 200) AS s(i)
) sub
ON CONFLICT (provider, provider_transaction_id) DO NOTHING;

-- ============================================================================
-- 5. REFUND TRANSACTIONS (15 refunds linked to refunded payments 181-195)
-- ============================================================================
INSERT INTO transactions (
    provider, provider_transaction_id, provider_event_id, merchant_id, order_id,
    provider_order_id, event_type, status, presentment_amount, presentment_currency,
    settlement_amount, settlement_currency, fee_amount, tax_amount, net_amount,
    event_occurred_at, refunded_at, ingested_at,
    payment_method, payer_email, payer_phone, payer_name,
    reconciliation_status, raw_payload, created_at, updated_at
)
SELECT
    'razorpay',
    'rfnd_seed_' || LPAD(i::text, 4, '0'),
    'evt_rfnd_seed_' || LPAD(i::text, 4, '0'),
    'merchant_001',
    'ORD-SEED-' || LPAD((i + 180)::text, 4, '0'),
    'order_rzp_seed_' || LPAD((i + 180)::text, 4, '0'),
    'REFUND',
    'REFUNDED',
    amount,
    'INR',
    amount,
    'INR',
    0,
    0,
    amount,
    NOW() - (INTERVAL '1 day' * (20 - i)) + INTERVAL '2 hours',
    NOW() - (INTERVAL '1 day' * (20 - i)) + INTERVAL '2 hours',
    NOW() - (INTERVAL '1 day' * (20 - i)) + INTERVAL '2 hours 1 second',
    'upi',
    (ARRAY['rahul.sharma@gmail.com','priya.patel@yahoo.com','amit.kumar@outlook.com','sneha.gupta@gmail.com','vikram.singh@gmail.com',
           'ananya.reddy@gmail.com','rajesh.verma@hotmail.com','deepika.joshi@gmail.com','arjun.nair@gmail.com','kavita.iyer@yahoo.com',
           'manish.tiwari@gmail.com','pooja.mishra@gmail.com','suresh.menon@gmail.com','nisha.agarwal@gmail.com','karan.mehta@outlook.com'])[i],
    '9876543' || LPAD((i + 209)::text, 3, '0'),
    (ARRAY['Rahul Sharma','Priya Patel','Amit Kumar','Sneha Gupta','Vikram Singh',
           'Ananya Reddy','Rajesh Verma','Deepika Joshi','Arjun Nair','Kavita Iyer',
           'Manish Tiwari','Pooja Mishra','Suresh Menon','Nisha Agarwal','Karan Mehta'])[i],
    'MATCHED',
    json_build_object('id', 'rfnd_seed_' || LPAD(i::text, 4, '0'), 'entity', 'refund', 'amount', amount, 'payment_id', 'pay_seed_' || LPAD((i + 180)::text, 4, '0'))::jsonb,
    NOW() - (INTERVAL '1 day' * (20 - i)) + INTERVAL '2 hours',
    NOW() - (INTERVAL '1 day' * (20 - i)) + INTERVAL '2 hours 1 minute'
FROM (
    SELECT
        i,
        (ARRAY[25000, 49900, 99900, 149900, 199900, 249900, 349900, 499900, 599900, 799900,
               999900, 1499900, 1999900, 2499900, 50000])[i] AS amount
    FROM generate_series(1, 15) AS s(i)
) sub
ON CONFLICT (provider, provider_transaction_id) DO NOTHING;

-- ============================================================================
-- 6. EXCEPTION RECORDS (20 exceptions for realism)
-- ============================================================================
INSERT INTO exception_records (merchant_id, exception_type, severity, expected_amount,
    actual_amount, discrepancy_amount, currency, description, status,
    resolved_by, resolved_at, resolution_notes, detected_at, updated_at)
VALUES
-- Amount mismatches
('merchant_001', 'AMOUNT_MISMATCH',       'HIGH',   499900, 489900, 10000, 'INR', 'Payment amount does not match order amount for ORD-SEED-0050',           'RESOLVED', 'admin@merchant.com', NOW() - INTERVAL '60 days', 'Customer used partial credit; verified with Razorpay dashboard', NOW() - INTERVAL '65 days', NOW() - INTERVAL '60 days'),
('merchant_001', 'AMOUNT_MISMATCH',       'MEDIUM', 199900, 199000,   900, 'INR', 'Minor amount discrepancy on ORD-SEED-0078',                              'RESOLVED', 'admin@merchant.com', NOW() - INTERVAL '45 days', 'Rounding difference in forex conversion',                        NOW() - INTERVAL '50 days', NOW() - INTERVAL '45 days'),
('merchant_001', 'AMOUNT_MISMATCH',       'HIGH',   999900, 949900, 50000, 'INR', 'Significant shortfall on ORD-SEED-0196',                                 'OPEN',     NULL,                 NULL,                       NULL,                                                             NOW() - INTERVAL '3 days',  NOW() - INTERVAL '3 days'),

-- Fee discrepancies
('merchant_001', 'FEE_DISCREPANCY',       'MEDIUM', 9998,   11200,  1202, 'INR', 'Razorpay charged higher fee than expected rate for pay_seed_0033',        'RESOLVED', 'finance@merchant.com', NOW() - INTERVAL '55 days', 'International card surcharge applied correctly', NOW() - INTERVAL '58 days', NOW() - INTERVAL '55 days'),
('merchant_001', 'FEE_DISCREPANCY',       'LOW',    4998,   5100,    102, 'INR', 'Minor fee variation on pay_seed_0091',                                    'IGNORED',  'admin@merchant.com',   NOW() - INTERVAL '30 days', 'Within acceptable tolerance',                   NOW() - INTERVAL '35 days', NOW() - INTERVAL '30 days'),

-- Settlement discrepancies
('merchant_001', 'SETTLEMENT_DISCREPANCY','HIGH',   5077280, 5067280, 10000, 'INR', 'Settlement setl_seed_001 net amount does not match sum of transactions', 'RESOLVED', 'finance@merchant.com', NOW() - INTERVAL '80 days', 'One chargeback deducted post-settlement', NOW() - INTERVAL '85 days', NOW() - INTERVAL '80 days'),
('merchant_001', 'SETTLEMENT_DISCREPANCY','MEDIUM', 3710320, 3700320, 10000, 'INR', 'Settlement setl_seed_002 has unexplained deduction',                     'RESOLVED', 'admin@merchant.com',   NOW() - INTERVAL '70 days', 'Platform fee adjustment',                 NOW() - INTERVAL '75 days', NOW() - INTERVAL '70 days'),

-- Missing captures
('merchant_001', 'MISSING_CAPTURE',       'HIGH',   349900,  NULL,   349900, 'INR', 'Payment pay_seed_0175 authorized but not captured within 24h',          'OPEN',     NULL, NULL, NULL, NOW() - INTERVAL '15 days', NOW() - INTERVAL '15 days'),
('merchant_001', 'MISSING_CAPTURE',       'HIGH',   499900,  NULL,   499900, 'INR', 'Payment pay_seed_0177 authorized but not captured within 24h',          'OPEN',     NULL, NULL, NULL, NOW() - INTERVAL '12 days', NOW() - INTERVAL '12 days'),
('merchant_001', 'MISSING_CAPTURE',       'MEDIUM', 125000,  NULL,   125000, 'INR', 'Payment pay_seed_0179 stuck in authorized state',                       'IN_REVIEW', NULL, NULL, NULL, NOW() - INTERVAL '10 days', NOW() - INTERVAL '8 days'),

-- Unmatched payments
('merchant_001', 'UNMATCHED_PAYMENT',     'MEDIUM', 250000,  250000,    0,  'INR', 'Payment received but no matching order found in system',                 'OPEN',     NULL, NULL, NULL, NOW() - INTERVAL '5 days',  NOW() - INTERVAL '5 days'),
('merchant_001', 'UNMATCHED_PAYMENT',     'LOW',    75000,   75000,     0,  'INR', 'Duplicate payment notification for already matched transaction',         'RESOLVED', 'admin@merchant.com', NOW() - INTERVAL '20 days', 'Webhook retry; original already processed', NOW() - INTERVAL '25 days', NOW() - INTERVAL '20 days'),

-- Order amount mismatches
('merchant_001', 'ORDER_AMOUNT_MISMATCH', 'HIGH',   2499900, 2549900, 50000, 'INR', 'ORD-SEED-0197 received more than expected amount',                     'OPEN',     NULL, NULL, NULL, NOW() - INTERVAL '2 days',  NOW() - INTERVAL '2 days'),
('merchant_001', 'ORDER_AMOUNT_MISMATCH', 'MEDIUM', 799900,  809900,  10000, 'INR', 'ORD-SEED-0198 slight overpayment detected',                            'IN_REVIEW', NULL, NULL, NULL, NOW() - INTERVAL '1 day',   NOW() - INTERVAL '1 day'),

-- Bank amount mismatches
('merchant_001', 'BANK_AMOUNT_MISMATCH',  'HIGH',   5956040, 5946040, 10000, 'INR', 'Bank credit for setl_seed_003 is less than settlement net amount',     'RESOLVED', 'finance@merchant.com', NOW() - INTERVAL '65 days', 'Bank deducted charges separately', NOW() - INTERVAL '70 days', NOW() - INTERVAL '65 days'),
('merchant_001', 'BANK_AMOUNT_MISMATCH',  'MEDIUM', 4393800, 4393300,   500, 'INR', 'Minor bank credit discrepancy for setl_seed_004',                      'IGNORED',  'admin@merchant.com',   NOW() - INTERVAL '55 days', 'Within Rs 5 tolerance',            NOW() - INTERVAL '60 days', NOW() - INTERVAL '55 days'),

-- Orphan refund
('merchant_001', 'ORPHAN_REFUND',         'HIGH',   149900,  NULL,  149900, 'INR', 'Refund processed but original payment not found in our records',         'OPEN',     NULL, NULL, NULL, NOW() - INTERVAL '7 days',  NOW() - INTERVAL '7 days'),

-- Missing payment
('merchant_001', 'MISSING_PAYMENT',       'MEDIUM', 299900,  NULL,  299900, 'INR', 'Order ORD-SEED-0165 created but no payment attempt recorded',            'OPEN',     NULL, NULL, NULL, NOW() - INTERVAL '8 days',  NOW() - INTERVAL '8 days'),

-- Overdue bank credit
('merchant_001', 'OVERDUE_BANK_CREDIT',   'HIGH',   6151320, NULL, 6151320, 'INR', 'Settlement setl_seed_013 pending bank credit for over 3 business days',  'OPEN',     NULL, NULL, NULL, NOW() - INTERVAL '1 day',   NOW() - INTERVAL '1 day'),

-- Status mismatch
('merchant_001', 'STATUS_MISMATCH',       'MEDIUM', 199900, 199900,     0, 'INR', 'Razorpay shows captured but webhook reported authorized for pay_seed_0183', 'IN_REVIEW', NULL, NULL, NULL, NOW() - INTERVAL '6 days', NOW() - INTERVAL '4 days')
ON CONFLICT DO NOTHING;

-- ============================================================================
-- 7. Update order transaction_id references for matched orders
-- ============================================================================
UPDATE orders o
SET transaction_id = t.id,
    matched_at = t.captured_at
FROM transactions t
WHERE o.merchant_id = 'merchant_001'
  AND t.merchant_id = 'merchant_001'
  AND o.order_id = t.order_id
  AND t.event_type = 'PAYMENT'
  AND t.status = 'CAPTURED'
  AND o.order_id LIKE 'ORD-SEED-%'
  AND o.transaction_id IS NULL;

COMMIT;

-- ============================================================================
-- Summary
-- ============================================================================
DO $$
DECLARE
    txn_count INTEGER;
    order_count INTEGER;
    settlement_count INTEGER;
    user_count INTEGER;
    exception_count INTEGER;
BEGIN
    SELECT COUNT(*) INTO txn_count FROM transactions WHERE merchant_id = 'merchant_001' AND provider_transaction_id LIKE '%seed%';
    SELECT COUNT(*) INTO order_count FROM orders WHERE merchant_id = 'merchant_001' AND order_id LIKE 'ORD-SEED-%';
    SELECT COUNT(*) INTO settlement_count FROM settlements WHERE merchant_id = 'merchant_001' AND provider_settlement_id LIKE 'setl_seed_%';
    SELECT COUNT(*) INTO user_count FROM users WHERE merchant_id = 'merchant_001';
    SELECT COUNT(*) INTO exception_count FROM exception_records WHERE merchant_id = 'merchant_001';

    RAISE NOTICE '=== Seed Data Summary for merchant_001 ===';
    RAISE NOTICE 'Transactions: %', txn_count;
    RAISE NOTICE 'Orders:       %', order_count;
    RAISE NOTICE 'Settlements:  %', settlement_count;
    RAISE NOTICE 'Users:        %', user_count;
    RAISE NOTICE 'Exceptions:   %', exception_count;
END $$;
