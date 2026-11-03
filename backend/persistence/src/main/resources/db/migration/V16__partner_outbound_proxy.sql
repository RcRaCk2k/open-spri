-- M8: the egress proxy (SPRI_OUTBOUND_PROXY) can be switched off per partner (AUFGABE 7.6).
ALTER TABLE partner
    ADD COLUMN use_outbound_proxy BOOLEAN NOT NULL DEFAULT TRUE COMMENT 'send through the egress proxy if configured' AFTER technical_contact_email;
