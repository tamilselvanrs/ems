-- V1__control_plane_schema.sql
-- Control Plane: employer, policy_account, insurer, insurer_config

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ─────────────────────────────────────────
-- EMPLOYER
-- ─────────────────────────────────────────
CREATE TABLE employer (
    employer_id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_key              VARCHAR(100) NOT NULL UNIQUE,
    legal_name              VARCHAR(255) NOT NULL,
    display_name            VARCHAR(255),
    status                  VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    primary_contact_email   VARCHAR(255) NOT NULL,
    timezone                VARCHAR(100) NOT NULL DEFAULT 'UTC',
    country_code            CHAR(2) NOT NULL,
    metadata                JSONB,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_employer_tenant_key ON employer(tenant_key);
CREATE INDEX idx_employer_status ON employer(status);

-- ─────────────────────────────────────────
-- INSURER
-- ─────────────────────────────────────────
CREATE TABLE insurer (
    insurer_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    insurer_code    VARCHAR(50) NOT NULL UNIQUE,
    name            VARCHAR(255) NOT NULL,
    status          VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    country_code    CHAR(2) NOT NULL,
    metadata        JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ─────────────────────────────────────────
-- INSURER CONFIG
-- ─────────────────────────────────────────
CREATE TABLE insurer_config (
    insurer_config_id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    insurer_id                  UUID NOT NULL REFERENCES insurer(insurer_id),
    execution_mode              VARCHAR(20) NOT NULL CHECK (execution_mode IN ('REALTIME', 'BATCH')),
    supports_webhook            BOOLEAN NOT NULL DEFAULT FALSE,
    supports_balance_snapshot   BOOLEAN NOT NULL DEFAULT FALSE,
    supports_item_level_status  BOOLEAN NOT NULL DEFAULT FALSE,
    qps_limit                   INTEGER NOT NULL DEFAULT 10,
    batch_max_items             INTEGER,
    batch_cutoff_time           TIME,
    backdate_window_days        INTEGER NOT NULL DEFAULT 30,
    reconciliation_mode         VARCHAR(20) DEFAULT 'FILE',
    reconciliation_source_uri   VARCHAR(500),
    reconciliation_file_pattern VARCHAR(255),
    schema_version              VARCHAR(20),
    credentials_secret_ref      VARCHAR(255),
    status                      VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    config_json                 JSONB,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (insurer_id)
);

CREATE INDEX idx_insurer_config_insurer_id ON insurer_config(insurer_id);

-- ─────────────────────────────────────────
-- POLICY ACCOUNT
-- ─────────────────────────────────────────
CREATE TABLE policy_account (
    policy_account_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    employer_id             UUID NOT NULL REFERENCES employer(employer_id),
    insurer_id              UUID NOT NULL REFERENCES insurer(insurer_id),
    policy_number           VARCHAR(100) NOT NULL,
    policy_type             VARCHAR(50) NOT NULL,
    policy_start_date       DATE NOT NULL,
    policy_end_date         DATE,
    endorsement_account_ref VARCHAR(255),
    currency_code           CHAR(3) NOT NULL DEFAULT 'INR',
    endorsement_mode        VARCHAR(20) NOT NULL CHECK (endorsement_mode IN ('REALTIME', 'BATCH')),
    status                  VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    metadata                JSONB,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (employer_id, policy_number)
);

CREATE INDEX idx_policy_account_employer_id ON policy_account(employer_id);
CREATE INDEX idx_policy_account_insurer_id ON policy_account(insurer_id);
CREATE INDEX idx_policy_account_status ON policy_account(status);

-- ─────────────────────────────────────────
-- POLICY MEMBER
-- ─────────────────────────────────────────
CREATE TABLE policy_member (
    policy_member_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    policy_account_id       UUID NOT NULL REFERENCES policy_account(policy_account_id),
    employee_code           VARCHAR(100) NOT NULL,
    member_type             VARCHAR(20) NOT NULL CHECK (member_type IN ('SELF', 'DEPENDENT')),
    parent_employee_code    VARCHAR(100),
    full_name               VARCHAR(255) NOT NULL,
    dob                     DATE NOT NULL,
    gender                  VARCHAR(10),
    relationship_type       VARCHAR(50),
    join_date               DATE NOT NULL,
    exit_date               DATE,
    status                  VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    dedupe_key              VARCHAR(255) NOT NULL,
    attributes              JSONB,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (policy_account_id, dedupe_key)
);

CREATE INDEX idx_policy_member_policy_account_id ON policy_member(policy_account_id);
CREATE INDEX idx_policy_member_employee_code ON policy_member(policy_account_id, employee_code);
CREATE INDEX idx_policy_member_status ON policy_member(status);

-- ─────────────────────────────────────────
-- ALERT CONFIG
-- ─────────────────────────────────────────
CREATE TABLE alert_config (
    alert_config_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    policy_account_id   UUID NOT NULL REFERENCES policy_account(policy_account_id),
    alert_type          VARCHAR(50) NOT NULL,
    threshold_value     BIGINT,
    notification_email  VARCHAR(255),
    is_active           BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_alert_config_policy_account_id ON alert_config(policy_account_id);
