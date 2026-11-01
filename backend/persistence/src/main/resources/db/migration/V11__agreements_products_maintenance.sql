-- M4: agreements per partner (AUFGABE 5.7, 5.9, R-11, R-12), product catalog and change matrix (Anlage 4/6),
-- maintenance announcements (NF-08).

CREATE TABLE partner_contract (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    partner_id      BIGINT       NOT NULL,
    role            VARCHAR(20)  NOT NULL COMMENT 'our role: AUFTRAGGEBER or LEISTUNGSERBRINGER',
    client_number   VARCHAR(10)  NOT NULL COMMENT 'Auftraggebernummer',
    service_number  VARCHAR(10)  NULL COMMENT 'Leistungsnummer',
    description     VARCHAR(500) NULL,
    valid_from      DATE         NULL,
    valid_to        DATE         NULL,
    PRIMARY KEY (id),
    KEY ix_contract_partner (partner_id),
    CONSTRAINT fk_contract_partner FOREIGN KEY (partner_id) REFERENCES partner (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE partner_power_of_attorney (
    id                      BIGINT        NOT NULL AUTO_INCREMENT,
    partner_id              BIGINT        NOT NULL COMMENT 'partner of the Besteller (sends the orders)',
    orderer_client_number   VARCHAR(10)   NOT NULL,
    orderer_service_number  VARCHAR(10)   NOT NULL,
    client_number           VARCHAR(10)   NOT NULL COMMENT 'Auftraggeber who grants the power',
    service_number          VARCHAR(10)   NOT NULL,
    description             VARCHAR(500)  NULL,
    valid_from              DATE          NULL,
    valid_to                DATE          NULL,
    document_name           VARCHAR(200)  NULL,
    document_type           VARCHAR(100)  NULL,
    document                LONGBLOB      NULL COMMENT 'signed power of attorney (personal data of the signatories)',
    PRIMARY KEY (id),
    KEY ix_poa_partner (partner_id),
    CONSTRAINT fk_poa_partner FOREIGN KEY (partner_id) REFERENCES partner (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE partner_project (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    partner_id   BIGINT       NOT NULL,
    project_id   VARCHAR(50)  NOT NULL COMMENT 'Projektkenner',
    description  VARCHAR(500) NULL,
    valid_from   DATE         NULL,
    valid_to     DATE         NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_project (partner_id, project_id),
    CONSTRAINT fk_project_partner FOREIGN KEY (partner_id) REFERENCES partner (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE product (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id      BIGINT       NOT NULL,
    partner_id     BIGINT       NULL COMMENT 'NULL = all partners',
    role           VARCHAR(20)  NOT NULL COMMENT 'AUFTRAGGEBER: product of the partner, LEISTUNGSERBRINGER: our product',
    identifier     VARCHAR(100) NOT NULL COMMENT 'Produktbezeichner',
    product_group  VARCHAR(20)  NULL COMMENT 'e.g. FTTH, FTTB, FTTC',
    description    VARCHAR(500) NULL,
    active         BOOLEAN      NOT NULL DEFAULT TRUE,
    PRIMARY KEY (id),
    KEY ix_product_partner (partner_id, role),
    CONSTRAINT fk_product_partner FOREIGN KEY (partner_id) REFERENCES partner (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE product_change (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id      BIGINT       NOT NULL,
    partner_id     BIGINT       NULL COMMENT 'NULL = default for all partners',
    from_product   VARCHAR(100) NOT NULL,
    to_product     VARCHAR(100) NOT NULL,
    business_case  VARCHAR(10)  NOT NULL COMMENT 'LAE or AEN-LMAE (Anlage 6)',
    PRIMARY KEY (id),
    KEY ix_change_partner (partner_id),
    CONSTRAINT fk_change_partner FOREIGN KEY (partner_id) REFERENCES partner (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE maintenance_announcement (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    tenant_id    BIGINT        NOT NULL,
    starts_at    TIMESTAMP(3)  NOT NULL,
    ends_at      TIMESTAMP(3)  NOT NULL,
    subject      VARCHAR(200)  NOT NULL,
    text         TEXT          NOT NULL,
    partner_ids  VARCHAR(2000) NOT NULL COMMENT 'comma separated',
    recipients   INT           NOT NULL,
    warnings     VARCHAR(4000) NULL COMMENT 'separated by line breaks',
    created_at   TIMESTAMP(3)  NOT NULL,
    created_by   VARCHAR(200)  NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
