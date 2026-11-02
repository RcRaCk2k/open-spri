-- M6: availability of the partners as Auftraggeber (AUFGABE 5.4): sources (Replikat download or online query),
-- import log and the imported locations.

CREATE TABLE availability_source (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    tenant_id   BIGINT        NOT NULL,
    partner_id  BIGINT        NOT NULL,
    kind        VARCHAR(10)   NOT NULL COMMENT 'REPLIKAT or ONLINE',
    url         VARCHAR(500)  NOT NULL,
    username    VARCHAR(200)  NULL,
    secret      VARCHAR(2000) NULL COMMENT 'password or token, encrypted with the master key (base64)',
    delta       BOOLEAN       NOT NULL DEFAULT FALSE,
    enabled     BOOLEAN       NOT NULL DEFAULT TRUE,
    PRIMARY KEY (id),
    KEY ix_source_partner (partner_id),
    CONSTRAINT fk_source_partner FOREIGN KEY (partner_id) REFERENCES partner (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE replikat_import (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    source_id      BIGINT        NOT NULL,
    partner_id     BIGINT        NOT NULL,
    started_at     TIMESTAMP(3)  NOT NULL,
    finished_at    TIMESTAMP(3)  NOT NULL,
    status         VARCHAR(10)   NOT NULL COMMENT 'OK or FAILED',
    file_name      VARCHAR(100)  NULL,
    replikat_date  DATE          NULL,
    delta          BOOLEAN       NOT NULL DEFAULT FALSE,
    records        INT           NOT NULL DEFAULT 0,
    added          INT           NOT NULL DEFAULT 0,
    changed        INT           NOT NULL DEFAULT 0,
    deleted        INT           NOT NULL DEFAULT 0,
    warnings       VARCHAR(4000) NULL COMMENT 'separated by line breaks',
    error          VARCHAR(4000) NULL,
    PRIMARY KEY (id),
    KEY ix_import_source (source_id, id),
    CONSTRAINT fk_import_source FOREIGN KEY (source_id) REFERENCES availability_source (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE partner_location (
    id                   BIGINT        NOT NULL AUTO_INCREMENT,
    partner_id           BIGINT        NOT NULL,
    location_key         CHAR(64)      NOT NULL COMMENT 'SHA-256 of LocationKey (SA-016)',
    location             MEDIUMTEXT    NOT NULL COMMENT 'record as JSON with the element names of Replikat.xsd 1.7',
    postal_code          VARCHAR(5)    NOT NULL,
    city                 VARCHAR(40)   NOT NULL,
    street               VARCHAR(40)   NOT NULL,
    house_number         VARCHAR(4)    NOT NULL,
    house_number_suffix  VARCHAR(6)    NULL,
    address_id           VARCHAR(36)   NULL,
    home_ids             VARCHAR(2000) NULL,
    latitude             DOUBLE        NULL COMMENT 'parsed from geo/lat, for the search by coordinates',
    longitude            DOUBLE        NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_partner_location (partner_id, location_key),
    KEY ix_partner_location_address (partner_id, postal_code, street, house_number),
    KEY ix_partner_location_geo (latitude, longitude),
    CONSTRAINT fk_partner_location_partner FOREIGN KEY (partner_id) REFERENCES partner (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
