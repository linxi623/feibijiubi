# AGENTS.md

This file provides guidance to coding agents (Claude Code and others) when working with code in this
repository. It is kept identical to `CLAUDE.md`.

## Project overview

菲比啾比（feibijiubi）is a Java 17 + Spring Boot backend for a mini-Bilibili-style video site.
It started as a learning skeleton but now has real, non-trivial modules: user/auth, video
upload & review, user interactions, category browsing, and a full Redis + RabbitMQ video-statistics
aggregation pipeline.

> This document describes the **current** state of the code. When you change controllers, services,
> config, or the MQ pipeline, update this file and `AGENTS.md` (they are kept identical) and
> `docs/api.md`.

## Learning goal

The user's goal is to become a backend engineer who writes standardized（规范化）, maintainable code.
When suggesting or making changes, prioritize clear layering, consistent API design, readable naming,
validation, error handling, and beginner-friendly explanations of *why* a pattern is considered standard.
The `docs/` folder holds long-form Chinese learning guides written alongside each feature — prefer
extending that style when a change introduces a new concept.

## Tech stack

- **Spring Boot 3.5.15**, Java 17, built with Maven (wrapper pinned).
- **MyBatis** 3.0.5 (XML mappers under `src/main/resources/com/feibijiubi/backend/mapper/`) + **MySQL**.
- **PageHelper** 1.4.7 for pagination.
- **Redis** via `spring-boot-starter-data-redis`; **Redisson** 3.36.0 for distributed locks; Lua scripts
  in `src/main/resources/lua/` for atomic counter/rate-limit operations.
- **RabbitMQ** via `spring-boot-starter-amqp` for the video-statistics event pipeline.
- **JJWT** 0.11.5 for stateless login tokens.
- **Tencent Cloud COS** (`cos_api` + `cos-sts_api`) for direct-to-COS video/cover uploads.
- **Lombok** (annotation processor, excluded from the packaged jar).
- **Bean Validation** (`spring-boot-starter-validation`) for DTO checks.

## Common commands

Use the Maven wrapper from the repository root. On Windows PowerShell/cmd use `mvnw.cmd`; the Git Bash
tool can use `./mvnw`.

```bash
# Run the full test suite
./mvnw test

# Run a single test class / method
./mvnw -Dtest=UserAccountServiceImplTests test
./mvnw -Dtest=BackendApplicationTests#contextLoads test

# Compile without tests / build the jar / run locally
./mvnw -DskipTests compile
./mvnw package
./mvnw spring-boot:run
```

Local infrastructure (Redis + RabbitMQ) is provided by Docker Compose; MySQL must be running separately
(see `application.yml` for the expected `feibijiubi` schema and connection).

```bash
# Start Redis (6379) and RabbitMQ (5672, management UI 15672) locally
docker compose up -d
```

There is no lint or formatting plugin configured in `pom.xml`.

## Configuration

Runtime configuration lives in `src/main/resources/application.yml` (not `application.properties`). It
configures `spring.datasource`, `spring.data.redis`, `spring.rabbitmq`, MyBatis, `jwt`, `tencent.cos`,
and the `app.video-status.*` feature flags / tuning knobs below.

> ⚠️ `application.yml` currently contains real secrets (DB password, COS keys, JWT secret). Do **not**
> commit new secrets or paste these values into other files, docs, or logs. Treat them as sensitive.

Key `app.video-status.*` flags (see `VideoStatusProperties`):

- `async-enabled` – publish interaction deltas asynchronously.
- `outbox-relay-enabled` – enable the outbox → RabbitMQ relay scheduler.
- `scheduling-enabled` – master switch for the batch-flush / recovery schedulers.
- `round-budget-ms` – per-round work budget for relay/flush/recovery (default 5000); exhausting it
  stops claiming new work but does not hard-interrupt an in-flight publish or transaction.
- plus batch sizes, fixed delays, lease/timeout seconds, and consumer retry limits.

RabbitMQ listeners default to `auto-startup: false` and manual ack — they are opt-in per environment.

## Architecture and structure

Application code lives under `com.feibijiubi.backend`. Entry point:
`BackendApplication` (`@SpringBootApplication`). Standard layering: Controller → Service (interface +
`impl`) → MyBatis Mapper; DTO for requests, VO for responses, `converter` for entity↔VO mapping,
`ApiResponse<T>` as the uniform envelope, `GlobalExceptionHandler` + `BusinessException` for errors.

Notable packages:

- `annotation/` – method markers read by interceptors: `@OptionalLogin`, `@AdminOnly`,
  `@AllowRevokedToken`.
- `interceptor/` – `LoginInterceptor` (validates JWT, checks Redis blacklist, writes `currentUserId` /
  token context into the request) and `AdminInterceptor` (`/api/admin/**`). Registered in `WebMvcConfig`;
  only `/api/auth/register` and `/api/auth/login` are excluded from login.
- `service/auth/` – `TokenService` / `JwtTokenServiceImpl`: JWT issue/verify, `token_version` check for
  multi-device invalidation on password change, and Redis blacklist for logout.
- `service/user/` – current-user and public-profile queries, avatar/background uploads, and optional-login
  profile responses that compute `subscribed` from the visitor and target user IDs.
- `service/ratelimit/` – fixed-window rate limiting via Lua (login-failure lockout, COS upload-credential
  throttle); returns `429` when exceeded.
- `service/storage/` – `FileStorageService` / Tencent COS: STS temp credentials for direct video upload,
  cover upload, and post-transaction cleanup on delete.
- `service/category/` – category tree, cached with Cache-Aside in Redis.
- `service/video/` – submit / review state machine / delete / detail / feed (keyset/cursor pagination,
  optional category filter).
- `service/video/videostatus/` + `mq/` – the video-statistics aggregation pipeline (see below).
- `metrics/` – process-local finite-enum telemetry (`VideoStatusTelemetry`) and the 15 s read-only
  shared-DB backlog sampler/alarmer (`VideoStatusBacklogSampler`), surfaced by the admin metrics
  controller.
- `utils/redis/` – `RedisUtils`, `RedisKeyUtils`, `RedisConstants`, typed Hash/Set/ZSet operation helpers.
- `utils/rabbitmq/` – `RabbitConstants` (exchange/queue/routing-key names).

### Video-statistics aggregation pipeline (current focus area)

Interaction counts (play / like / coin / collect / share) are **not** written straight to the DB.
The flow is:

1. Interaction → MySQL outbox event in the business transaction (`VideoStatusChangedEvent` /
   `VideoStatusDelta`); Redis is updated by the consumer, not before publishing.
2. **Outbox pattern**: `VideoStatusOutbox` rows are claimed (`VideoStatusOutboxClaimService`) and relayed
   to RabbitMQ (`VideoStatusOutboxRelay`) with publisher confirms.
3. **Consumer** (`VideoStatusEventConsumer` → `VideoStatusConsumptionServiceImpl`) applies deltas
   idempotently, guarded by `VideoStatusConsumedEvent`, fingerprint checks, and atomic Redis Lua.
   The normal apply path takes no vid lock. Cache misses/rebuild gates enter a locked slow path.
4. **Batch flush** (`VideoStatusBatchFlushScheduler` → `...BatchFlushServiceImpl`) locks and reads a
   batch of status-1 consumed events for one video, applies their deltas to the `video_status` table,
   and marks those events status 2 in the same MySQL transaction.
5. **Recovery / rebuild** schedulers recover dirty video IDs and rebuild Redis current state from the
   MySQL baseline plus confirmed status-0/1 consumed events (`VideoStatusRebuild*`). Rebuild holds the
   same vid lock as flush and closes a token-owned Redis gate before loading the snapshot; increment
   Lua checks this gate, initialization/release verify its token. Gates have no TTL (next lock owner
   takes over after a crash); use noeviction. Dirty refresh only re-adds pending vids, never removes
   a marker concurrently added by an unlocked consumer. Recovery remains required.

### Accepted correctness and runtime contracts (T02–T04)

- All eight video counters and personal received-like totals use `Long`, range `0..Long.MAX_VALUE`;
  identifiers remain `Integer`. SQL computes guarded updates with `DECIMAL(20,0)` (no unsigned
  subtraction or blanket signed cast); Redis Lua validates canonical decimal strings before exact
  `HINCRBY`, without converting counters through floating point. Bad cached fields raise explicit
  errors, not false zeroes. JSON counters remain numbers: clients need lossless parsing above 2^53-1.
- Flush uses `BigInteger` for batch net sums. A temporary lower/upper bound violation defers with a
  bounded local 2-second backoff; repeated deferral alerts but never silently clears or quarantines
  legal events. On an upper-bound/net-overflow batch it tries at most one negative-first locked
  batch, preserving progress with batch size 1. Unknown types and missing statistics rows still
  enter controlled repair. Statistics and FLUSHED markers remain one MySQL transaction.
- `outbox-relay-enabled` defaults true and is enforced: false skips lease recovery, claim and
  publish, independently of listener/flush. Startup validates batch/duration/retry/replay parameters
  and logs the local role matrix. Async without scheduling is legal for maintenance/split roles,
  but needs another flusher. Switching sync/async requires stopped ingress, drained events/queues,
  and coordinated cache rebuild; it is not a supported live hot-switch operation.
- No schema change in T02–T04. Before rollout, audit counters above Long.MAX and rollback risks
  above Integer.MAX; deploy compatible writers together. Do not deploy T02 without T03. Redis
  validation failure causes no writes; arbitrary Redis runtime failures/data loss are NOT covered
  by a generic rollback or exactly-once promise.

### Accepted observability and scheduling contracts (T05–T06)

- T05 adds no monitoring stack: only process-local in-memory counters over a fixed
  `Operation × Outcome` enum plus a 15 s (5 s initial delay) read-only SQL backlog sample.
  `GET /api/admin/video-status/metrics` returns the cached snapshot; it does not query MySQL or
  Redis in the request path. `GET /api/admin/video-status/gate?vid=...` performs one read-only Redis
  GET. Both are under `/api/admin/**` with `@AdminOnly`; there is no actuator or unauthenticated
  metrics endpoint.
- Shared-DB backlog covers outbox 0/1/2/3 and consumed 0/1/3, capped at 10001 (`countCapped` means
  `>= 10001`). Oldest ages use `(status, created_at, id)` / `(process_status, consumed_at, id)` and
  database `NOW(3)`. Run `database/migrations/t05-video-status-observability-indexes.sql` **before**
  enabling sampling. Missing indexes produce `DEGRADED` diagnostics and skip counts instead of a full
  scan; the migration is idempotent. Sampling and both HTTP endpoints never write Redis, change
  event state, or add TTL to the rebuild gate.
- Test thresholds are oldest pending >120 s and scheduler heartbeat >30 s; production thresholds are
  T16 work. Alarms are emitted as logs and in the HTTP snapshot only: there is **no external alert
  delivery**. Local `instanceTelemetry` resets on restart and must not be added to the global
  shared-DB backlog. `STALE` after 45 s means the sampler stopped producing fresh attempts.
- T06 gives relay, flush and recovery three independent single-thread `ThreadPoolTaskScheduler` beans
  (`videoStatusRelayScheduler` / `videoStatusFlushScheduler` / `videoStatusRecoveryScheduler`), each
  with a stable thread-name prefix, `fixedDelay`, exception isolation and 5 s bounded shutdown.
  `fixedDelay` plus one thread prevents overlap; the executors hold only the three fixed recurring
  tasks, not per-message/per-vid submissions. `round-budget-ms` stops claiming new work; flush uses a
  100 ms per-vid scheduler lock wait (other paths keep 10 s), requeues dirty work without `SREM`, and
  recovery leaves unprocessed vids in MySQL for the next round.
- T06 acceptance used two independent scheduler objects in **one JVM** sharing the same Redis
  distributed lock, not two JVMs. The publish fault is a controlled in-process block, not a real 30 s
  network disconnect. These are test-environment acceptance results, not production acceptance.

### T07 outbox reliability (accepted, test environment)

- T07 is implemented on the main branch at `95af60d` and accepted on 2026-10-22 after the parent
  reran the full-chain regression
  `./mvnw -DvideoStatusIT=true '-Dtest=VideoStatusOutbox*Tests,VideoStatusIsolatedIntegrationTests,VideoStatusMetricsIsolatedTests,VideoStatusSchedulingRealFlowTests,VideoStatusDirtyRecoveryRealConvergenceTests' test`
  with **87 tests, 0 failures/errors, 1 skipped** (optional benchmark), log
  `/tmp/improvement-main-regression3.log`. Earlier T07-only runs were 61 tests, 0
  failures/errors/skipped (`/tmp/improvement-t07-verify.log`, `/tmp/improvement-t07-main.log`).
  This is test-environment acceptance, not production acceptance and not a real process-crash or
  two-JVM drill.
- Publish/confirm/writeback failures are classified separately. `retry_count` is publish-failure
  budget; `lease_recovery_count` is lease-recovery budget; `confirm_writeback_count` is the budget
  for “confirm ACK succeeded but `markSent` failed”. The three budgets must not consume each other.
  The 10th lease recovery fails with `lease recovery budget exhausted`; the 10th confirmed-writeback
  failure fails with `confirm writeback budget exhausted` instead of requeueing forever.
- Schema/mapper changes are part of this branch: `database/migrations/t07-video-status-outbox-lease-recovery.sql`
  and `t07-video-status-outbox-confirm-writeback.sql`, mirrored in `database/feibijiubi.sql`.
  Migrations are idempotent, add columns without backfilling/zeroing history, and MySQL UPDATE
  assignment order keeps the increment after all `CASE` expressions.
- The only T07 admin entry points are the T10 narrow subset:
  `GET /api/admin/video-status/outbox/{eventId}` (safe read-only status) and
  `POST /api/admin/video-status/outbox/{eventId}/replay` (single FAILED event, audited, same
  transaction, operator from login state). They never return payload/leaseToken/raw `last_error`,
  never create a new eventId, and reject existing consumed rows or unsafe windows. Batch/DLQ/
  consumed status3 repair and reconciliation remain T10.
- Runbook and limits: `docs/tutorial/video-status-outbox-reliability.md`,
  `video-status-outbox-lease-migration.md`, and `video-status-outbox-replay-runbook.md`.

### T08 consumer retry and ACK boundaries (accepted, test environment)

- T08 is implemented on the main branch via `0dd98ad`, `b846032` and `92eb8c1` (candidate base
  `b796fc8`) and accepted on 2026-10-22. The parent reran
  `./mvnw -DvideoStatusIT=true '-Dtest=VideoStatusIsolatedIntegrationTests,VideoStatusConsumerRetryLifecycleIsolatedTests' test`
  on the independent candidate parent with **22 tests, 0 failures/errors, 1 skipped**, log
  `/tmp/t08-parent-final.log`.
- Retries use three fixed-TTL queues (10 s / 30 s / 120 s; the original 10 s queue name and
  parameters are unchanged). Attempt 1/2/3+ routes to 10/30/120 s, so one TTL never head-of-line
  blocks another. Forwarding failure neither ACKs nor NACKs: a single dedicated recovery task
  asynchronously stops the listener container and restarts it after
  `consumer-recovery-delay-ms` (default 10000), so the consumer thread never sleeps or requeues hot.
- ACK errors are handled separately from business and forwarding errors: a successfully handled
  message whose ACK fails is never forwarded to retry/DLQ and never NACKed; the unacked message is
  redelivered and absorbed by eventId + fingerprint idempotency. At-least-once is retained; no
  cross-RabbitMQ/MySQL exactly-once claim.
- The isolated acceptance exercised real 10/30/120 s TTL expiry, real listener container stop/start,
  and a server-side Redis `CLIENT PAUSE 120000` where the process and network connection stayed
  alive. It is **not** a Redis process restart, network disconnect, or arbitrary Redis data-loss
  guarantee; those remain T16 work.

### T09 status0 compensation (accepted, test environment)

- T09 first phase is implemented on the main branch at `97c7357` (candidate `aab1c64`) and accepted
  in the **test environment** on 2026-10-22; this is not production acceptance. The parent ran on
  the main branch `./mvnw '-Dtest=*Tests,!BackendApplicationTests' test` with **356 tests, 0
  failures/errors, 108 skipped** (log `/tmp/t09-main-unit.log`) and
  `./mvnw -DvideoStatusIT=true '-Dtest=VideoStatusStatus0CompensationTests,VideoStatusStatus0CompensationAuthorizationTests,VideoStatusStatus0CompensationIsolatedIntegrationTests,VideoStatusIsolatedIntegrationTests,VideoStatusOutboxReliabilityIsolatedTests' test`
  with **62 tests, 0 failures/errors, 1 skipped** (log `/tmp/t09-main-it.log`). The earlier candidate
  run was **43 tests, 0 failures/errors/skipped** (8 real isolated IT), log
  `/tmp/t09-parent-final.log`.
- Three gates default to false: `status0-compensation-enabled`,
  `status0-compensation-integrity-confirmed`, `status0-compensation-allow-unproven-replay`.
  `enabled=true` without integrity confirmation fails startup. Unproven replay requires all three;
  it is an operator assertion that no Redis data loss/partial-write incident occurred, not automatic
  detection. Missing `processed` evidence is `EVIDENCE_UNKNOWN`: it may mean never applied, or current
  applied with only `processed` lost, so it must not be treated as "not applied".
- The proven path only republishes the original eventId after payload/event/fingerprint/DB-age/
  `occurredAt` checks and a Redis processed key whose remaining TTL covers the maximum MQ retry
  backoff plus max(compensation lease, publish confirm). Missing/unknown/insufficient TTL defaults to
  no send and no status3, only lease release, cooldown and diagnostics. The controlled unproven mode
  is not a guarantee against arbitrary Redis data loss or `current + processed` loss.
- Preview is the only new HTTP entry point: read-only admin
  `GET /api/admin/video-status/status0-compensation/preview?limit=1..100&afterId>=0`. It does not
  claim a lease, publish MQ, or write Redis/DB. There is no runtime pause/write endpoint.
- yaml is not hot-reloaded. Incident response must disable `status0-compensation-enabled` on **all**
  compensation-role instances and restart them, then let in-flight publish/confirm finish or let the
  lease expire; do not hard-kill an in-flight publish.
- Claims are one row per short transaction with DB-clock lease CAS and an independent
  `compensation_attempts` budget; republish writeback does not increment `consumer_retry_count`.
  Migration `database/migrations/t09-video-status-status0-compensation.sql` is idempotent and only
  adds compensation columns/index, mirrored in `database/feibijiubi.sql`.
- Failure history retained: an earlier verification used a wrong switch that skipped the intended
  tests, and mapper parameter/timezone handling failed before being fixed. Do not copy credentials
  into docs or logs. T09 remains test-environment evidence only, not production acceptance; the
  no-evidence (unproven) replay mode stays controlled and default off.
- Guide: `docs/tutorial/video-status-status0-compensation.md`.
### T11 dirty-recovery fairness (accepted, test environment)

- T11 is implemented at candidate `be7d836` and accepted on 2026-10-22. The parent reran
  `./mvnw -DvideoStatusIT=true '-Dtest=VideoStatusDirtyRecoveryRealConvergenceTests,VideoStatusDirtyRecoveryFairnessIsolatedTests' test`
  with **3 tests, 0 failures/errors/skipped** (log `/tmp/t11-real-convergence.log`). The run printed
  `T11 real convergence vids=250 head=100 tail=150 flushRounds=1 oracleTotal=250`.
- The acceptance used real MySQL and Redis in the isolated `compose.test.yaml` environment, with a
  dedicated SADD fault injected only for the 100 head vids. The 150 healthy tail vids all got a
  recovery opportunity within four rounds, and the final 250 vids converged with no `process_status=3`.
  The oracle comes from independently seeded expected values and is also checked against
  `SUM(delta)` over the consumed-event table.
- The recovery scheduler now uses a keyset cursor (`vid > cursor`) with wrap-around and a bounded
  local failure backoff (2 s, 4096-entry LRU). It only `SADD`s dirty markers and never adds `SREM`;
  cursor/backoff are process-local and losing them only affects efficiency. `be7d836` fixes the
  candidate defect where exhausting the round budget before the first vid ran could roll the cursor
  back instead of leaving the previous keyset progress intact; the cursor now advances only to an
  actually processed vid.
- This is not a process-crash drill and not production acceptance: the fault is an in-process SADD
  injection on dedicated test vids, not a real Redis/MySQL outage or a two-JVM drill. Capacity and
  real infrastructure failures remain T16 work.

Guides: `docs/tutorial/video-status-counter-range.md`, `video-status-flush-defer.md`,
`video-status-runtime-roles.md`, `video-status-observability.md`,
`video-status-scheduling-isolation.md`, `video-status-outbox-reliability.md`,
`video-status-outbox-lease-migration.md`, and `video-status-consumer-retry-ack.md` (all under
`docs/tutorial/`). Acceptance progress and
remaining recovery/observability work: `docs/tutorial/video-status-improvement-plan.md`.

Consumer retry/dead-letter behavior is driven by the typed exceptions in `common/`:
`RetryableMessageException`, `NonRetryableMessageException`, `RepairRequiredMessageException`.

Design docs: `docs/tutorial/video-status-full-flow-design.md`,
`docs/tutorial/video-status-batch-aggregation-implementation.md`,
`docs/tutorial/video-stats-redis-mq-design.md`, and
`docs/tutorial/video-status-lock-free-fast-path.md` (protocol, failure limits, rollout and tests).
Do not mix old/new consumers during rollout: old Lua does not honor the rebuild gate.

## Data & docs

- `database/` – SQL schema (`feibijiubi.sql`; `teriteri.sql` is the reference project it is modeled on).
- `docs/` – extensive Chinese learning/design guides (JWT, Redis, Lua rate limiting, MQ aggregation,
  COS direct upload, transactions, etc.) and `docs/api.md` (the HTTP API reference — keep it in sync).

## Testing

JUnit 5 via `spring-boot-starter-test`. Beyond `BackendApplicationTests.contextLoads()` there are unit
tests for the exception handler, response converter, login interceptor, JWT service, rate-limit service,
user-account service, and Redis utils under `src/test/java/...`.

## Conventions worth preserving

- **HTTP method convention**: `GET` for reads, `POST` for every write (create/update/delete/action).
  The codebase does not currently use `PUT`/`DELETE`. Match this when adding endpoints.
- Passwords are still stored/compared in plaintext (learning stage; a `TODO` marks where hashing goes).
- Business errors return HTTP 200 with a business `code` in the `ApiResponse` body; the global handler
  wraps unhandled errors as `code = 500`.

## Isolated video-statistics integration tests

`docker compose -f compose.test.yaml up -d --wait` starts the independent `feibijiubi-test` project
(MySQL 13307, Redis 16379, RabbitMQ 15673 / management 15674). Never merge it with `compose.yaml`.
Run `./mvnw -DvideoStatusIT=true -Dtest=VideoStatusIsolatedIntegrationTests test`; add
`-DvideoStatusBenchmark=true` for an optional diagnostic hot-path comparison. T05/T06 acceptance
classes are `VideoStatusMetricsIsolatedTests` and `VideoStatusSchedulingRealFlowTests` (the latter
blocks publish for about 30 s by design). T11 acceptance classes are
`VideoStatusDirtyRecoveryRealConvergenceTests` and
`VideoStatusDirtyRecoveryFairnessIsolatedTests`; the non-IT fairness unit tests live in
`VideoStatusDirtyRecoveryFairnessTests`. The T08 acceptance class is
`VideoStatusConsumerRetryLifecycleIsolatedTests` (real 10/30/120 s TTL, container stop/start, and
Redis `CLIENT PAUSE`). Tests replace application.yml, reject non-isolated endpoint
overrides before connecting, seed new rows and purge only the dedicated test queues. Existing
deployment volumes are never used. Normal test runs skip this opt-in suite. Stop with
`docker compose -f compose.test.yaml stop` to preserve test data.

T09 acceptance classes are `VideoStatusStatus0CompensationTests`,
`VideoStatusStatus0CompensationAuthorizationTests` and
`VideoStatusStatus0CompensationIsolatedIntegrationTests`; the main-branch parent run was 62 opt-in
IT tests (0 failures/errors, 1 skipped, log `/tmp/t09-main-it.log`) plus 356 unit tests (0
failures/errors, 108 skipped, log `/tmp/t09-main-unit.log`). T09 is accepted in the test
environment only; the unproven replay mode remains controlled and default off. T10 is in progress
in an independent tree with a first-stage 15-unit-test candidate that is not merged; no DLQ or
reconciliation item is complete.
## Docker Compose deployment

`compose.yaml` starts MySQL, Redis, RabbitMQ, and the Spring Boot backend together. The backend is
built by the multi-stage `Dockerfile`; MySQL runs `database/feibijiubi.sql` automatically when its
named volume is initialized for the first time. Use `docker compose up -d --build` and access the API
at `http://localhost:8080`. Set `MYSQL_ROOT_PASSWORD`, `MYSQL_DATABASE`, `RABBITMQ_DEFAULT_USER`,
`RABBITMQ_DEFAULT_PASS`, or `BACKEND_PORT` in a `.env` file to override defaults. To recreate the
database from the SQL script, remove the named volume with `docker compose down -v`.
