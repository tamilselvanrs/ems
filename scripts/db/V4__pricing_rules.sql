-- V4__pricing_rules.sql
-- Pricing rule table for server-side premium derivation + estimated_premium on endorsement_request

-- 1. Pricing rules table
CREATE TABLE policy_pricing_rule (
    pricing_rule_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    policy_account_id   UUID NOT NULL REFERENCES policy_account(policy_account_id),
    request_type        VARCHAR(20)  NOT NULL CHECK (request_type IN ('ADD', 'DELETE', 'UPDATE')),
    member_type         VARCHAR(20)  NOT NULL CHECK (member_type IN ('SELF', 'DEPENDENT')),
    age_band_min        INTEGER      NOT NULL CHECK (age_band_min >= 0),
    age_band_max        INTEGER      NOT NULL CHECK (age_band_max >= age_band_min),
    gender              VARCHAR(10),  -- NULL means any gender
    base_premium        BIGINT       NOT NULL CHECK (base_premium >= 0),  -- smallest currency unit (paisa)
    is_active           BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_pricing_rule UNIQUE (policy_account_id, request_type, member_type, age_band_min, age_band_max, gender)
);

CREATE INDEX idx_pricing_rule_lookup
    ON policy_pricing_rule (policy_account_id, request_type, member_type, is_active);

-- 2. Add estimated_premium to endorsement_request (nullable for existing rows)
ALTER TABLE endorsement_request ADD COLUMN estimated_premium BIGINT;

-- 3. Seed pricing rules for both policy accounts
-- Policy Account 1: ACME (d1000000-...-001) — REALTIME insurer
INSERT INTO policy_pricing_rule (policy_account_id, request_type, member_type, age_band_min, age_band_max, base_premium) VALUES
    ('d1000000-0000-0000-0000-000000000001', 'ADD', 'SELF',       0, 17,  150000),   -- 1,500 INR
    ('d1000000-0000-0000-0000-000000000001', 'ADD', 'SELF',      18, 35,  250000),   -- 2,500 INR
    ('d1000000-0000-0000-0000-000000000001', 'ADD', 'SELF',      36, 45,  350000),   -- 3,500 INR
    ('d1000000-0000-0000-0000-000000000001', 'ADD', 'SELF',      46, 55,  500000),   -- 5,000 INR
    ('d1000000-0000-0000-0000-000000000001', 'ADD', 'SELF',      56, 65,  750000),   -- 7,500 INR
    ('d1000000-0000-0000-0000-000000000001', 'ADD', 'SELF',      66, 99, 1000000),   -- 10,000 INR
    ('d1000000-0000-0000-0000-000000000001', 'ADD', 'DEPENDENT',  0, 17,  120000),   -- 1,200 INR
    ('d1000000-0000-0000-0000-000000000001', 'ADD', 'DEPENDENT', 18, 35,  200000),   -- 2,000 INR
    ('d1000000-0000-0000-0000-000000000001', 'ADD', 'DEPENDENT', 36, 45,  300000),   -- 3,000 INR
    ('d1000000-0000-0000-0000-000000000001', 'ADD', 'DEPENDENT', 46, 55,  450000),   -- 4,500 INR
    ('d1000000-0000-0000-0000-000000000001', 'ADD', 'DEPENDENT', 56, 65,  650000),   -- 6,500 INR
    ('d1000000-0000-0000-0000-000000000001', 'ADD', 'DEPENDENT', 66, 99,  850000),   -- 8,500 INR
    ('d1000000-0000-0000-0000-000000000001', 'DELETE', 'SELF',       0, 99, 0),
    ('d1000000-0000-0000-0000-000000000001', 'DELETE', 'DEPENDENT',  0, 99, 0);

-- Policy Account 2: TECHSTART (d1000000-...-002) — BATCH insurer
INSERT INTO policy_pricing_rule (policy_account_id, request_type, member_type, age_band_min, age_band_max, base_premium) VALUES
    ('d1000000-0000-0000-0000-000000000002', 'ADD', 'SELF',       0, 17,  130000),   -- 1,300 INR
    ('d1000000-0000-0000-0000-000000000002', 'ADD', 'SELF',      18, 35,  220000),   -- 2,200 INR
    ('d1000000-0000-0000-0000-000000000002', 'ADD', 'SELF',      36, 45,  320000),   -- 3,200 INR
    ('d1000000-0000-0000-0000-000000000002', 'ADD', 'SELF',      46, 55,  470000),   -- 4,700 INR
    ('d1000000-0000-0000-0000-000000000002', 'ADD', 'SELF',      56, 65,  700000),   -- 7,000 INR
    ('d1000000-0000-0000-0000-000000000002', 'ADD', 'SELF',      66, 99,  950000),   -- 9,500 INR
    ('d1000000-0000-0000-0000-000000000002', 'ADD', 'DEPENDENT',  0, 17,  100000),   -- 1,000 INR
    ('d1000000-0000-0000-0000-000000000002', 'ADD', 'DEPENDENT', 18, 35,  180000),   -- 1,800 INR
    ('d1000000-0000-0000-0000-000000000002', 'ADD', 'DEPENDENT', 36, 45,  270000),   -- 2,700 INR
    ('d1000000-0000-0000-0000-000000000002', 'ADD', 'DEPENDENT', 46, 55,  420000),   -- 4,200 INR
    ('d1000000-0000-0000-0000-000000000002', 'ADD', 'DEPENDENT', 56, 65,  600000),   -- 6,000 INR
    ('d1000000-0000-0000-0000-000000000002', 'ADD', 'DEPENDENT', 66, 99,  800000),   -- 8,000 INR
    ('d1000000-0000-0000-0000-000000000002', 'DELETE', 'SELF',       0, 99, 0),
    ('d1000000-0000-0000-0000-000000000002', 'DELETE', 'DEPENDENT',  0, 99, 0);
