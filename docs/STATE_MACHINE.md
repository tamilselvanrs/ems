# Endorsement Request — State Machine

## States

| State | Description | Terminal? |
|-------|-------------|-----------|
| `DRAFT` | Created, not yet submitted | No |
| `VALIDATED` | Passed pre-submission validation (dedup, balance, date) | No |
| `VALIDATION_FAILED` | Failed pre-submission validation — awaiting correction or cancellation | No |
| `QUEUED` | Passed validation, queued for insurer submission | No |
| `SUBMITTED` | Sent to insurer (realtime: awaiting webhook; batch: in batch) | No |
| `INSURER_PROCESSING` | Insurer acknowledged, processing in progress | No |
| `EXECUTED` | Insurer confirmed — coverage active, ledger settled | **Yes** |
| `FAILED_RETRYABLE` | Insurer returned transient error — eligible for retry | No |
| `FAILED_TERMINAL` | Insurer returned permanent error OR max retries exhausted | **Yes** |
| `CANCELLED` | Cancelled by employer before insurer submission | **Yes** |

---

## Valid Transitions

```
DRAFT
  → VALIDATED              (validation passes)
  → VALIDATION_FAILED      (validation fails)
  → CANCELLED              (employer cancels before submission)

VALIDATED
  → QUEUED                 (scheduled submission window opens)
  → CANCELLED              (employer cancels before submission)

VALIDATION_FAILED
  → DRAFT                  (employer corrects and resubmits)
  → CANCELLED              (employer abandons)

QUEUED
  → SUBMITTED              (sent to insurer)
  → CANCELLED              (employer cancels before send — only in batch mode before cutoff)

SUBMITTED
  → INSURER_PROCESSING     (insurer acknowledges)
  → EXECUTED               (insurer confirms immediately — realtime)
  → FAILED_RETRYABLE       (insurer timeout / transient error)
  → FAILED_TERMINAL        (insurer permanent rejection)

INSURER_PROCESSING
  → EXECUTED               (insurer confirms)
  → FAILED_RETRYABLE       (insurer timeout)
  → FAILED_TERMINAL        (insurer permanent rejection)

FAILED_RETRYABLE
  → QUEUED                 (retry scheduled — auto or manual)
  → FAILED_TERMINAL        (max retry_count exceeded)

EXECUTED         → (none — terminal)
FAILED_TERMINAL  → (none — terminal)
CANCELLED        → (none — terminal)
```

---

## Ledger Impact per Transition

| Transition | Ledger Entry Type | Amount |
|------------|------------------|--------|
| `DRAFT → VALIDATED` (ADD type) | `RESERVE` | + estimated_premium |
| `VALIDATED → CANCELLED` | `RELEASE` | - estimated_premium |
| `QUEUED → CANCELLED` | `RELEASE` | - estimated_premium |
| `* → FAILED_TERMINAL` (ADD type) | `RELEASE` | - estimated_premium |
| `* → EXECUTED` (ADD type) | `SETTLE_DEBIT` | Replaces RESERVE with confirmed premium |
| `* → EXECUTED` (DELETE type) | `SETTLE_CREDIT` | + credited amount from insurer |
| `FAILED_RETRYABLE → QUEUED` | (no ledger change) | — |

---

## Retry Logic

- `retry_count` increments on each `FAILED_RETRYABLE → QUEUED` transition
- Max retries defined per endorsement type: **ADD = 5**, **DELETE = 3**, **UPDATE = 3**
- Retry backoff: exponential — 1 min, 5 min, 15 min, 1 hr, 4 hr
- After max retries: auto-transition to `FAILED_TERMINAL` + notify employer

---

## Balance Minimization Algorithm

**Problem:** Employers should maintain the minimum required balance in their EA.

**Approach — Priority Ordering within a submission window:**

1. **Process DELETEs first** — credits come back before new debits go out
2. **Process UPDATEs second** — net zero or net credit in most cases
3. **Process ADDs last** — ordered by `effective_date ASC` (earliest coverage need first)
4. Within each type, order by `priority DESC` then `created_at ASC`

**Pre-submission balance check:**
```
required_balance = sum(estimated_premium for all VALIDATED ADD requests in window)
available_balance = confirmed_ea_balance - reserved_exposure

if available_balance < required_balance:
    - Submit DELETEs/UPDATEs first (async)
    - Recalculate available_balance after credits land
    - Submit ADDs in order of effective_date
    - Alert employer if still insufficient after credits
```

**Reserve-on-validate strategy:**
- Reserve immediately on `DRAFT → VALIDATED` (not on submission)
- This gives the employer real-time visibility into effective available balance
- Reservation released if request is cancelled or terminally failed
