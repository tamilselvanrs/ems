# EMS Scaled Architecture

> Production-grade architecture for the Endorsement Management System handling live traffic.
> This document describes the target state — not a refactor plan, but the system as it should exist at scale.

---

## System Context

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                              Employer Portal / API                              │
│                         (HR dashboards, bulk uploads)                           │
└──────────────────────────────────┬──────────────────────────────────────────────┘
                                   │
                                   ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│                           API Gateway / Load Balancer                           │
│                    (rate limiting, auth, request routing)                        │
└──────────────────────────────────┬──────────────────────────────────────────────┘
                                   │
                    ┌──────────────┼──────────────┐
                    ▼              ▼              ▼
              ┌──────────┐  ┌──────────┐  ┌──────────┐
              │  EMS      │  │  EMS      │  │  EMS      │
              │  Node 1   │  │  Node 2   │  │  Node 3   │
              └─────┬─────┘  └─────┬─────┘  └─────┬─────┘
                    │              │              │
         ┌─────────┴──────────────┴──────────────┴─────────┐
         │                                                  │
    ┌────▼────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐  │
    │Postgres │  │  Redis    │  │ Temporal │  │ Object   │  │
    │(Primary │  │ (Cache)   │  │ Server   │  │ Store    │  │
    │+ Replica)│  │          │  │          │  │ (Batch   │  │
    └─────────┘  └──────────┘  └────┬─────┘  │  files)  │  │
                                    │         └──────────┘  │
                    ┌───────────────┼────────────┐          │
                    ▼               ▼            ▼          │
              ┌──────────┐  ┌────────────┐  ┌──────────┐   │
              │ Batch     │  │ Temporal    │  │ Insurer   │  │
              │ Worker    │  │ Workers    │  │ Adapter   │  │
              │           │  │ (activities)│  │          │  │
              └──────────┘  └────────────┘  └─────┬─────┘  │
                                                  │         │
                                    ┌─────────────┼─────┐   │
                                    ▼             ▼     ▼   │
                              ┌─────────┐ ┌────────┐ ┌─────┘
                              │Insurer A│ │Insurer B│ │...
                              │  (REST) │ │ (SFTP) │ │
                              └─────────┘ └────────┘ │
         ┌──────────────────────────────────────────────────┐
         │               Observability Plane                │
         │  ┌────────┐  ┌──────────┐  ┌─────────────────┐  │
         │  │Grafana │  │Prometheus│  │  Tempo / Jaeger  │  │
         │  │        │  │          │  │  (Traces)        │  │
         │  └────────┘  └──────────┘  └─────────────────┘  │
         │  ┌────────────────────┐  ┌────────────────────┐  │
         │  │ Loki (Logs)        │  │ AlertManager       │  │
         │  └────────────────────┘  └────────────────────┘  │
         └──────────────────────────────────────────────────┘
```

---

## 1. Endorsement Request — Multi-Step Workflow (Temporal)

### Why Temporal over a custom state machine

An endorsement request passes through pricing, balance reservation, optional human approval, insurer submission, response handling, and settlement — each step can fail independently, may take hours or days (batch insurers, underwriting review), and must be retryable without side effects.

A custom Kafka + outbox + poller stack requires building and maintaining ~5 infrastructure components (outbox table + CDC/poller, DLQ consumer, retry scheduler, state reconciler, workflow visibility dashboard). Temporal provides all of this out of the box:

| Concern | Custom (Kafka + outbox) | Temporal |
|---------|------------------------|----------|
| **Durable state** | You build it (outbox, status columns, retry table) | Built-in — workflow state survives crashes and restarts |
| **Retries with backoff** | DLQ, retry topic, delay logic, max-attempt tracking | Declarative `RetryOptions` per activity |
| **Human approval / long waits** | Very hard — external signal table + polling + timeout tracking | First-class `Workflow.await()` and signals, durable for days/weeks |
| **Visibility** | Build dashboards over your own tables | Temporal Web UI — full workflow history, pending activities, search by any field |
| **Timeouts** | Application-level timers, prone to drift on restarts | Server-enforced durable timers (survive worker restarts) |
| **Versioning** | Schema migrations + backward-compat code | Built-in workflow versioning for zero-downtime deploys |
| **Saga / compensation** | Manual rollback logic per step | Natural try/catch in workflow code; compensation is just code |
| **Operational maturity** | Months of hardening (poison pills, rebalancing, lag monitoring) | Battle-tested at Stripe, Netflix, Snap, Coinbase |
| **Infra to maintain** | Kafka cluster + Debezium/poller + DLQ consumer + monitoring | Temporal server (or Temporal Cloud — fully managed) |

**Bottom line:** Temporal eliminates the outbox table, CDC connector, DLQ topic, retry scheduler, and custom visibility layer — replacing ~2000 lines of infrastructure code with declarative workflow definitions.

### Workflow Definition

```
┌────────┐    ┌──────────┐    ┌─────────────┐    ┌──────────┐    ┌───────────┐
│ INTAKE │───▶│ PRICED   │───▶│  RESERVED   │───▶│ APPROVAL │───▶│ SUBMITTED │
└────────┘    └──────────┘    └─────────────┘    │ (if req) │    └─────┬─────┘
     │                              │             └──────────┘          │
     ▼                              ▼                  │          ┌────┴─────┐
 VALIDATION_                   INSUFFICIENT            ▼          ▼          ▼
  FAILED                        _BALANCE          REJECTED    EXECUTED   FAILED_
     │                              │             (human)     (settle)   RETRYABLE
     ▼                              ▼                                       │
   CANCELLED                   CANCELLED                              ┌─────┴──────┐
                                                                      ▼            ▼
                                                                   QUEUED    FAILED_TERMINAL
                                                                  (retry)
```

### Temporal Workflow — Java SDK

```java
@WorkflowInterface
public interface EndorsementWorkflow {

    @WorkflowMethod
    EndorsementResult process(EndorsementWorkflowInput input);

    /** Signal: human approves or rejects the endorsement */
    @SignalMethod
    void approvalDecision(ApprovalDecision decision);

    /** Query: current workflow state without side effects */
    @QueryMethod
    EndorsementWorkflowState getState();
}
```

```java
public class EndorsementWorkflowImpl implements EndorsementWorkflow {

    // Activities are Temporal's unit of work — each is retried independently
    private final PricingActivities pricing = Workflow.newActivityStub(PricingActivities.class,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(10))
            .setRetryOptions(RetryOptions.newBuilder()
                .setMaximumAttempts(3)
                .setBackoffCoefficient(2.0)
                .build())
            .build());

    private final BalanceActivities balance = ...;   // same pattern
    private final SubmissionActivities submission = ...;
    private final SettlementActivities settlement = ...;

    private ApprovalDecision approvalDecision = null;

    @Override
    public EndorsementResult process(EndorsementWorkflowInput input) {

        // Step 1: Derive premium
        long premium = pricing.derivePremium(input);

        // Step 2: Reserve balance
        balance.reserve(input.policyAccountId(), input.endorsementId(), premium);

        // Step 3: Human approval (conditional)
        if (input.requiresApproval()) {
            // Durable wait — survives worker restarts, can wait days/weeks
            boolean approved = Workflow.await(
                Duration.ofDays(7),   // timeout
                () -> approvalDecision != null
            );

            if (!approved || approvalDecision.isRejected()) {
                balance.release(input.policyAccountId(), input.endorsementId(), premium);
                return EndorsementResult.rejected(approvalDecision);
            }
        }

        // Step 4: Submit to insurer
        SubmissionResult result = submission.submit(input.endorsementId());

        // Step 5: Settle balance
        settlement.settle(input.policyAccountId(), input.endorsementId(),
                          premium, result.actualPremium());

        return EndorsementResult.executed(result);
    }

    @Override
    public void approvalDecision(ApprovalDecision decision) {
        this.approvalDecision = decision;   // unblocks Workflow.await()
    }

    @Override
    public EndorsementWorkflowState getState() {
        return currentState;   // queries are read-only, no side effects
    }
}
```

This reads like normal Java code, but Temporal makes it **durable** — if the worker crashes after `reserve()` but before `submit()`, Temporal replays the workflow from the last checkpoint. No manual state management, no outbox, no DLQ.

### Activities (Side-Effecting Operations)

Each activity is a Spring bean with access to repositories, services, and external APIs:

```java
@ActivityInterface
public interface PricingActivities {
    @ActivityMethod
    long derivePremium(EndorsementWorkflowInput input);
}

@ActivityInterface
public interface BalanceActivities {
    @ActivityMethod
    void reserve(UUID policyAccountId, UUID endorsementId, long amount);

    @ActivityMethod
    void release(UUID policyAccountId, UUID endorsementId, long amount);
}

@ActivityInterface
public interface SubmissionActivities {
    @ActivityMethod
    SubmissionResult submit(UUID endorsementId);
}

@ActivityInterface
public interface SettlementActivities {
    @ActivityMethod
    void settle(UUID policyAccountId, UUID endorsementId,
                long reservedAmount, long actualAmount);
}
```

**Activity retry configuration** is per-activity, not global:

| Activity | Timeout | Max Attempts | Backoff | Non-Retryable Errors |
|----------|---------|-------------|---------|---------------------|
| `derivePremium` | 10s | 3 | 2x | `PricingRuleNotFoundException` |
| `reserve` | 15s | 3 | 2x | `InsufficientBalanceException` |
| `submit` (realtime) | 30s | 5 | 2x exponential, max 60s | — |
| `submit` (batch) | 5min | 3 | 2x | — |
| `settle` | 15s | 5 | 2x | — |

Non-retryable errors immediately fail the activity (no wasted retries on validation errors).

### Human Approval Flow

Some endorsements require human approval before insurer submission (e.g., high-value adds, manual underwriting, compliance review). Temporal makes this trivial:

```
┌──────────┐                    ┌───────────────┐                ┌──────────────┐
│ EMS API  │                    │   Temporal     │                │  Ops Portal  │
│          │                    │   Workflow     │                │              │
└────┬─────┘                    └───────┬───────┘                └──────┬───────┘
     │                                  │                               │
     │── startWorkflow() ─────────────▶│                               │
     │                                  │── pricing ──▶                 │
     │                                  │── reserve ──▶                 │
     │                                  │── await(7 days) ────────────▶│ (query: pending)
     │                                  │              (blocked)        │
     │                                  │                               │
     │                                  │     (hours / days pass)       │
     │                                  │                               │
     │                                  │◀── signal(APPROVED) ─────────│
     │                                  │── submit ──▶                 │
     │                                  │── settle ──▶                 │
     │◀── query(getState) ────────────▶│                               │
     │   { status: EXECUTED }           │                               │
```

**Key properties:**
- The workflow **durably waits** — no polling, no timers table, no scheduled jobs. Temporal persists the wait and resumes on signal.
- 7-day timeout is server-enforced. If no approval signal arrives, the workflow auto-releases the balance reservation and transitions to CANCELLED.
- The ops portal calls `workflowStub.approvalDecision(decision)` — a single RPC. No message queue, no API endpoint to build.
- `getState()` query returns current workflow state without side effects — the ops portal can poll this for status display.

### When Approval Is Required

Approval rules are configurable per policy account:

```java
public boolean requiresApproval(EndorsementWorkflowInput input) {
    return input.estimatedPremium() > approvalThreshold
        || input.requestType() == RequestType.DELETE
        || input.policyAccount().requiresManualApproval();
}
```

### Temporal Task Queues & Worker Configuration

```java
@Configuration
public class TemporalConfig {

    @Bean
    public WorkerFactory workerFactory(WorkflowClient client) {
        WorkerFactory factory = WorkerFactory.newInstance(client);

        // Endorsement workflow workers
        Worker endorsementWorker = factory.newWorker("ems-endorsement-queue");
        endorsementWorker.registerWorkflowImplementationTypes(
            EndorsementWorkflowImpl.class);
        endorsementWorker.registerActivitiesImplementations(
            pricingActivities, balanceActivities,
            submissionActivities, settlementActivities);

        // Onboarding workflow workers (separate queue, separate scaling)
        Worker onboardingWorker = factory.newWorker("ems-onboarding-queue");
        onboardingWorker.registerWorkflowImplementationTypes(
            OnboardingWorkflowImpl.class);
        onboardingWorker.registerActivitiesImplementations(
            onboardingActivities);

        return factory;
    }
}
```

**Task queue separation** allows independent scaling — endorsement workers can scale up under load without affecting onboarding workflows.

### Workflow-to-DB Status Sync

The `endorsement_request.current_status` column still exists for queries and reporting, but it's now updated by activities as a side effect, not as the source of truth. Temporal is the source of truth for workflow state:

```java
// Inside each activity implementation:
public void reserve(UUID policyAccountId, UUID endorsementId, long amount) {
    // ... balance logic ...
    endorsementRequest.transitionTo(EndorsementStatus.VALIDATED);
    repository.save(endorsementRequest);
}
```

For queries like "all endorsements in SUBMITTED status", the DB column is sufficient. For "what step is this workflow currently on", query Temporal directly.

### Retry, Timeout & Failure Handling

Temporal replaces the custom retry/DLQ infrastructure entirely:

| Custom approach (removed) | Temporal equivalent |
|--------------------------|-------------------|
| `outbox_event` table | Not needed — Temporal persists workflow state |
| CDC / Debezium poller | Not needed |
| DLQ topic + consumer | Workflow reaches `FAILED` state, visible in Temporal UI |
| `retry_count` column | Tracked per-activity in Temporal, queryable |
| Exponential backoff logic | `RetryOptions.setBackoffCoefficient(2.0)` |
| Max attempts tracking | `RetryOptions.setMaximumAttempts(5)` |
| Dead letter dashboard | Temporal Web UI — filter by failed workflows, inspect history |

**Workflow-level timeout** (entire endorsement lifecycle):

```java
// On the workflow client side:
WorkflowOptions options = WorkflowOptions.newBuilder()
    .setTaskQueue("ems-endorsement-queue")
    .setWorkflowExecutionTimeout(Duration.ofDays(30))  // max lifecycle
    .setWorkflowId("endorsement-" + endorsementId)     // idempotency
    .build();
```

Using `endorsementId` as the Temporal workflow ID provides natural idempotency — starting a workflow with a duplicate ID returns the existing execution.

---

## 2. Onboarding Workflow (Temporal)

Onboarding is the process of setting up a new employer's policy account in EMS — creating the policy account, configuring insurer integration, loading pricing rules, and funding the balance. Unlike endorsement processing (which is mostly automated), onboarding is a **human-driven, multi-day workflow** — making Temporal's durable waits and signal handling essential.

### Workflow Definition

```
┌──────────────┐    ┌──────────────┐    ┌──────────────┐    ┌──────────────┐
│ POLICY       │───▶│ INSURER      │───▶│   AWAIT      │───▶│ PRICING      │
│ ACCOUNT      │    │ CONFIG       │    │   RULES      │    │ RULES        │
│ CREATION     │    │ SETUP        │    │   UPLOAD     │    │ VALIDATION   │
└──────────────┘    └──────────────┘    │  (signal)    │    └──────┬───────┘
                                        └──────────────┘           │
                                                              ┌────┴───────┐
                                                              ▼            ▼
                                                       ┌────────────┐  VALIDATION
                                                       │  AWAIT     │  _FAILED
                                                       │  FUNDING   │  (retry upload)
                                                       │  APPROVAL  │
                                                       │  (signal)  │
                                                       └─────┬──────┘
                                                             ▼
                                                       ┌────────────┐
                                                       │ BALANCE    │
                                                       │ FUNDING    │
                                                       └─────┬──────┘
                                                             ▼
                                                       ┌────────────┐
                                                       │ ACTIVATION │
                                                       │ (go-live)  │
                                                       └────────────┘
```

### Temporal Workflow

```java
@WorkflowInterface
public interface OnboardingWorkflow {

    @WorkflowMethod
    OnboardingResult process(OnboardingInput input);

    /** Signal: pricing rules CSV uploaded to object store */
    @SignalMethod
    void pricingRulesUploaded(String fileRef);

    /** Signal: finance team approves funding */
    @SignalMethod
    void fundingApproved(FundingApproval approval);

    @QueryMethod
    OnboardingState getState();
}
```

```java
public class OnboardingWorkflowImpl implements OnboardingWorkflow {

    private String pricingRulesFileRef = null;
    private FundingApproval fundingApproval = null;

    @Override
    public OnboardingResult process(OnboardingInput input) {

        // Step 1: Create policy account + zero-balance entry
        UUID policyAccountId = accountActivities.createPolicyAccount(input);

        // Step 2: Link or create insurer config, test connectivity
        accountActivities.setupInsurerConfig(policyAccountId, input.insurerId());

        // Step 3: Wait for pricing rules upload (human action, may take days)
        boolean rulesUploaded = Workflow.await(
            Duration.ofDays(30),
            () -> pricingRulesFileRef != null
        );
        if (!rulesUploaded) {
            return OnboardingResult.timedOut("Pricing rules not uploaded within 30 days");
        }

        // Step 4: Validate and load pricing rules
        ValidationResult validation = pricingActivities.validateAndLoadRules(
            policyAccountId, pricingRulesFileRef);

        if (!validation.isValid()) {
            // Reset signal — allow re-upload
            pricingRulesFileRef = null;
            // Re-await (workflow loops back to step 3)
            // ... (simplified — real impl uses a loop)
        }

        // Step 5: Wait for funding approval (finance team, may take days)
        boolean funded = Workflow.await(
            Duration.ofDays(30),
            () -> fundingApproval != null
        );
        if (!funded) {
            return OnboardingResult.timedOut("Funding not approved within 30 days");
        }

        // Step 6: Apply funding
        balanceActivities.fund(policyAccountId, fundingApproval.amount(),
                               fundingApproval.currencyCode());

        // Step 7: Activate
        accountActivities.activate(policyAccountId);

        return OnboardingResult.success(policyAccountId);
    }
}
```

**Why this is better than a status column + REST polling:**
- The workflow can wait 30 days for a file upload with zero infrastructure — no cron jobs checking "is the file uploaded yet?", no expiry schedulers.
- If the pricing rules fail validation, the workflow naturally loops back to await a re-upload. No state machine transitions to manage.
- The ops team queries workflow state via `getState()` — the Temporal UI also provides full visibility.

### Onboarding Entity (DB — for reporting/queries)

```sql
CREATE TABLE onboarding_request (
    onboarding_request_id   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    temporal_workflow_id    VARCHAR(200) NOT NULL,  -- link to Temporal
    employer_id             UUID         NOT NULL,
    insurer_id              UUID         NOT NULL,
    policy_number           VARCHAR(100) NOT NULL,
    current_step            VARCHAR(50)  NOT NULL DEFAULT 'POLICY_ACCOUNT_CREATION',
    current_status          VARCHAR(20)  NOT NULL DEFAULT 'IN_PROGRESS',
    policy_start_date       DATE         NOT NULL,
    policy_end_date         DATE         NOT NULL,
    currency_code           VARCHAR(3)   NOT NULL DEFAULT 'INR',
    initial_balance         BIGINT       NOT NULL DEFAULT 0,
    pricing_rules_ref       VARCHAR(500),          -- object store path to uploaded rules CSV
    config_snapshot         JSONB,                 -- insurer config at onboarding time
    error_details           TEXT,
    requested_by            VARCHAR(100) NOT NULL,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- output references
    policy_account_id       UUID,                  -- set after step 1 completes
    insurer_config_id       UUID                   -- set after step 2 completes
);
```

The `temporal_workflow_id` column links the DB record to the Temporal workflow execution. Activities update `current_step` and `current_status` as side effects, so the DB stays queryable for dashboards and reporting.

### Onboarding API

```
POST   /api/v1/onboarding                      -- initiate (starts Temporal workflow)
GET    /api/v1/onboarding/{id}                  -- status (queries Temporal + DB)
POST   /api/v1/onboarding/{id}/pricing-rules    -- upload CSV → signal workflow
POST   /api/v1/onboarding/{id}/fund             -- funding approval → signal workflow
```

The upload and funding endpoints don't execute business logic — they validate the input, then **signal the Temporal workflow** which resumes and continues processing. This keeps the API layer thin.

### Pricing Rules Upload Format

```csv
request_type,member_type,age_band_min,age_band_max,gender,base_premium
ADD,SELF,0,17,,150000
ADD,SELF,18,35,MALE,350000
ADD,SELF,18,35,FEMALE,320000
ADD,SELF,36,45,,500000
ADD,DEPENDENT,0,17,,120000
DELETE,SELF,0,999,,0
DELETE,DEPENDENT,0,999,,0
```

The `validateAndLoadRules` activity validates:
- No overlapping age bands for the same (request_type, member_type, gender) tuple
- All request types have at least one rule
- Premium values are non-negative
- Age bands are contiguous (no gaps)

Validation failures return a structured error listing every invalid row. The workflow resets the signal and re-awaits a corrected upload.

---

## 3. Batch Service

The current system treats batch as a submission mode flag. At scale, batch is a first-class service handling two distinct flows: **employer batches** (inbound) and **insurer batches** (outbound).

### 3.1 Employer Batch (Inbound)

Employers submit bulk endorsement requests via file upload (CSV/Excel) instead of individual API calls.

```
┌───────────────┐     ┌───────────────┐     ┌───────────────────┐
│  Employer      │────▶│  Batch        │────▶│  Endorsement      │
│  uploads CSV   │     │  Ingestion    │     │  Workflow (× N)   │
└───────────────┘     │  Service      │     │  (one per row)    │
                      └───────┬───────┘     └───────────────────┘
                              │
                              ▼
                      ┌───────────────┐
                      │  employer_    │
                      │  batch_job    │
                      └───────────────┘
```

**Employer Batch Job table:**

```sql
CREATE TABLE employer_batch_job (
    batch_job_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    policy_account_id   UUID         NOT NULL REFERENCES policy_account,
    file_ref            VARCHAR(500) NOT NULL,     -- object store path
    file_name           VARCHAR(200) NOT NULL,
    total_rows          INTEGER,
    processed_rows      INTEGER      DEFAULT 0,
    succeeded_rows      INTEGER      DEFAULT 0,
    failed_rows         INTEGER      DEFAULT 0,
    status              VARCHAR(20)  NOT NULL DEFAULT 'UPLOADED',
        -- UPLOADED → VALIDATING → PROCESSING → COMPLETED / COMPLETED_WITH_ERRORS / FAILED
    error_report_ref    VARCHAR(500),              -- path to error report CSV
    uploaded_by         VARCHAR(100) NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);
```

**Processing flow:**

1. **Upload** — file lands in object store, `employer_batch_job` row created (UPLOADED).
2. **Validation** — worker reads file, validates schema and each row's data. Invalid rows written to error report. Status → VALIDATING.
3. **Fan-out** — each valid row becomes an `AddEndorsementRequest`, published to `ems.endorsement.workflow` topic. Status → PROCESSING. Each endorsement carries `batch_job_id` as correlation.
4. **Tracking** — as endorsement workflow events complete, batch job counters are updated. When `processed_rows == total_rows`, status → COMPLETED or COMPLETED_WITH_ERRORS.

**API:**

```
POST   /api/v1/policy-accounts/{id}/batches/upload    -- upload CSV
GET    /api/v1/policy-accounts/{id}/batches            -- list batch jobs
GET    /api/v1/policy-accounts/{id}/batches/{batchId}  -- status + counts
GET    /api/v1/policy-accounts/{id}/batches/{batchId}/errors  -- download error report
```

### 3.2 Insurer Batch (Outbound)

For insurers that don't support real-time APIs, endorsements are collected and submitted as a batch file on a schedule.

```
                      ┌──────────────────┐
                      │  Scheduler       │
                      │  (cron per       │
                      │   insurer)       │
                      └────────┬─────────┘
                               │ triggers
                               ▼
┌──────────────┐     ┌─────────────────┐     ┌──────────────┐
│ QUEUED       │────▶│ Insurer Batch   │────▶│ Insurer      │
│ endorsements │     │ Assembler       │     │ (SFTP/API)   │
│ (per insurer)│     └────────┬────────┘     └──────┬───────┘
└──────────────┘              │                      │
                              ▼                      │ ack file
                      ┌───────────────┐              │
                      │ insurer_      │◀─────────────┘
                      │ batch_job     │
                      └───────────────┘
```

**Insurer Batch Job table:**

```sql
CREATE TABLE insurer_batch_job (
    batch_job_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    insurer_id          UUID         NOT NULL REFERENCES insurer_config(insurer_id),
    file_ref            VARCHAR(500),              -- outbound file path
    ack_file_ref        VARCHAR(500),              -- insurer response file path
    total_endorsements  INTEGER      NOT NULL,
    accepted_count      INTEGER      DEFAULT 0,
    rejected_count      INTEGER      DEFAULT 0,
    status              VARCHAR(20)  NOT NULL DEFAULT 'ASSEMBLING',
        -- ASSEMBLING → SUBMITTED → ACK_RECEIVED → RECONCILED / FAILED
    cutoff_time         TIME         NOT NULL,
    submitted_at        TIMESTAMPTZ,
    ack_received_at     TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Junction table: which endorsements are in which batch
CREATE TABLE insurer_batch_item (
    batch_job_id            UUID NOT NULL REFERENCES insurer_batch_job,
    endorsement_request_id  UUID NOT NULL REFERENCES endorsement_request,
    insurer_member_id       VARCHAR(100),   -- ID assigned by insurer in ack
    item_status             VARCHAR(20) NOT NULL DEFAULT 'PENDING',
        -- PENDING → ACCEPTED / REJECTED
    rejection_reason        TEXT,
    PRIMARY KEY (batch_job_id, endorsement_request_id)
);
```

**Processing flow:**

1. **Collection** — scheduled job (configurable per insurer) queries all QUEUED endorsements for that insurer's policy accounts.
2. **Assembly** — generates insurer-specific file format (CSV, XML, Excel — driven by `InsurerConfig.fileFormat`). Uploads to object store.
3. **Submission** — sends file via insurer's preferred channel (SFTP, API upload, email). Endorsements transition QUEUED → SUBMITTED.
4. **Acknowledgment** — polls or receives webhook for insurer's response file. Parses per-row accept/reject.
5. **Reconciliation** — accepted endorsements → EXECUTED + balance settlement. Rejected → FAILED_RETRYABLE or FAILED_TERMINAL depending on rejection code.

### 3.3 Insurer Adapter (Pluggable)

Each insurer has different integration requirements. The adapter pattern isolates these differences:

```java
public interface InsurerAdapter {
    String insurerId();
    SubmissionResult submitRealtime(EndorsementRequest request);
    BatchFileSpec generateBatchFile(List<EndorsementRequest> requests);
    List<BatchItemResult> parseAcknowledgment(InputStream ackFile);
}
```

Adapters are registered by insurer ID. New insurer integrations require only a new adapter implementation — no changes to the workflow or batch service.

---

## 4. Observability

### 4.1 Metrics (Prometheus + Micrometer)

**Business Metrics:**

| Metric | Type | Labels | Purpose |
|--------|------|--------|---------|
| `ems.endorsement.created` | Counter | `policy_account_id`, `request_type`, `submission_mode` | Volume tracking |
| `ems.endorsement.status_transition` | Counter | `from_status`, `to_status` | Workflow health |
| `ems.endorsement.e2e_duration_seconds` | Histogram | `request_type`, `submission_mode` | DRAFT → EXECUTED latency |
| `ems.endorsement.step_duration_seconds` | Histogram | `step` (pricing, reserve, submit, settle) | Per-step latency |
| `ems.balance.reservation` | Counter | `policy_account_id`, `outcome` (success/insufficient) | Balance pressure |
| `ems.balance.available_ratio` | Gauge | `policy_account_id` | available / confirmed — early warning |
| `ems.balance.drift` | Gauge | `policy_account_id` | Reconciliation health |
| `ems.pricing.lookup_duration_ms` | Histogram | `strategy` | Pricing performance |
| `ems.pricing.rule_miss` | Counter | `policy_account_id`, `request_type` | Missing rule alerts |
| `ems.batch.employer.rows_processed` | Counter | `policy_account_id`, `outcome` | Inbound batch health |
| `ems.batch.insurer.submitted` | Counter | `insurer_id` | Outbound batch volume |
| `ems.batch.insurer.ack_latency_seconds` | Histogram | `insurer_id` | Insurer responsiveness |
| `ems.workflow.activity_duration_seconds` | Histogram | `activity`, `task_queue` | Per-activity latency |
| `ems.workflow.failed_count` | Counter | `workflow_type`, `failure_reason` | Failures needing intervention |
| `ems.workflow.approval_wait_seconds` | Histogram | `policy_account_id` | Time endorsements spend awaiting human approval |

**Infrastructure Metrics (auto-collected):**

- JVM heap, GC, threads (Micrometer JVM metrics)
- HikariCP connection pool (active, idle, pending, timeouts)
- Temporal SDK metrics (workflow task latency, activity poll success rate, schedule-to-start latency)
- HTTP request rate, latency, error rate (Spring Boot actuator)

### 4.2 Distributed Tracing (OpenTelemetry → Tempo/Jaeger)

Every endorsement carries a **correlation ID** (`X-Correlation-Id` header or generated UUID) that propagates through:

```
HTTP Request → Temporal workflow → Activity execution → Insurer API call → DB operations
```

Temporal's Java SDK has built-in OpenTelemetry interceptors — trace context propagates automatically from the API call through the workflow into each activity execution.

**Trace structure for a single endorsement:**

```
Trace: ems.endorsement.add  [correlation_id=abc-123, workflow_id=endorsement-def-456]
├─ Span: http.request POST /endorsements                 12ms
│  └─ Span: temporal.start_workflow                       4ms
│
├─ Span: temporal.workflow EndorsementWorkflow.process
│  ├─ Span: activity pricing.derivePremium                3ms
│  ├─ Span: activity balance.reserve                      6ms
│  │  ├─ Span: db.balance.acquire_lock                    5ms
│  │  └─ Span: db.balance.reserve                         1ms
│  ├─ Span: activity submission.submit                  850ms
│  │  └─ Span: insurer.api.submit                      820ms
│  └─ Span: activity settlement.settle                    8ms
│     ├─ Span: db.balance.settle_debit                    3ms
│     └─ Span: ledger.write_entry                         2ms
```

For workflows with human approval, the trace will show the wait duration between the `reserve` and `submit` spans — making approval bottlenecks immediately visible.

**Implementation:**

```yaml
# application.yml
management:
  tracing:
    sampling:
      probability: 1.0    # 100% in staging, 10-20% in prod
  otlp:
    tracing:
      endpoint: http://tempo:4318/v1/traces
```

Spring Boot 3's built-in Micrometer Tracing + OpenTelemetry bridge handles auto-instrumentation for HTTP, JDBC, and RestClient. Temporal's `OpenTracingClientInterceptor` and `OpenTracingWorkerInterceptor` propagate trace context across workflow/activity boundaries automatically.

### 4.3 Structured Logging (JSON → Loki/ELK)

All logs are JSON-structured for machine parsing. Every log line includes the correlation ID.

```json
{
  "timestamp": "2026-03-13T10:15:23.456Z",
  "level": "INFO",
  "logger": "com.ems.service.EndorsementService",
  "message": "Endorsement created",
  "correlation_id": "abc-123",
  "endorsement_id": "def-456",
  "policy_account_id": "ghi-789",
  "request_type": "ADD",
  "estimated_premium": 500000,
  "duration_ms": 47,
  "thread": "http-nio-8080-exec-3"
}
```

**Log configuration:**

```xml
<!-- logback-spring.xml -->
<appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
    <encoder class="net.logstash.logback.encoder.LogstashEncoder">
        <includeMdcKeyName>correlation_id</includeMdcKeyName>
        <includeMdcKeyName>endorsement_id</includeMdcKeyName>
        <includeMdcKeyName>policy_account_id</includeMdcKeyName>
    </encoder>
</appender>
```

MDC (Mapped Diagnostic Context) is populated at the HTTP filter layer and Kafka consumer layer, then cleared after each request/message.

### 4.4 Alerting

| Alert | Condition | Severity | Action |
|-------|-----------|----------|--------|
| **Workflow failure rate** | `rate(ems.workflow.failed_count[5m]) > 5` | Critical | Page on-call. Check Temporal UI for failure details. |
| **Balance exhaustion** | `ems.balance.available_ratio < 0.1` | Warning | Notify employer ops team to top up. |
| **Balance drift** | `abs(ems.balance.drift) > 100000` | Warning | Trigger reconciliation job. |
| **Insurer ack delay** | `ems.batch.insurer.ack_latency_seconds{quantile="0.99"} > 86400` | Warning | Insurer SLA breach. Escalate. |
| **Temporal schedule-to-start** | `temporal_activity_schedule_to_start_latency{p99} > 30s` | Warning | Workers can't keep up. Scale up worker pods. |
| **Lock timeout spike** | `rate(db.lock.timeout_errors[5m]) > 10` | Critical | Connection pool exhaustion or hot partition. Investigate. |
| **HTTP 5xx rate** | `rate(http_server_requests{status=~"5.."}[1m]) > 0.05` | Critical | Service degradation. Check logs. |
| **Endorsement E2E P99 > SLA** | `histogram_quantile(0.99, ems.endorsement.e2e_duration_seconds) > 300` | Warning | Workflow bottleneck. Check per-activity latency in Temporal UI. |
| **Approval wait > 3 days** | `ems.workflow.approval_wait_seconds{quantile="0.5"} > 259200` | Warning | Endorsements stuck in approval. Notify ops team. |

### 4.5 Health Checks

```java
@Component
public class InsurerConnectivityHealthIndicator implements HealthIndicator {
    // Pings each active insurer's API/SFTP endpoint
    // DOWN if any critical insurer is unreachable
}

@Component
public class TemporalHealthIndicator implements HealthIndicator {
    // Checks Temporal server connectivity and namespace availability
    // Reports degraded if schedule-to-start latency exceeds threshold
}

@Component
public class BalanceDriftHealthIndicator implements HealthIndicator {
    // Flags accounts with non-zero drift as degraded
}
```

Health endpoint (`/actuator/health`) aggregates all indicators. Load balancer uses this for instance routing — unhealthy instances are drained, not killed.

### 4.6 Audit Trail

Every state-changing operation writes to an immutable audit log. This is not the same as application logging — the audit log is a compliance artifact.

```sql
CREATE TABLE audit_log (
    audit_log_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    correlation_id      VARCHAR(100) NOT NULL,
    actor_id            VARCHAR(100) NOT NULL,
    actor_type          VARCHAR(20)  NOT NULL,     -- EMPLOYER, EMPLOYEE, SYSTEM, SCHEDULER
    event_type          VARCHAR(50)  NOT NULL,      -- ENDORSEMENT_CREATED, BALANCE_RESERVED, etc.
    entity_type         VARCHAR(50)  NOT NULL,
    entity_id           UUID         NOT NULL,
    payload             JSONB,                      -- before/after snapshot
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Append-only: no UPDATE or DELETE grants on this table
```

### 4.7 Ledger

The ledger is the single source of truth for all balance movements.

```sql
CREATE TABLE ledger_entry (
    ledger_entry_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    policy_account_id       UUID         NOT NULL REFERENCES policy_account,
    endorsement_request_id  UUID         REFERENCES endorsement_request,
    entry_type              VARCHAR(30)  NOT NULL,
        -- RESERVE, RELEASE, SETTLE_DEBIT, SETTLE_CREDIT, FUND, ADJUSTMENT
    amount                  BIGINT       NOT NULL,  -- always positive
    direction               VARCHAR(6)   NOT NULL,  -- DEBIT or CREDIT
    currency_code           VARCHAR(3)   NOT NULL,
    effective_date          DATE         NOT NULL,
    balance_snapshot        JSONB,                   -- balance state after this entry
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Append-only: no UPDATE or DELETE grants on this table
CREATE INDEX idx_ledger_policy_account ON ledger_entry(policy_account_id, created_at);
```

---

## 5. Data Architecture

### Database Strategy

| Concern | Approach |
|---------|----------|
| **Primary store** | PostgreSQL 16 (single-writer primary + read replicas) |
| **Connection pooling** | HikariCP — max 20 per node, 5 min idle |
| **Read scaling** | Route read-only queries (`@Transactional(readOnly=true)`) to replicas |
| **Locking** | Pessimistic write lock (SELECT FOR UPDATE) on `policy_account_balance` with 5s timeout |
| **Optimistic concurrency** | `@Version` on balance entity — catches stale writes that bypass pessimistic lock |
| **Schema migrations** | Flyway — forward-only, no destructive migrations in production |
| **Partitioning** | `endorsement_request` partitioned by `created_at` (monthly) once table exceeds ~50M rows |
| **Archival** | Endorsements in terminal state older than 2 years moved to archive table |

### Caching (Redis)

| Cache | TTL | Invalidation | Purpose |
|-------|-----|-------------|---------|
| `pricing_rule:{policyAccountId}:{requestType}:{memberType}` | 15 min | On pricing rule update (publish event) | Avoid DB lookup per endorsement |
| `insurer_config:{insurerId}` | 30 min | On config change | Routing decisions |
| `policy_account:{policyAccountId}` | 10 min | On onboarding or deactivation | Existence and active check |

Balance is **never cached** — always read from DB with lock.

### Temporal vs Kafka — Scope Split

Temporal replaces Kafka for **workflow orchestration** (endorsement lifecycle, onboarding steps). Kafka is **optional** and only used for **event streaming** if downstream analytics or audit sinks need real-time feeds. For most deployments, direct DB writes from Temporal activities are sufficient.

| Concern | Technology | Rationale |
|---------|-----------|-----------|
| Endorsement workflow | Temporal | Durable execution, signals, retries, visibility |
| Onboarding workflow | Temporal | Long-running waits (days), human signals |
| Batch fan-out | Temporal child workflows | Each row becomes a child endorsement workflow |
| Balance events (analytics) | Kafka (optional) | Decouple analytics from transaction path |
| Audit sink | Direct DB write | Simpler; async Kafka sink is an optimization |

If Kafka is used for analytics/audit streaming:

| Topic | Partitions | Key | Consumers |
|-------|-----------|-----|-----------|
| `ems.balance.events` | 8 | `policy_account_id` | Analytics pipeline |
| `ems.audit.events` | 4 | `entity_id` | Audit data lake sink |

These are **optional event streams**, not part of the critical transaction path.

---

## 6. Deployment Topology

```
┌──────────────────────────────────────────────────────────────────┐
│                       Kubernetes Cluster                         │
│                                                                   │
│  ┌────────────────────────┐   ┌────────────────────────────┐    │
│  │  ems-api (3 pods)      │   │  ems-temporal-worker        │    │
│  │  - HTTP endpoints      │   │  (3 pods)                   │    │
│  │  - Starts workflows    │   │  - Endorsement workflows    │    │
│  │  - Sends signals       │   │  - Onboarding workflows     │    │
│  │  - HPA on CPU/RPS      │   │  - All activity impls       │    │
│  └────────────────────────┘   │  - HPA on schedule-to-start │    │
│                                │    latency                   │    │
│  ┌────────────────────────┐   └────────────────────────────┘    │
│  │  ems-batch-worker      │                                      │
│  │  (2 pods)              │   ┌────────────────────────────┐    │
│  │  - Employer batch      │   │  Temporal Server            │    │
│  │    ingestion           │   │  (3 pods or Temporal Cloud) │    │
│  │  - File parsing        │   │  - Workflow state store     │    │
│  │  - Fan-out to child    │   │  - Timer management         │    │
│  │    workflows           │   │  - Visibility / search      │    │
│  │  - HPA on queue depth  │   │  - Web UI (ops dashboard)   │    │
│  └────────────────────────┘   └────────────────────────────┘    │
│                                                                   │
└──────────────────────────────────────────────────────────────────┘
```

| Component | Scaling Strategy | Min Pods | Max Pods |
|-----------|-----------------|----------|----------|
| `ems-api` | HPA on CPU (70%) and RPS | 3 | 12 |
| `ems-temporal-worker` | HPA on Temporal schedule-to-start latency | 3 | 20 |
| `ems-batch-worker` | HPA on queue depth | 2 | 8 |
| Temporal Server | Fixed (or Temporal Cloud — fully managed) | 3 | 3 |

**Note on Temporal Server hosting:** For production, **Temporal Cloud** (managed SaaS) eliminates the operational burden of running the Temporal server cluster, its Cassandra/PostgreSQL backend, and Elasticsearch for visibility. Self-hosted is viable but requires dedicated infra expertise. The EMS worker pods are the same regardless of hosting choice.

---

## 7. Request Flow — End to End

### Single Endorsement (Realtime Insurer, No Approval)

```
Client                  EMS API              Temporal Server        Temporal Worker         Insurer API
  │                       │                       │                       │                     │
  │── POST /endorsements ▶│                       │                       │                     │
  │                       │── startWorkflow() ───▶│                       │                     │
  │◀── 201 Created ───────│                       │                       │                     │
  │  (workflow ID returned)│                       │── dispatch ──────────▶│                     │
  │                       │                       │                       │── derivePremium()   │
  │                       │                       │                       │── reserve()          │
  │                       │                       │                       │── submit() ─────────▶│
  │                       │                       │                       │◀── 200 OK ───────────│
  │                       │                       │                       │── settle()           │
  │                       │                       │◀── workflow complete ──│                     │
```

### Single Endorsement (With Human Approval)

```
Client         EMS API         Temporal Server      Temporal Worker      Ops Portal
  │               │                  │                     │                  │
  │── POST ──────▶│                  │                     │                  │
  │               │── startWorkflow()▶                     │                  │
  │◀── 201 ───────│                  │── dispatch ────────▶│                  │
  │               │                  │                     │── price + reserve│
  │               │                  │                     │── await(7 days) ─│
  │               │                  │                     │    (blocked)     │
  │               │                  │                     │                  │
  │               │                  │                     │ (hours/days)     │
  │               │                  │                     │                  │
  │               │                  │◀── signal(APPROVED)─────────────────── │
  │               │                  │── resume ──────────▶│                  │
  │               │                  │                     │── submit()       │
  │               │                  │                     │── settle()       │
  │               │                  │◀── complete ────────│                  │
```

### Employer Batch Upload

```
Client              EMS API           Object Store     Temporal (Batch Workflow)    Temporal (Child × N)
  │                    │                    │                    │                        │
  │── POST /upload ───▶│                    │                    │                        │
  │                    │── store file ─────▶│                    │                        │
  │                    │── startWorkflow() ─────────────────────▶│                        │
  │◀── 202 Accepted ──│                    │                    │                        │
  │                    │                    │                    │── readFile() ──────────▶│
  │                    │                    │                    │── validateRows()        │
  │                    │                    │                    │                        │
  │                    │                    │                    │── startChildWorkflow() ▶│ (row 1)
  │                    │                    │                    │── startChildWorkflow() ▶│ (row 2)
  │                    │                    │                    │── ...                  │ (row N)
  │                    │                    │                    │                        │
  │                    │                    │                    │── awaitAll(children) ──▶│
  │                    │                    │                    │◀── all complete ────────│
  │── GET /batches/{id}▶│                    │                    │                        │
  │◀── { processed: N } │                    │                    │                        │
```

Each child workflow is a full `EndorsementWorkflow` — with its own retries, approval waits, and insurer submission. The parent batch workflow tracks completion and updates counters.

### Insurer Batch Submission

```
Scheduler              Batch Service         Object Store          Insurer (SFTP)
  │                       │                       │                     │
  │── trigger (cron) ────▶│                       │                     │
  │                       │── query QUEUED ──────▶│                     │
  │                       │── generate file ─────▶│                     │
  │                       │── upload to store ───▶│                     │
  │                       │── SFTP put ──────────────────────────────▶│
  │                       │── status → SUBMITTED  │                     │
  │                       │                       │                     │
  │── trigger (poll) ────▶│                       │                     │
  │                       │── SFTP get ack ◀──────────────────────────│
  │                       │── parse ack          │                     │
  │                       │── per-item: EXECUTED or FAILED             │
  │                       │── settle balances     │                     │
  │                       │── status → RECONCILED │                     │
```

---

## 8. Entity Relationship Diagram (Target State)

```
┌────────────────────┐       ┌─────────────────────┐
│  onboarding_       │──────▶│  policy_account      │
│  request           │       │                      │◀──┐
└────────────────────┘       └──────────┬───────────┘   │
                                        │               │
                    ┌───────────────────┼───────────┐   │
                    ▼                   ▼           ▼   │
          ┌─────────────────┐ ┌────────────────┐ ┌─────┴──────────┐
          │ policy_account_ │ │ policy_pricing │ │ endorsement_   │
          │ balance         │ │ _rule          │ │ request        │
          └─────────────────┘ └────────────────┘ └───────┬────────┘
                    ▲                                     │
                    │                              ┌──────┴───────┐
                    │                              ▼              ▼
              ┌─────┴───────┐            ┌──────────────┐ ┌──────────────────┐
              │ ledger_     │            │ insurer_     │ │ employer_        │
              │ entry       │            │ batch_item   │ │ batch_job        │
              └─────────────┘            └──────┬───────┘ └──────────────────┘
                                                │
                                         ┌──────┴───────┐
          ┌──────────────┐               │ insurer_     │
          │ insurer_     │◀──────────────│ batch_job    │
          │ config       │               └──────────────┘
          └──────────────┘

          ┌──────────────┐
          │ audit_log    │     Temporal Server manages workflow state externally.
          └──────────────┘     No outbox_event table needed.
```

---

## 9. Non-Functional Requirements

| Requirement | Target | Mechanism |
|-------------|--------|-----------|
| **Throughput** | 500 endorsements/sec (API), 100K rows/batch | Temporal worker concurrency, connection pooling, child workflow fan-out |
| **Latency (P99)** | < 200ms (API response), < 5min (realtime E2E) | Balance lock timeout 5s, pricing cache, connection pool tuning |
| **Availability** | 99.9% (API), 99.5% (batch processing) | Multi-pod deployment, health checks, Temporal server HA |
| **Durability** | Zero lost endorsements | Temporal durable execution (workflow state persisted to its DB), application DB WAL |
| **Consistency** | Strong for balance, eventual for status | Pessimistic lock on balance, Temporal ensures workflow completion |
| **Idempotency** | All writes are idempotent | `endorsementId` as Temporal workflow ID (dedup), `idempotencyKey` on DB records |
| **Data retention** | Active: 2 years, Archive: 7 years | Monthly partitioning, archive job, Temporal retention policy |
| **Recovery** | RPO < 1 min, RTO < 15 min | DB streaming replication, Temporal auto-resumes in-flight workflows on worker restart |
