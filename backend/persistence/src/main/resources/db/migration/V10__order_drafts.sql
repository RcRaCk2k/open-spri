-- M4: drafts of our own orders (AUFGABE 5.6: Entwurf → Senden). The order row has phase DRAFT and reserves the
-- external order number; the content as entered (REST: the `auftrag` JSON) is kept here until it is sent.

CREATE TABLE spri_order_draft (
    order_id    BIGINT       NOT NULL,
    content     LONGBLOB     NOT NULL COMMENT 'auftrag JSON as entered',
    updated_at  TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (order_id),
    CONSTRAINT fk_draft_order FOREIGN KEY (order_id) REFERENCES spri_order (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
