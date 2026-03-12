-- V2__data_plane_schema.sql
-- Data Plane: endorsement lifecycle, ledger, batch, reconciliation, audit

-- ─────────────────────────────────────────
-- ENDORSEMENT REQUEST
-- ─────────────────────────────────────────
CREATE TABLE endorsement_request (
    endorsement_request_id  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    policy_account_id       UUID NOT NULL REFERENCES policy_account(policy_account_id),
    policy_member_id        UUID REFERENCES policy_member(policy_member_id),
    request_type            VARCHAR(20) NOT NULL CHECK (request_type IN ('ADD', 'DELETE', 'UPDATE')),
    effective_date          DATE NOT NULL,
    requested_by_actor      VARCHAR(50) NOT NULL CHECK (requested_by_actor IN ('EMPLOYER', 'EMPLOYEE', 'SYSTEM')),
    requested_by_id         VARCHAR(255) NOT NULL,
    priority                INTEGER NOT NULL DEFAULT 5,
    submission_mode         VARCHAR(20) NOT NULL CHECK (submission_mode IN ('REALTIME', 'BATCH')),
    current_status          VARCHAR(50) NOT NULL DEFAULT 'DRAFT',
    retry_count             INTEGER NOT NULL DEFAULT 0,
    last_error_code         VARCHAR(100),
    last_error_message      TEXT,
    idempotency_key         VARCHAR(255) NOT NULL,
    source_ref              VARCHAR(255),
    payload                 JSONB NOT NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (policy_account_id, idempotency_key)
);

CREATE INDEX idx_er_policy_account_id ON endorsement_request(policy_account_id);
CREATE INDEX idx_er_policy_member_id ON endorsement_request(policy_member_id);
CREATE INDEX idx_er_current_status ON endorsement_request(current_status);
CREATE INDEX idx_er_effective_date ON endorsement_request(effective_date);
CREATE INDEX idx_er_submission_mode_status ON endorsement_request(submission_mode, current_status);

-- ─────────────────────────────────────────
-- ENDORSEMENT JOURNAL (immutable event log)
-- ─────────────────────────────────────────
CREATE TABLE endorsement_journal (
    endorsement_journal_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    endorsement_request_id      UUID NOT NULL REFERENCES endorsement_request(endorsement_request_id),
    policy_account_id           UUID NOT NULL,
    event_type                  VARCHAR(100) NOT NULL,
    event_source                VARCHAR(100) NOT NULL,
    insurer_submission_id       UUID,
    batch_request_id            UUID,
    event_time                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    event_payload               JSONB
);

CREATE INDEX idx_ej_endorsement_request_id ON endorsement_journal(endorsement_request_id);
CREATE INDEX idx_ej_policy_account_id ON endorsement_journal(policy_account_id);
CREATE INDEX idx_ej_event_time ON endorsement_journal(event_time);

-- ─────────────────────────────────────────
-- MEMBER COVERAGE STATE
-- ─────────────────────────────────────────
CREATE TABLE member_coverage_state (
    member_coverage_state_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    policy_member_id                UUID NOT NULL REFERENCES policy_member(policy_member_id),
    policy_account_id               UUID NOT NULL,
    coverage_status                 VARCHAR(50) NOT NULL,
    coverage_valid_from             DATE,
    coverage_valid_to               DATE,
    source_endorsement_request_id   UUID REFERENCES endorsement_request(endorsement_request_id),
    confirmation_status             VARCHAR(50),
    last_confirmed_at               TIMESTAMPTZ,
    notes                           TEXT,
    created_at                      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_mcs_policy_member_id ON member_coverage_state(policy_member_id);
CREATE INDEX idx_mcs_policy_account_id ON member_coverage_state(policy_account_id);

-- ─────────────────────────────────────────
-- INSURER SUBMISSION
-- ─────────────────────────────────────────
CREATE TABLE insurer_submission (
    insurer_submission_id   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    policy_account_id       UUID NOT NULL REFERENCES policy_account(policy_account_id),
    endorsement_request_id  UUID NOT NULL REFERENCES endorsement_request(endorsement_request_id),
    batch_request_id        UUID,
    insurer_id              UUID NOT NULL REFERENCES insurer(insurer_id),
    submission_mode         VARCHAR(20) NOT NULL,
    external_request_ref    VARCHAR(255),
    external_batch_ref      VARCHAR(255),
    submission_status       VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    attempt_no              INTEGER NOT NULL DEFAULT 1,
    request_payload         JSONB,
    response_payload        JSONB,
    submitted_at            TIMESTAMPTZ,
    last_response_at        TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_is_endorsement_request_id ON insurer_submission(endorsement_request_id);
CREATE INDEX idx_is_policy_account_id ON insurer_submission(policy_account_id);
CREATE INDEX idx_is_submission_status ON insurer_submission(submission_status);

-- ─────────────────────────────────────────
-- POLICY ACCOUNT LEDGER
-- ─────────────────────────────────────────
CREATE TABLE policy_account_ledger (
    policy_account_ledger_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    policy_account_id           UUID NOT NULL REFERENCES policy_account(policy_account_id),
    endorsement_request_id      UUID REFERENCES endorsement_request(endorsement_request_id),
    entry_type                  VARCHAR(50) NOT NULL,  -- RESERVE, RELEASE, SETTLE_DEBIT, SETTLE_CREDIT, DEPOSIT, ADJUSTMENT
    amount                      BIGINT NOT NULL,        -- smallest currency unit (paisa / cents)
    currency_code               CHAR(3) NOT NULL,
    effective_date              DATE NOT NULL,
    entry_status                VARCHAR(50) NOT NULL DEFAULT 'POSTED',
    external_reference          VARCHAR(255),
    narration                   TEXT,
    metadata                    JSONB,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW()
    -- NOTE: No updated_at — ledger entries are immutable
);

CREATE INDEX idx_pal_policy_account_id ON policy_account_ledger(policy_account_id);
CREATE INDEX idx_pal_endorsement_request_id ON policy_account_ledger(endorsement_request_id);
CREATE INDEX idx_pal_entry_type ON policy_account_ledger(entry_type);
CREATE INDEX idx_pal_created_at ON policy_account_ledger(created_at);

-- ─────────────────────────────────────────
-- POLICY ACCOUNT BALANCE (maintained by LedgerService)
-- ─────────────────────────────────────────
CREATE TABLE policy_account_balance (
    policy_account_id           UUID PRIMARY KEY REFERENCES policy_account(policy_account_id),
    confirmed_ea_balance        BIGINT NOT NULL DEFAULT 0,
    reserved_exposure           BIGINT NOT NULL DEFAULT 0,
    available_balance           BIGINT NOT NULL DEFAULT 0,  -- confirmed - reserved
    pending_credit              BIGINT NOT NULL DEFAULT 0,
    pending_debit               BIGINT NOT NULL DEFAULT 0,
    last_insurer_balance_sync_at TIMESTAMPTZ,
    last_reconciled_at          TIMESTAMPTZ,
    drift_amount                BIGINT NOT NULL DEFAULT 0,
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_available_balance CHECK (available_balance = confirmed_ea_balance - reserved_exposure),
    CONSTRAINT chk_reserved_non_negative CHECK (reserved_exposure >= 0)
);

-- ─────────────────────────────────────────
-- BATCH REQUEST
-- ─────────────────────────────────────────
CREATE TABLE batch_request (
    batch_request_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    policy_account_id       UUID NOT NULL REFERENCES policy_account(policy_account_id),
    insurer_id              UUID NOT NULL REFERENCES insurer(insurer_id),
    batch_type              VARCHAR(50) NOT NULL,
    status                  VARCHAR(50) NOT NULL DEFAULT 'ASSEMBLING',
    item_count              INTEGER NOT NULL DEFAULT 0,
    submitted_file_uri      VARCHAR(500),
    external_batch_ref      VARCHAR(255),
    cutoff_window_date      DATE,
    submitted_at            TIMESTAMPTZ,
    completed_at            TIMESTAMPTZ,
    metadata                JSONB,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_br_policy_account_id ON batch_request(policy_account_id);
CREATE INDEX idx_br_status ON batch_request(status);

-- ─────────────────────────────────────────
-- BATCH ITEM STATUS
-- ─────────────────────────────────────────
CREATE TABLE batch_item_status (
    batch_item_status_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    batch_request_id        UUID NOT NULL REFERENCES batch_request(batch_request_id),
    endorsement_request_id  UUID NOT NULL REFERENCES endorsement_request(endorsement_request_id),
    sequence_no             INTEGER NOT NULL,
    item_status             VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    external_item_ref       VARCHAR(255),
    last_error_code         VARCHAR(100),
    last_error_message      TEXT,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_bis_batch_request_id ON batch_item_status(batch_request_id);
CREATE INDEX idx_bis_endorsement_request_id ON batch_item_status(endorsement_request_id);

-- ─────────────────────────────────────────
-- RECONCILIATION RUN
-- ─────────────────────────────────────────
CREATE TABLE reconciliation_run (
    reconciliation_run_id   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    insurer_id              UUID NOT NULL REFERENCES insurer(insurer_id),
    run_type                VARCHAR(50) NOT NULL,
    status                  VARCHAR(50) NOT NULL DEFAULT 'RUNNING',
    source_type             VARCHAR(50),
    source_reference        VARCHAR(500),
    expected_record_count   INTEGER,
    actual_record_count     INTEGER,
    drift_amount            BIGINT NOT NULL DEFAULT 0,
    started_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at            TIMESTAMPTZ,
    summary_json            JSONB,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ─────────────────────────────────────────
-- RECONCILIATION ISSUE
-- ─────────────────────────────────────────
CREATE TABLE reconciliation_issue (
    reconciliation_issue_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reconciliation_run_id       UUID NOT NULL REFERENCES reconciliation_run(reconciliation_run_id),
    policy_account_id           UUID REFERENCES policy_account(policy_account_id),
    issue_type                  VARCHAR(100) NOT NULL,
    severity                    VARCHAR(20) NOT NULL DEFAULT 'MEDIUM',
    endorsement_request_id      UUID REFERENCES endorsement_request(endorsement_request_id),
    insurer_submission_id       UUID REFERENCES insurer_submission(insurer_submission_id),
    ledger_entry_id             UUID REFERENCES policy_account_ledger(policy_account_ledger_id),
    expected_state              JSONB,
    actual_state                JSONB,
    drift_amount                BIGINT,
    resolution_status           VARCHAR(50) NOT NULL DEFAULT 'OPEN',
    resolved_by                 VARCHAR(255),
    resolved_at                 TIMESTAMPTZ,
    notes                       TEXT,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ri_reconciliation_run_id ON reconciliation_issue(reconciliation_run_id);
CREATE INDEX idx_ri_policy_account_id ON reconciliation_issue(policy_account_id);

-- ─────────────────────────────────────────
-- AUDIT LOG (immutable — no updated_at)
-- ─────────────────────────────────────────
CREATE TABLE audit_log (
    audit_log_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id        VARCHAR(255) NOT NULL,
    actor_type      VARCHAR(50) NOT NULL,
    action          VARCHAR(100) NOT NULL,
    resource_type   VARCHAR(100) NOT NULL,
    resource_id     VARCHAR(255) NOT NULL,
    payload         JSONB,
    ip_address      VARCHAR(45),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_actor_id ON audit_log(actor_id);
CREATE INDEX idx_audit_resource ON audit_log(resource_type, resource_id);
CREATE INDEX idx_audit_created_at ON audit_log(created_at);
