-- Baseline schema (M0).
-- v1 is single-tenant (decision D-12), but every business table references tenant(id)
-- so that multi-tenancy can be added later without data migration.

CREATE TABLE tenant (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    name        VARCHAR(200) NOT NULL,
    created_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- State of the first-start setup wizard (chapter 5.10). Exactly one row per tenant.
CREATE TABLE setup_state (
    tenant_id     BIGINT       NOT NULL,
    completed     BOOLEAN      NOT NULL DEFAULT FALSE,
    completed_at  TIMESTAMP(3) NULL,
    PRIMARY KEY (tenant_id),
    CONSTRAINT fk_setup_state_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

INSERT INTO tenant (id, name) VALUES (1, 'default');
INSERT INTO setup_state (tenant_id, completed) VALUES (1, FALSE);
