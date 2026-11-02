-- M6: own availabilities as Leistungserbringer (D-13), Replikat releases, provided Replikate (31 days) and the export
-- state per partner (basis of change marks and delta). Partner tokens for Replikat download and online query.

CREATE TABLE availability_site (
    id                   BIGINT        NOT NULL AUTO_INCREMENT,
    tenant_id            BIGINT        NOT NULL,
    location_key         CHAR(64)      NOT NULL COMMENT 'SHA-256 of LocationKey (SA-016)',
    location             MEDIUMTEXT    NOT NULL COMMENT 'record as JSON with the element names of Replikat.xsd 1.7',
    postal_code          VARCHAR(5)    NOT NULL,
    city                 VARCHAR(40)   NOT NULL,
    street               VARCHAR(40)   NOT NULL,
    house_number         VARCHAR(4)    NOT NULL,
    house_number_suffix  VARCHAR(6)    NULL,
    address_id           VARCHAR(36)   NULL,
    home_ids             VARCHAR(2000) NULL COMMENT 'comma separated, for the search',
    updated_at           TIMESTAMP(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_site_key (tenant_id, location_key),
    KEY ix_site_address (tenant_id, postal_code, street, house_number),
    KEY ix_site_aid (tenant_id, address_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE replikat_release (
    partner_id      BIGINT        NOT NULL,
    enabled         BOOLEAN       NOT NULL DEFAULT TRUE,
    delta           BOOLEAN       NOT NULL DEFAULT TRUE,
    omitted_fields  VARCHAR(1000) NULL COMMENT 'comma separated Replikat element names',
    quotas          VARCHAR(1000) NULL COMMENT 'comma separated kont values',
    PRIMARY KEY (partner_id),
    CONSTRAINT fk_release_partner FOREIGN KEY (partner_id) REFERENCES partner (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE replikat_snapshot (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    partner_id      BIGINT        NOT NULL,
    replikat_date   DATE          NOT NULL,
    kind            VARCHAR(5)    NOT NULL COMMENT 'FULL or DELTA',
    file_name       VARCHAR(100)  NOT NULL,
    meta_file_name  VARCHAR(100)  NOT NULL,
    md5             CHAR(32)      NOT NULL,
    records         INT           NOT NULL,
    size            BIGINT        NOT NULL,
    content         LONGBLOB      NOT NULL COMMENT 'ZIP with the Replikat XML',
    meta            BLOB          NOT NULL,
    created_at      TIMESTAMP(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_snapshot_file (partner_id, file_name),
    KEY ix_snapshot_date (replikat_date),
    CONSTRAINT fk_snapshot_partner FOREIGN KEY (partner_id) REFERENCES partner (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE replikat_export_state (
    partner_id    BIGINT      NOT NULL,
    location_key  CHAR(64)    NOT NULL,
    location      MEDIUMTEXT  NOT NULL COMMENT 'record as last provided (JSON)',
    PRIMARY KEY (partner_id, location_key),
    CONSTRAINT fk_export_state_partner FOREIGN KEY (partner_id) REFERENCES partner (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

ALTER TABLE api_token
    ADD COLUMN partner_id BIGINT NULL COMMENT 'role PARTNER: Replikat download and online query' AFTER owner_user_id,
    ADD CONSTRAINT fk_token_partner FOREIGN KEY (partner_id) REFERENCES partner (id);
