-- M4: our outbound IP addresses for the partner onboarding sheet (AUFGABE 7.6).
ALTER TABLE company_settings ADD COLUMN outbound_addresses VARCHAR(1000) NULL COMMENT 'comma separated IP addresses' AFTER public_spri_endpoint;
