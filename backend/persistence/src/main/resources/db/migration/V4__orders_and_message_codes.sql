-- M2: orders with their lifecycle state, order history, inbox processing state, message code table versions.

-- "order" is a reserved word; the table is called spri_order.
CREATE TABLE spri_order (
    id                      BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id               BIGINT       NOT NULL,
    partner_id              BIGINT       NOT NULL,
    role                    VARCHAR(20)  NOT NULL COMMENT 'AUFTRAGGEBER, LEISTUNGSERBRINGER, ABGEBENDER_EKP',
    external_order_number   VARCHAR(20)  NOT NULL,
    business_case           VARCHAR(10)  NOT NULL,
    -- lifecycle (domain OrderState)
    phase                   VARCHAR(30)  NOT NULL,
    open_requests           VARCHAR(100) NOT NULL DEFAULT '' COMMENT 'comma separated RequestKind',
    cancelled               BOOLEAN      NOT NULL DEFAULT FALSE,
    complaints              INT          NOT NULL DEFAULT 0,
    appointment_requested   BOOLEAN      NOT NULL DEFAULT FALSE,
    connectivity            BOOLEAN      NOT NULL DEFAULT FALSE,
    bracketed               BOOLEAN      NOT NULL DEFAULT FALSE,
    handover_phase          VARCHAR(20)  NULL,
    handover_only           BOOLEAN      NOT NULL DEFAULT FALSE,
    finished                BOOLEAN      NOT NULL DEFAULT FALSE,
    -- business content (domain OrderData)
    client_number           VARCHAR(10)  NULL,
    service_number          VARCHAR(10)  NULL,
    orderer_client_number   VARCHAR(10)  NULL,
    orderer_service_number  VARCHAR(10)  NULL,
    desired_date            DATE         NULL,
    contract_number         VARCHAR(10)  NULL,
    line_id                 VARCHAR(21)  NULL,
    pre_agreement_id        VARCHAR(21)  NULL,
    home_id                 VARCHAR(50)  NULL,
    products                VARCHAR(1000) NULL COMMENT 'product identifiers, separated by |',
    project_id              VARCHAR(50)  NULL,
    coupling_id             VARCHAR(50)  NULL,
    bracket_id              VARCHAR(50)  NULL,
    bracket_size            INT          NULL,
    ont_serial              VARCHAR(50)  NULL,
    infrastructure_key      VARCHAR(300) NULL COMMENT 'R-10: LineID, HomeID or normalized address',
    created_at              TIMESTAMP(3) NOT NULL,
    updated_at              TIMESTAMP(3) NOT NULL,
    version                 BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order (partner_id, role, external_order_number),
    KEY ix_order_infrastructure (role, infrastructure_key, finished),
    KEY ix_order_line (line_id),
    CONSTRAINT fk_order_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT fk_order_partner FOREIGN KEY (partner_id) REFERENCES partner (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE spri_order_history (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    order_id          BIGINT        NULL COMMENT 'NULL if no order could be found (finding)',
    partner_id        BIGINT        NOT NULL,
    archive_id        BIGINT        NULL,
    kind              VARCHAR(10)   NOT NULL COMMENT 'ORDER, REQUEST, MESSAGE',
    message_type      VARCHAR(10)   NULL,
    change_indicator  VARCHAR(20)   NOT NULL,
    codes             VARCHAR(200)  NULL,
    findings          VARCHAR(4000) NULL COMMENT 'results of the checks, separated by line breaks',
    created_at        TIMESTAMP(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY ix_history_order (order_id, id),
    CONSTRAINT fk_history_order FOREIGN KEY (order_id) REFERENCES spri_order (id),
    CONSTRAINT fk_history_archive FOREIGN KEY (archive_id) REFERENCES soap_archive (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

ALTER TABLE inbox_message
    ADD COLUMN processed_at TIMESTAMP(3)  NULL AFTER received_at,
    ADD COLUMN findings     VARCHAR(4000) NULL AFTER processed_at,
    ADD COLUMN error        VARCHAR(2000) NULL AFTER findings;

-- Versions of the message code table (Anlage 5). The original file is kept so that every version can be
-- reproduced; exactly one version is active.
CREATE TABLE message_code_table (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id    BIGINT       NOT NULL,
    source       VARCHAR(300) NOT NULL,
    sha256       CHAR(64)     NOT NULL,
    code_count   INT          NOT NULL,
    content      LONGBLOB     NOT NULL,
    active       BOOLEAN      NOT NULL DEFAULT FALSE,
    imported_at  TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_message_code_table (tenant_id, sha256),
    CONSTRAINT fk_message_code_table_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
