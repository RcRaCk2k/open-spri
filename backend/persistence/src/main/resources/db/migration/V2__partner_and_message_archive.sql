-- M1: partners, revision-proof message archive (NF-09) and the inbound queue.

CREATE TABLE partner (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT       NOT NULL,
    name          VARCHAR(200) NOT NULL,
    carrier_code  VARCHAR(20)  NOT NULL COMMENT 'ITU Carrier Code (ICC) of the partner',
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_partner_carrier (tenant_id, carrier_code),
    CONSTRAINT fk_partner_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Certificates accepted for WS-Security signatures of a partner. Several during a certificate change.
CREATE TABLE partner_certificate (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    partner_id     BIGINT        NOT NULL,
    certificate    BLOB          NOT NULL COMMENT 'X.509 certificate, DER',
    subject        VARCHAR(500)  NOT NULL,
    issuer         VARCHAR(500)  NOT NULL,
    serial_number  VARCHAR(100)  NOT NULL,
    not_after      TIMESTAMP(3)  NOT NULL,
    created_at     TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_partner_certificate (partner_id, issuer(250), serial_number),
    CONSTRAINT fk_partner_certificate_partner FOREIGN KEY (partner_id) REFERENCES partner (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Outbound IP addresses of a partner that may call our S/PRI endpoint (AK security document 2.6).
CREATE TABLE partner_source_network (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    partner_id  BIGINT       NOT NULL,
    cidr        VARCHAR(50)  NOT NULL COMMENT 'IPv4/IPv6 network in CIDR notation',
    PRIMARY KEY (id),
    UNIQUE KEY uk_partner_source_network (partner_id, cidr),
    CONSTRAINT fk_partner_source_network_partner FOREIGN KEY (partner_id) REFERENCES partner (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Every SOAP exchange, verbatim (request and response bytes as on the wire).
CREATE TABLE soap_archive (
    id                     BIGINT        NOT NULL AUTO_INCREMENT,
    tenant_id              BIGINT        NOT NULL,
    direction              VARCHAR(10)   NOT NULL COMMENT 'INBOUND or OUTBOUND',
    operation              VARCHAR(30)   NULL     COMMENT 'annehmenAuftrag / annehmenMeldung',
    partner_id             BIGINT        NULL,
    remote_address         VARCHAR(500)  NOT NULL COMMENT 'sender IP (inbound) or endpoint URL (outbound)',
    started_at             TIMESTAMP(3)  NOT NULL,
    duration_ms            BIGINT        NOT NULL,
    teq_code               VARCHAR(4)    NULL,
    rejection_reason       VARCHAR(2000) NULL     COMMENT 'internal only, never sent to the partner',
    signer_subject         VARCHAR(500)  NULL,
    signer_serial          VARCHAR(100)  NULL,
    external_order_number  VARCHAR(20)   NULL,
    request                LONGBLOB      NOT NULL,
    request_sha256         CHAR(64)      NOT NULL,
    response               LONGBLOB      NULL,
    PRIMARY KEY (id),
    KEY ix_soap_archive_order (external_order_number),
    KEY ix_soap_archive_partner_time (partner_id, started_at),
    CONSTRAINT fk_soap_archive_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT fk_soap_archive_partner FOREIGN KEY (partner_id) REFERENCES partner (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Accepted inbound orders/messages waiting for business processing (M2).
CREATE TABLE inbox_message (
    id                     BIGINT       NOT NULL AUTO_INCREMENT,
    archive_id             BIGINT       NOT NULL,
    partner_id             BIGINT       NOT NULL,
    operation              VARCHAR(30)  NOT NULL,
    external_order_number  VARCHAR(20)  NULL,
    payload                LONGBLOB     NOT NULL,
    status                 VARCHAR(20)  NOT NULL DEFAULT 'RECEIVED',
    received_at            TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_inbox_archive (archive_id),
    KEY ix_inbox_status (status, id),
    CONSTRAINT fk_inbox_archive FOREIGN KEY (archive_id) REFERENCES soap_archive (id),
    CONSTRAINT fk_inbox_partner FOREIGN KEY (partner_id) REFERENCES partner (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
