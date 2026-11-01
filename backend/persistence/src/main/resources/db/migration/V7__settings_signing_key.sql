-- M4: company settings from the setup wizard and the stored (encrypted) WS-Security signing key.

CREATE TABLE company_settings (
    tenant_id                   BIGINT       NOT NULL,
    name                        VARCHAR(200) NOT NULL,
    street                      VARCHAR(200) NULL,
    postal_code                 VARCHAR(10)  NULL,
    city                        VARCHAR(100) NULL,
    carrier_code                VARCHAR(6)   NOT NULL COMMENT 'ITU Carrier Code (ICC)',
    contact_email               VARCHAR(320) NOT NULL,
    role_auftraggeber           BOOLEAN      NOT NULL,
    role_leistungserbringer     BOOLEAN      NOT NULL,
    est_enabled                 BOOLEAN      NOT NULL,
    diag_enabled                BOOLEAN      NOT NULL,
    connectivity_enabled        BOOLEAN      NOT NULL,
    third_party_orders_enabled  BOOLEAN      NOT NULL,
    wbci_mode                   VARCHAR(20)  NOT NULL,
    public_spri_endpoint        VARCHAR(500) NULL,
    updated_at                  TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (tenant_id),
    CONSTRAINT fk_company_settings_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE signing_key (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id          BIGINT       NOT NULL,
    encrypted_pkcs12   BLOB         NOT NULL COMMENT 'AES-256-GCM with OPENSPRI_MASTER_KEY (not stored here)',
    subject            VARCHAR(500) NOT NULL,
    serial_number      VARCHAR(100) NOT NULL,
    not_after          TIMESTAMP(3) NOT NULL,
    active             BOOLEAN      NOT NULL,
    created_by         VARCHAR(300) NOT NULL,
    created_at         TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_signing_key_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
