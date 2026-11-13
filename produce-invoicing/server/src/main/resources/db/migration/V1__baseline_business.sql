-- V1 baseline: tenant root only. Invoice, register, lease, payment and stock
-- tables are added by later migrations (Tasks 04/05/10/11/12) — never by
-- editing this file once applied anywhere.
CREATE TABLE business (
    id          uuid        PRIMARY KEY,
    -- PRE abbreviation for PRE-INV-SUF / PRE-N; frozen into issued invoices (docs/11).
    code        varchar(8)  NOT NULL CHECK (code ~ '^[A-Z0-9]{2,8}$'),
    name        text        NOT NULL CHECK (length(trim(name)) > 0),
    currency    char(3)     NOT NULL DEFAULT 'BBD' CHECK (currency = 'BBD'),
    time_zone   text        NOT NULL DEFAULT 'America/Barbados',
    created_at  timestamptz NOT NULL DEFAULT now()
);

COMMENT ON TABLE business IS 'Tenant root. Uniqueness of invoice numbers is scoped by business.id, not by code.';
