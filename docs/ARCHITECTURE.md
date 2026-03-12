# Architecture Design — Endorsement Management System (EMS)

> Version: 1.0 | Status: Approved | Phase: 1 (Single-Region)

---

## 1. Problem Summary

An EMS manages the lifecycle of insurance endorsements (add / delete / update members on a group policy) between employers and insurers. Key challenges:
- Insurers have heterogeneous APIs (realtime vs batch, varying SLAs)
- Employers need uninterrupted coverage with minimum capital tied up in their endorsement account
- System must handle 1M endorsements/day with peak 120 QPS, across 100K employers and 10 insurers

---

## 2. Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                          CLIENT LAYER                               │
│   React SPA (Employer Portal)     EMS Support Dashboard             │
└───────────────┬─────────────────────────────┬───────────────────────┘
                │ HTTPS/REST                  │ HTTPS/REST
┌───────────────▼─────────────────────────────▼───────────────────────┐
│                        API GATEWAY (Nginx)                          │
│            Rate limiting · Auth stub · Request routing              │
└───────────────┬─────────────────────────────────────────────────────┘
                │
┌───────────────▼─────────────────────────────────────────────────────┐
│                     EMS BACKEND (Spring Boot 3)                     │
│                                                                     │
│  ┌─────────────────┐  ┌──────────────────┐  ┌──────────────────┐  │
│  │  Onboarding     │  │  Endorsement     │  │  Balance &       │  │
│  │  Service        │  │  Service         │  │  Ledger Service  │  │
│  └─────────────────┘  └────────┬─────────┘  └──────────────────┘  │
│                                │                                     │
│  ┌─────────────────┐  ┌────────▼─────────┐  ┌──────────────────┐  │
│  │  Batch          │  │  Submission      │  │  Reconciliation  │  │
│  │  Scheduler      │  │  Router          │  │  Service         │  │
│  └─────────────────┘  └────────┬─────────┘  └──────────────────┘  │
│                                │                                     │
│  ┌─────────────────────────────▼──────────────────────────────────┐ │
│  │                    Insurer Client Layer                        │ │
│  │     RealtimeInsurerClient    |    BatchInsurerClient           │ │
│  └────────────────────────────────────────────────────────────────┘ │
└───────────────┬─────────────────────────────────────────────────────┘
                │
┌───────────────▼──────────┐    ┌───────────────────────────────────┐
│   PostgreSQL 15           │    │   Insurer APIs (External)         │
│   Control Plane DB        │    │   · Realtime REST                 │
│   Data Plane DB           │    │   · Batch SFTP / REST             │
└──────────────────────────┘    │   · Webhook callbacks             │
                                 └───────────────────────────────────┘
```

**Phase 2 additions (not in scope now):** Redis (rate limiting + caching), Kafka (event streaming), multi-region replication, separate read replicas per plane.

---

## 3. Service Responsibilities

### 3.1 Onboarding Service
- Employer registration and policy account setup
- Insurer config bootstrap (execution mode, QPS limits, batch config)
- Sandbox connectivity verification

### 3.2 Endorsement Service
- Create / validate / cancel endorsement requests
- Drive state machine transitions (see `STATE_MACHINE.md`)
- Idempotency enforcement on `idempotency_key`
- Pre-submission validation: dedup, balance check, effective date window

### 3.3 Balance & Ledger Service
- Sole writer to `policy_account_ledger`
- Maintains `policy_account_balance` (materialized, refreshed on write)
- Enforces balance invariant: `available = confirmed - reserved`
- Emits low-balance alerts when `available_balance < alert_threshold`

### 3.4 Submission Router
- Reads `insurer_config.execution_mode` to decide REALTIME vs BATCH
- REALTIME: direct HTTP call, awaits webhook callback or polls
- BATCH: accumulates requests until cutoff, assembles file/payload, submits
- Handles QPS limiting per insurer via token bucket (in-process for Phase 1)

### 3.5 Batch Scheduler
- Cron-driven: fires before `batch_cutoff_time` per insurer config
- Applies balance minimization ordering (DELETE → UPDATE → ADD by effective_date)
- Creates `batch_request` record and links `batch_item_status` per endorsement

### 3.6 Reconciliation Service
- Triggered by insurer batch response file or daily schedule
- Compares `policy_account_ledger` state vs insurer-reported state
- Writes `reconciliation_run` + `reconciliation_issue` for any drift
- Phase 1: file-based; Phase 2: webhook-stream-based

---

## 4. Data Model — Key Design Decisions

### 4.1 Control Plane vs Data Plane separation

| Plane | Tables | Access Pattern |
|-------|--------|----------------|
| Control | employer, policy_account, insurer, insurer_config | Low write, high read, config-driven |
| Data | endorsement_request, insurer_submission, endorsement_journal, policy_account_ledger, batch_*, reconciliation_* | High write, append-heavy |

**Rationale:** Separating planes allows independent scaling. In Phase 2, Data Plane moves to a write-optimized cluster; Control Plane moves to a read replica with aggressive caching.

### 4.2 Balance as materialized table (not a live view)

`policy_account_balance` is a **table** maintained by `LedgerService` within the same transaction as each ledger write. A live `VIEW` on `policy_account_ledger` would require a full table scan at 1M rows/day — unacceptable at peak QPS.

### 4.3 Idempotency

`endorsement_request.idempotency_key` has a unique constraint scoped to `(policy_account_id, idempotency_key)`. The service layer checks for existence before insert and returns the existing record if found.

### 4.4 Missing tables (added vs original design)

These tables are added beyond the original schema:

```sql
-- Audit trail for regulatory compliance
CREATE TABLE audit_log (
    audit_log_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id        VARCHAR(255) NOT NULL,
    actor_type      VARCHAR(50) NOT NULL,  -- EMPLOYER, EMPLOYEE, SYSTEM, SUPPORT
    action          VARCHAR(100) NOT NULL,
    resource_type   VARCHAR(100) NOT NULL,
    resource_id     VARCHAR(255) NOT NULL,
    payload         JSONB,
    ip_address      VARCHAR(45),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Alert configuration for low balance and scheduled reports
CREATE TABLE alert_config (
    alert_config_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    policy_account_id   UUID NOT NULL REFERENCES policy_account(policy_account_id),
    alert_type          VARCHAR(50) NOT NULL,  -- LOW_BALANCE, BATCH_FAILURE, RECONCILIATION_DRIFT
    threshold_value     BIGINT,
    notification_email  VARCHAR(255),
    is_active           BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

---

## 5. Add Endorsement — E2E Sequence

```
HR Admin (UI)
    │
    │  POST /api/v1/endorsements
    │  { policy_account_id, member, effective_date, idempotency_key }
    ▼
EndorsementController
    │
    ├─ Check idempotency_key → return existing if duplicate
    │
    ├─ Validate request:
    │   ├─ effective_date within backdate_window_days
    │   ├─ member dedup check (employee_code in policy_members)
    │   └─ available_balance >= estimated_premium
    │
    ├─ Create endorsement_request [status: VALIDATED]
    ├─ Create policy_member record
    ├─ Write RESERVE ledger entry → update policy_account_balance
    ├─ Write audit_log entry
    │
    └─ Return { endorsement_request_id, status: VALIDATED, available_balance }
    
    ▼ (async — SubmissionRouter, triggered by scheduler or immediate for REALTIME)

SubmissionRouter
    │
    ├─ Load insurer_config for policy_account
    │
    ├─ REALTIME path:
    │   ├─ POST to insurer API
    │   ├─ Update status → SUBMITTED
    │   ├─ Await webhook callback (or poll)
    │   └─ On confirmation:
    │       ├─ Update status → EXECUTED
    │       ├─ Write SETTLE_DEBIT ledger entry
    │       └─ Update member_coverage_state → ACTIVE
    │
    └─ BATCH path:
        ├─ Add to batch_item_status [item_status: PENDING]
        ├─ BatchScheduler fires at cutoff_time
        ├─ Assemble batch (DELETE→UPDATE→ADD ordering)
        ├─ Submit batch file → create batch_request
        └─ On insurer batch response:
            ├─ Process each item_status
            ├─ Update endorsement_request statuses
            ├─ Write SETTLE_DEBIT ledger entries
            └─ Update member_coverage_states

HR Admin (UI)
    │
    └─ Polls GET /api/v1/endorsements/{id} or receives WebSocket push
       for real-time status update
```

---

## 6. Technology Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Language | Java 21 | Virtual threads (Project Loom) for high-concurrency insurer calls |
| Framework | Spring Boot 3.2 | Native support for virtual threads, WebSocket, Actuator |
| Database | PostgreSQL 15 | JSONB for flexible insurer payloads, strong ACID for ledger |
| Migrations | Flyway | Version-controlled, reproducible schema |
| Frontend | React 18 + Vite + TypeScript | Fast dev loop, strong typing for complex domain |
| State mgmt | TanStack Query | Server state management, auto-refetch for status polling |
| Forms | react-hook-form + zod | Type-safe validation aligned with backend constraints |
| Testing (BE) | JUnit 5 + Testcontainers | Real Postgres in tests, no mock-DB drift |
| Testing (FE) | Vitest + RTL | Fast unit tests, same toolchain as Vite |
| Local infra | Docker Compose | Single-command local setup |

---

## 7. NFR Targets & Phase 1 Approach

| NFR | Target | Phase 1 Approach |
|-----|--------|-----------------|
| Throughput | 1M endorsements/day | Async submission, batch processing |
| Peak QPS | 120 | In-process token bucket per insurer |
| Availability | 99.9% | Single-region, health checks, graceful degradation |
| Audit | Immutable audit_log | Append-only table, no deletes |
| Tenant isolation | Per-employer data isolation | `policy_account_id` on all data-plane queries |
| Multi-region | Phase 2 | Document replication strategy in ADR-002 |

---

## 8. Phase 2 Roadmap (Out of Scope Now, Documented for Awareness)

- **Redis:** Session caching, distributed rate limiting (replace in-process token bucket)
- **Kafka:** Decouple endorsement validation from submission; enable event sourcing on ledger
- **Multi-region:** Active-passive with Postgres streaming replication; promote to active-active with CRDT-safe ledger
- **AI/ML:** Anomaly detection on endorsement patterns, balance prediction for low-alert tuning, automated reconciliation issue triage
