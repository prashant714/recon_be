CREATE TABLE audit_logs (
    id              BIGSERIAL PRIMARY KEY,
    actor           VARCHAR(60) NOT NULL,
    action          VARCHAR(60) NOT NULL,
    entity_type     VARCHAR(30),
    entity_id       BIGINT,
    old_value       JSONB,
    new_value       JSONB,
    ip_address      INET,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_entity ON audit_logs (entity_type, entity_id);
CREATE INDEX idx_audit_created ON audit_logs (created_at DESC);
CREATE INDEX idx_audit_actor ON audit_logs (actor, created_at DESC);
