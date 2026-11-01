-- M5: inventory (AUFGABE 8.4 inventory_contract, built from completed orders) and the abgebender EKP of a provider
-- change in which we are Leistungserbringer (5.5.1).

CREATE TABLE inventory_contract (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    tenant_id        BIGINT        NOT NULL,
    role             VARCHAR(20)   NOT NULL COMMENT 'our role: AUFTRAGGEBER or LEISTUNGSERBRINGER',
    partner_id       BIGINT        NOT NULL,
    contract_number  VARCHAR(10)   NOT NULL,
    line_id          VARCHAR(21)   NOT NULL,
    client_number    VARCHAR(10)   NULL,
    service_number   VARCHAR(10)   NULL,
    products         VARCHAR(1000) NULL COMMENT 'product identifiers, separated by |',
    order_id         BIGINT        NULL COMMENT 'order that created the contract',
    started_at       TIMESTAMP(3)  NOT NULL,
    ended_at         TIMESTAMP(3)  NULL,
    PRIMARY KEY (id),
    KEY ix_inventory_line (role, line_id, ended_at),
    KEY ix_inventory_partner (partner_id),
    CONSTRAINT fk_inventory_partner FOREIGN KEY (partner_id) REFERENCES partner (id),
    CONSTRAINT fk_inventory_order FOREIGN KEY (order_id) REFERENCES spri_order (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

ALTER TABLE spri_order
    ADD COLUMN handover_partner_id BIGINT NULL COMMENT 'PV as LE: abgebender EKP' AFTER handover_only,
    ADD COLUMN handover_contract_number VARCHAR(10) NULL AFTER handover_partner_id,
    ADD COLUMN handover_client_number VARCHAR(10) NULL AFTER handover_contract_number;
