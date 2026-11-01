-- M4: direction of history entries, event feed, webhooks (D-14) and idempotency keys (AUFGABE 8.5).

ALTER TABLE spri_order_history
    ADD COLUMN direction VARCHAR(10) NULL COMMENT 'INBOUND or OUTBOUND' AFTER kind;
-- outbound entries have no archive id (the signed request is archived on sending)
UPDATE spri_order_history SET direction = IF(archive_id IS NULL, 'OUTBOUND', 'INBOUND');
ALTER TABLE spri_order_history MODIFY direction VARCHAR(10) NOT NULL COMMENT 'INBOUND or OUTBOUND';

CREATE TABLE event (
    id                     BIGINT        NOT NULL AUTO_INCREMENT,
    tenant_id              BIGINT        NOT NULL,
    type                   VARCHAR(40)   NOT NULL COMMENT 'public name, e.g. message.received',
    partner_id             BIGINT        NOT NULL,
    order_id               BIGINT        NULL,
    role                   VARCHAR(20)   NULL,
    external_order_number  VARCHAR(40)   NULL,
    business_case          VARCHAR(10)   NULL,
    phase                  VARCHAR(30)   NULL,
    message_type           VARCHAR(10)   NULL,
    change_indicator       VARCHAR(20)   NULL,
    codes                  VARCHAR(200)  NULL,
    findings               VARCHAR(4000) NULL,
    delivery_id            BIGINT        NULL,
    delivery_status        VARCHAR(30)   NULL,
    created_at             TIMESTAMP(3)  NOT NULL,
    inserted_at            TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'database time; the feed shows events after a short delay',
    PRIMARY KEY (id),
    KEY ix_event_tenant (tenant_id, id),
    KEY ix_event_created (created_at),
    CONSTRAINT fk_event_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE webhook_subscription (
    id                BIGINT         NOT NULL AUTO_INCREMENT,
    tenant_id         BIGINT         NOT NULL,
    url               VARCHAR(1000)  NOT NULL,
    secret_encrypted  VARBINARY(512) NOT NULL COMMENT 'HMAC secret, AES-256-GCM with OPENSPRI_MASTER_KEY',
    types             VARCHAR(1000)  NULL COMMENT 'comma separated event types, NULL = all',
    enabled           BOOLEAN        NOT NULL,
    description       VARCHAR(200)   NULL,
    last_event_id     BIGINT         NOT NULL COMMENT 'delivery cursor',
    failures          INT            NOT NULL DEFAULT 0,
    next_attempt_at   TIMESTAMP(3)   NOT NULL,
    last_error        VARCHAR(2000)  NULL,
    last_success_at   TIMESTAMP(3)   NULL,
    created_at        TIMESTAMP(3)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_webhook_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE idempotency_key (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    actor            VARCHAR(200)  NOT NULL COMMENT 'user or token that sent the key',
    idem_key         VARCHAR(200)  NOT NULL,
    request_sha256   CHAR(64)      NOT NULL COMMENT 'method, path and body',
    status           INT           NULL COMMENT 'NULL while the request is running',
    response         MEDIUMBLOB    NULL,
    content_type     VARCHAR(100)  NULL,
    created_at       TIMESTAMP(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_idempotency (actor, idem_key),
    KEY ix_idempotency_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
