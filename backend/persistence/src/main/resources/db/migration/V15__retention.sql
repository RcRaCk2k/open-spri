-- M8: retention of end customer data (NF-10, ADR-0010). After the configured period the message contents of
-- completed business are removed; metadata, TEQ and the SHA-256 of the request stay as evidence (NF-09).

ALTER TABLE soap_archive
    ADD COLUMN content_purged_at TIMESTAMP(3) NULL COMMENT 'request removed after the retention period; request_sha256 remains',
    ADD KEY ix_soap_archive_time (started_at);

ALTER TABLE inbox_message
    ADD COLUMN content_purged_at TIMESTAMP(3) NULL COMMENT 'payload removed after the retention period';

ALTER TABLE outbox_message
    ADD COLUMN content_purged_at TIMESTAMP(3) NULL COMMENT 'payload removed after the retention period',
    ADD KEY ix_outbox_created (created_at);
