-- M2: observations that did not lead to a rejection (e.g. Anlage 1 violations in report mode, ADR-0006).
ALTER TABLE soap_archive ADD COLUMN findings VARCHAR(4000) NULL COMMENT 'internal only, never sent to the partner' AFTER rejection_reason;
