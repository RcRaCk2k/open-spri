-- M4: local users, API tokens (only the hash of the secret is stored), audit log.

CREATE TABLE app_user (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id      BIGINT       NOT NULL,
    username       VARCHAR(50)  NOT NULL,
    display_name   VARCHAR(200) NOT NULL,
    role           VARCHAR(20)  NOT NULL,
    password_hash  VARCHAR(200) NOT NULL COMMENT 'Argon2id',
    enabled        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMP(3) NOT NULL,
    last_login_at  TIMESTAMP(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user (tenant_id, username),
    CONSTRAINT fk_user_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE api_token (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    tenant_id      BIGINT        NOT NULL,
    name           VARCHAR(200)  NOT NULL,
    prefix         VARCHAR(20)   NOT NULL COMMENT 'public part of the token, identifies it',
    secret_hash    CHAR(64)      NOT NULL COMMENT 'SHA-256 of the secret part',
    role           VARCHAR(20)   NOT NULL,
    scopes         VARCHAR(1000) NOT NULL COMMENT 'comma separated',
    owner_user_id  BIGINT        NULL,
    created_by     VARCHAR(200)  NOT NULL,
    created_at     TIMESTAMP(3)  NOT NULL,
    expires_at     TIMESTAMP(3)  NULL,
    revoked_at     TIMESTAMP(3)  NULL,
    last_used_at   TIMESTAMP(3)  NULL,
    last_used_ip   VARCHAR(50)   NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_token_prefix (prefix),
    CONSTRAINT fk_token_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT fk_token_owner FOREIGN KEY (owner_user_id) REFERENCES app_user (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE audit_log (
    id         BIGINT        NOT NULL AUTO_INCREMENT,
    tenant_id  BIGINT        NOT NULL,
    at         TIMESTAMP(3)  NOT NULL,
    actor      VARCHAR(300)  NOT NULL,
    action     VARCHAR(100)  NOT NULL,
    target     VARCHAR(300)  NOT NULL,
    details    VARCHAR(2000) NULL,
    PRIMARY KEY (id),
    KEY ix_audit_time (tenant_id, at),
    CONSTRAINT fk_audit_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
