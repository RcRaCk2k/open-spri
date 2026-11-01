-- M3: transactional outbox with ordering per sequence key, partner outages, multi-instance inbox.

ALTER TABLE partner
    ADD COLUMN endpoint_url            VARCHAR(500) NULL COMMENT 'S/PRI endpoint of the partner (WSDL service address)' AFTER carrier_code,
    ADD COLUMN technical_contact_email VARCHAR(320) NULL COMMENT 'outage notification and e-mail fallback (11.2.3.2)' AFTER endpoint_url;

ALTER TABLE spri_order
    ADD COLUMN sequence_key VARCHAR(100) NULL COMMENT 'outbox ordering key, fixed with the first outbound request' AFTER infrastructure_key;

CREATE TABLE outbox_message (
    id                     BIGINT        NOT NULL AUTO_INCREMENT,
    tenant_id              BIGINT        NOT NULL,
    partner_id             BIGINT        NOT NULL,
    order_id               BIGINT        NULL,
    request_type           VARCHAR(10)   NOT NULL COMMENT 'ORDER or MESSAGE',
    operation              VARCHAR(30)   NOT NULL,
    external_order_number  VARCHAR(20)   NULL,
    sequence_key           VARCHAR(100)  NOT NULL,
    payload                LONGBLOB      NOT NULL COMMENT 'unsigned payload element; signed when sent',
    status                 VARCHAR(25)   NOT NULL,
    attempts               INT           NOT NULL DEFAULT 0,
    next_attempt_at        TIMESTAMP(3)  NOT NULL,
    last_error             VARCHAR(2000) NULL,
    teq_code               VARCHAR(4)    NULL,
    created_at             TIMESTAMP(3)  NOT NULL,
    finished_at            TIMESTAMP(3)  NULL,
    PRIMARY KEY (id),
    KEY ix_outbox_due (status, next_attempt_at),
    KEY ix_outbox_key (sequence_key, id),
    CONSTRAINT fk_outbox_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT fk_outbox_partner FOREIGN KEY (partner_id) REFERENCES partner (id),
    CONSTRAINT fk_outbox_order FOREIGN KEY (order_id) REFERENCES spri_order (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE partner_outage (
    partner_id        BIGINT       NOT NULL,
    since             TIMESTAMP(3) NOT NULL,
    last_notified_at  TIMESTAMP(3) NULL,
    PRIMARY KEY (partner_id),
    CONSTRAINT fk_outage_partner FOREIGN KEY (partner_id) REFERENCES partner (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- inbox: ordering key (partner + external order number) for multi-instance processing
ALTER TABLE inbox_message
    ADD COLUMN sequence_key VARCHAR(100) NULL AFTER external_order_number,
    ADD KEY ix_inbox_key (sequence_key, id);
UPDATE inbox_message SET sequence_key = CONCAT(partner_id, ':', COALESCE(external_order_number, CONCAT('#', id)));
