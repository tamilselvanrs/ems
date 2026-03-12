# Endorsement Management System (EMS)

> Group insurance endorsement lifecycle management — from HR admin action to insurer confirmation.

[![CI](https://github.com/your-org/ems/actions/workflows/ci.yml/badge.svg)](https://github.com/your-org/ems/actions)

---

## Quick Start (Local)

**Prerequisites:** Docker Desktop, Java 21, Node 20

```bash
# 1. Clone
git clone https://github.com/your-org/ems.git && cd ems

# 2. Start infrastructure
docker-compose up -d

# 3. Run backend (applies Flyway migrations automatically)
cd backend && ./mvnw spring-boot:run

# 4. Run frontend
cd ../frontend && npm install && npm run dev

# 5. Open
open http://localhost:5173
```

Swagger UI: http://localhost:8080/swagger-ui.html  
Actuator: http://localhost:8080/actuator/health

---

## Documentation

| Doc | Description |
|-----|-------------|
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | System design, service responsibilities, data model decisions |
| [STATE_MACHINE.md](docs/STATE_MACHINE.md) | Endorsement request states, transitions, retry logic, balance algorithm |
| [API_CONTRACT.md](docs/API_CONTRACT.md) | REST API reference |
| [AGENTS.md](AGENTS.md) | **AI agent guardrails — read before generating any code** |

---

## Project Structure

```
ems/
├── backend/          Spring Boot 3 / Java 21
├── frontend/         React 18 / Vite / TypeScript
├── scripts/db/       Flyway SQL migrations
├── docs/             Architecture, ADRs, API contracts
└── docker-compose.yml
```

---

## Critical Flow: Add Endorsement

The flagship E2E flow — see [ARCHITECTURE.md §5](docs/ARCHITECTURE.md#5-add-endorsement--e2e-sequence) for the full sequence diagram.

**Short version:**
1. HR Admin submits member addition via UI
2. Backend validates (dedup, balance, effective date)
3. Reserves balance, creates endorsement request
4. Routes to insurer (realtime or batch per insurer config)
5. On insurer confirmation → settles ledger, activates coverage

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Backend | Java 21, Spring Boot 3.2, Spring Data JPA |
| Database | PostgreSQL 15, Flyway migrations |
| Frontend | React 18, Vite, TypeScript, TanStack Query |
| Forms | react-hook-form + zod |
| Testing | JUnit 5, Testcontainers, Mockito, Vitest, RTL |
| Local infra | Docker Compose |

---

## AI-Assisted Development

This repo uses AI agents (Claude, Codex) for code generation. See **[AGENTS.md](AGENTS.md)** for guardrails that ensure consistent, domain-correct output regardless of which model is running.
