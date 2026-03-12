# AGENTS.md — EMS Developer & AI Agent Guardrails

> This file governs all AI-assisted development on the Endorsement Management System.
> It applies to Claude (claude.ai / API) and OpenAI Codex equally.
> **Every agent MUST read this file before generating, modifying, or reviewing any code.**

---

## 1. Project Overview (Agent Context)

This is an **Endorsement Management System (EMS)** for group insurance.
- **Stack:** React 18 (Vite) · Java 21 (Spring Boot 3) · PostgreSQL 15
- **Critical domain invariant:** `available_balance = confirmed_ea_balance - reserved_exposure`
- **Core flow:** HR Admin adds member → endorsement_request created → insurer submission → ledger update
- **All monetary values** are stored as `BIGINT` in the smallest currency unit (e.g., paisa/cents). Never use `FLOAT` or `DOUBLE` for money.

---

## 2. Repository Structure

```
ems/
├── AGENTS.md                  ← YOU ARE HERE
├── README.md
├── docs/
│   ├── ARCHITECTURE.md        ← Read before designing any feature
│   ├── STATE_MACHINE.md       ← Endorsement request state transitions
│   ├── API_CONTRACT.md        ← OpenAPI reference
│   └── ADR/                   ← Architecture Decision Records
├── backend/                   ← Spring Boot 3 / Java 21
│   └── src/
│       ├── main/java/com/ems/
│       └── test/java/com/ems/
├── frontend/                  ← React 18 / Vite / TypeScript
│   └── src/
├── scripts/
│   └── db/                    ← Flyway migration SQL files
└── .github/
    ├── workflows/             ← CI/CD pipelines
    └── ISSUE_TEMPLATE/        ← GitHub issue templates
```

---

## 3. Dev Loop Rules (Non-Negotiable)

### 3.1 Before Writing Any Code
- [ ] Read `docs/ARCHITECTURE.md` for the relevant domain area
- [ ] Read `docs/STATE_MACHINE.md` if touching endorsement_request status
- [ ] Check if a migration is needed — if yes, create it in `scripts/db/` before touching entities
- [ ] Check for an existing service/repository before creating a new one

### 3.2 Implementation Rules

**Java / Spring Boot**
- Package structure: `com.ems.{domain}.{layer}` — never flatten packages
- Every public service method MUST have a corresponding unit test
- Use `@Transactional` on service methods that write to multiple tables
- All DB writes that touch `policy_account_ledger` MUST go through `LedgerService` — never write to the ledger directly from a controller or scheduler
- Use `BigDecimal` for any balance arithmetic that crosses service boundaries; convert to `Long` (smallest unit) only at persistence time
- DTOs live in `dto/request` and `dto/response` — entities MUST NOT be returned from controllers
- Idempotency: all `POST` endpoints that create endorsement requests MUST check `idempotency_key` before processing
- Error handling: use `GlobalExceptionHandler` — never catch-and-swallow exceptions silently

**Database / Migrations**
- All schema changes go through Flyway: `scripts/db/V{n}__{description}.sql`
- Never use `ALTER TABLE ... DROP COLUMN` without a corresponding deprecation ADR
- All tables MUST have `created_at` and `updated_at` with default `NOW()`
- Add indexes for every FK column and every column used in `WHERE` clauses in hot paths

**React / TypeScript**
- All API calls go through `src/api/` — never use `fetch` directly in components
- Use `React Query` (`@tanstack/react-query`) for all server state — no manual `useEffect` fetching
- Form state uses `react-hook-form` + `zod` for validation
- No `any` types — use the types in `src/types/`
- Components are functional only — no class components

### 3.3 Testing Rules

| Layer | Required Coverage | Tool |
|-------|------------------|------|
| Service unit tests | All public methods | JUnit 5 + Mockito |
| Repository tests | All custom queries | `@DataJpaTest` + Testcontainers |
| Controller tests | All endpoints | `@WebMvcTest` + MockMvc |
| Integration tests | Critical flows (Add Endorsement E2E) | Testcontainers + full Spring context |
| Frontend unit tests | All form validation + API hooks | Vitest + React Testing Library |

- **Never mock the database in integration tests** — use Testcontainers with a real Postgres image
- Test method naming: `methodName_givenCondition_expectedBehavior()`
- Each test must be independent — no shared mutable state between tests

### 3.4 Before Committing
- [ ] `./mvnw test` passes with zero failures
- [ ] `npm run test` passes with zero failures
- [ ] `npm run lint` shows no errors
- [ ] No TODO comments left in production code (use GitHub issues instead)
- [ ] Migration files are named correctly and sequential

---

## 4. Domain Rules Agents Must Enforce

These rules come from the invariants in `docs/ARCHITECTURE.md`. Agents must flag violations, not silently implement around them.

1. **Balance invariant:** Any code path that processes an ADD endorsement MUST check `available_balance >= estimated_premium` before reserving. If insufficient, throw `InsufficientBalanceException`.
2. **Idempotency:** Every endorsement request creation is idempotent on `idempotency_key`. If a duplicate key is received, return the existing request — never create a duplicate.
3. **Effective date:** Endorsement effective date must be validated against `insurer_config.backdate_window_days`. Reject if outside the allowed window.
4. **Terminal states:** Once an `endorsement_request` reaches `EXECUTED` or `FAILED_TERMINAL`, its status MUST NOT be updated. Any attempt must throw `InvalidStateTransitionException`.
5. **Ledger writes:** Every status transition on `endorsement_request` that changes financial exposure MUST produce a corresponding `policy_account_ledger` entry in the same transaction.
6. **Insurer submission routing:** Submission mode (`REALTIME` vs `BATCH`) is determined by `insurer_config.execution_mode` — never hardcode the mode.

---

## 5. What Agents Must NOT Do

- ❌ Do not generate code that directly queries `endorsement_request` and updates `policy_account_ledger` in separate transactions
- ❌ Do not introduce new dependencies without adding them to the ADR directory
- ❌ Do not create new REST endpoints that bypass the `GlobalExceptionHandler`
- ❌ Do not write raw SQL in service classes — use repository methods or named queries
- ❌ Do not return entity objects from `@RestController` methods
- ❌ Do not skip the idempotency check on endorsement creation
- ❌ Do not use `System.out.println` — use SLF4J logger (`log.info`, `log.error`, etc.)
- ❌ Do not hardcode insurer URLs, credentials, or QPS limits — all from `insurer_config`

---

## 6. GitHub Issues Workflow

When picking up a GitHub issue to implement:

1. **Read the issue fully** — check for linked ADRs or design comments
2. **Branch naming:** `feat/EMS-{issue-number}-{short-description}` or `fix/EMS-{issue-number}-{short-description}`
3. **Scope check:** If the implementation requires touching more than 3 domain areas, **stop and comment on the issue** asking for scope clarification before proceeding
4. **PR checklist** (must be satisfied before opening a PR):
   - Implementation follows all rules in Section 3
   - Tests added/updated per Section 3.3
   - `docs/` updated if behavior or API contract changed
   - Migration added if schema changed
   - PR description references the issue with `Fixes #N`

---

## 7. Code Generation Guidance (Model-Specific)

### Claude (claude.ai / API)
- Prefer generating complete, compilable files over snippets
- When generating migrations, always include a rollback comment
- When asked to "implement a feature," generate: entity/DTO → repository → service → controller → test, in that order

### OpenAI Codex
- Always provide the full file path as a comment at the top of generated code
- When generating tests, scaffold the full test class including `@ExtendWith` and `@BeforeEach`
- Prefer explicit over implicit — always spell out annotations, never rely on Spring Boot auto-detection defaults without a comment

---

## 8. Glossary

| Term | Definition |
|------|-----------|
| EA | Endorsement Account — the employer's balance held with the insurer |
| Confirmed EA Balance | Balance confirmed by insurer (source of truth) |
| Reserved Exposure | Sum of premiums for endorsements in non-terminal pending states |
| Available Balance | Confirmed EA Balance − Reserved Exposure |
| Endorsement | A change to insurance coverage (add/delete/update member) |
| Policy Account | Links an employer to an insurer policy |
| Terminal State | `EXECUTED` or `FAILED_TERMINAL` — no further transitions allowed |
| Backdate Window | Max days in the past an effective date can be set |
