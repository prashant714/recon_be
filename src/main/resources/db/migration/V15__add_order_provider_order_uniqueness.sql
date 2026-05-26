ALTER TABLE orders
    ADD CONSTRAINT uq_order_merchant_provider_order_id UNIQUE (merchant_id, provider_order_id);
