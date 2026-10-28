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
- `status3-replay-max-attempts` – T10-B1 cap (default 3, validated 1..100) on audited human replays of
  one consumed status3 event; counted from `REPLAY_CONSUMED_TO_RECEIVED` audit rows with a bounded query.
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
  consumed status3 repair remain T10; the T10 first-phase failure diagnostics and read-only
  reconciliation are now merged (see the T10 section below).
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

### T10 failure diagnostics, read-only reconciliation and status3 replay (first phase merged; T10 in progress)

- The T10 first phase is merged on main at `6fc5e7f` (preceding `d21ebd7`, `66f9bb0`,
  `6a46fd0`, `2641897`, `e2c6ea6`). **T10 itself is still in progress**: DLQ ingestion/processing,
  flush-origin status3 repair, batch actions and automatic repair/convergence are not implemented.
- Parent verification on 2026-10-22: opt-in IT **121 tests, 0 failures/errors/skipped**
  (`/tmp/improvement-t10-main-it.log`), covering T10 failure/reconciliation plus T07 replay
  and T09 compensation; normal unit run **416 tests, 0 failures/errors, 130 skipped**
  (`/tmp/improvement-t10-main-final-unit.log`). These are test-environment results, not
  production acceptance.
- Failure admin endpoints: `GET /api/admin/video-status/failures` (keyset `source=OUTBOX`
  default or `CONSUMED`, `afterId>=0`, `limit=1..100`), `GET .../{eventId}`,
  `GET .../{eventId}/precheck`, and `POST .../{eventId}/acknowledge`. OUTBOX lists only
  status3; CONSUMED lists status0 (diagnostic only) and status3. The list queries require
  the exact `(status,id)` / `(process_status,id)` index key order and return HTTP 503 with
  no scan when it is missing or wrong. Responses never expose payload, lease tokens or raw
  `last_error`; safe reason codes are returned instead.
- `acknowledge` is not “ignore”: it only writes an `ACKNOWLEDGE_FAILURE` audit row, only
  for OUTBOX FAILED or consumed status3, and records `before == after` to prove it did not
  change the target. Consumed status0 is read-only and ack returns 409. A repeated
  operationId with the same target/source/action/operator/reason returns a duplicate result;
  concurrent duplicates for the same target are absorbed after the target row lock, while
  reuse on a different target or with a different shape is 409. Historical
  `retry_count`/budget counters only guide diagnosis; they do not prove the current failure
  cause.
- Read-only reconciliation: `GET /api/admin/video-status/reconciliation?vid=<positive>&limit=1..1000`
  (default 100). It separately samples DB eight counters, bounded ledger counts, a bounded
  event window, bounded `user_video` relation truth and Redis current counters. Ledger counts
  are capped independently per status (`cap = limit + 1`, per-status `Capped`, aggregate
  `truncated`); event and relation windows are also lower bounds when truncated. Results are
  explicitly `CONCURRENT_OBSERVATION_NOT_ATOMIC`, `incomplete` is set on truncation/unknown
  data, and the verdict is always `NO_SAFE_CONCLUSION_AUTOMATIC_REPAIR_DISABLED`; the
  endpoint never writes or proves convergence.
- If the `(vid,id)` consumed-event index is missing, reconciliation skips only the expensive
  event projection (`indexAvailable=false`, empty window, `INDEX_MISSING`, `incomplete=true`)
  and still runs the other bounded reads. DB exceptions use the standard global 500; Redis
  read failures are caught as an explicit `READ_FAILED` Redis status with null counters,
  never fabricated zeroes.
- Required migrations: `database/migrations/t10-video-status-failure-admin-indexes.sql` and
  `t10-video-status-reconciliation-indexes.sql`; both indexes are already mirrored in
  `database/feibijiubi.sql`. Guides: `docs/tutorial/video-status-failure-admin.md`,
  `docs/tutorial/video-status-reconciliation.md`.

### T10-B1 consumed status3 replay (candidate, not yet accepted)

- `POST /api/admin/video-status/failures/{eventId}/replay?source=CONSUMED` is the single write path:
  one MySQL transaction resets `process_status 3 → 0` (CAS on `process_status=3 AND
  redis_applied_at IS NULL AND payload_hash=?`), moves the same eventId's outbox row `2 SENT → 0
  PENDING` (clearing `next_retry_at`/`sending_at`/`lease_token`) and inserts a `COMPLETED`
  `REPLAY_CONSUMED_TO_RECEIVED` audit row. Any non-1 row count or exception rolls everything back.
- Delivery is **not** done in the request thread and not after commit: the existing outbox relay
  claims the PENDING row and publishes it with its own confirm/backoff/lease recovery, so a crash
  after commit still delivers. The endpoint refuses (409) when the outbox row is missing or not
  `SENT` instead of degrading to an in-memory send. No new queue and no consumer-protocol change:
  the consumer already absorbs the duplicate eventId (`DUPLICATE` → `markRedisApplied` → flush).
- Only the proven path exists. `redis_applied_at IS NOT NULL` (flush-origin status3) is rejected; a
  missing `processed` key is `CONSUMED_PROCESSED_EVIDENCE_MISSING` and is never treated as “not
  applied”; insufficient/unknown TTL and Redis read failures are rejected (never fail-open). There is
  no unproven mode and no substitution for T09's integrity gate; arbitrary Redis data loss is not
  covered. Age windows are double-checked (DB `TIMESTAMPDIFF` on `consumed_at` plus payload
  `occurredAt` with a 300 s future tolerance) and `consumed_at`/`consumer_retry_count`/
  `last_error`/all three outbox budgets are left untouched (`markRedisApplied` clears `last_error`).
- `GET .../{eventId}/precheck?source=CONSUMED` now returns a `replay` sub-record for status3 built by
  the same evaluation method, so read and write refusals share one code (`replay.refusalCode`). It
  stays read-only (one Redis `GETEXPIRE`) and never answers `SAFE`. `acknowledge` and the list
  endpoints are unchanged.
- Tests: `VideoStatusStatus3ReplayServiceTests` (refusal matrix, CAS guards, idempotency) and the
  opt-in `VideoStatusStatus3ReplayIsolatedTests` (real MySQL 13307 + Redis 16379 + RabbitMQ 15673:
  proven replay converges through the real relay/consumer/flush exactly once, refusals leave zero
  changes, concurrent same `operationId` applies once, audit failure rolls back the real MySQL
  transaction, budget exhaustion). Transaction rollback is proven with real MySQL, not Mockito.
  Guide: `docs/tutorial/video-status-status3-replay.md`.

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

### T12-A bounded rebuild processed-evidence batches (candidate, not yet accepted)

- T12-A is a small, self-contained slice of T12: `VideoStatusRebuildServiceImpl` no longer issues
  one Redis `EXISTS` per RECEIVED candidate. Candidates are walked in fixed batches of 256
  (`PROCESSED_EVIDENCE_BATCH_SIZE`); each batch builds only its own `processed` keys and calls
  `RedisUtils.hasKeys`, one pipelined `EXISTS` round trip per batch. Per-key results are matched by
  position, so the accumulation order still equals snapshot order and duplicate eventIds are counted
  once per occurrence. No full-candidate key set is materialised.
- Only `process_status = 0` (RECEIVED) candidates need Redis evidence. `process_status = 1`
  (REDIS_APPLIED_PENDING_FLUSH) is confirmed without any Redis read. `hasKeys` rejects null results,
  length mismatches and unknown reply elements; the classification path additionally rejects null
  entries, so unknown evidence never degrades to "not applied". Failures surface as
  `RedisOperationException` before `initializeRedis`, i.e. no partial initialisation is published and
  the existing vid lock / no-TTL token gate / token-owned release protocol is untouched.
- Unchanged by T12-A: the snapshot MySQL transaction, rebuild gate TTL/ownership, the init Lua
  script arguments, and all Redis-data-loss guarantees. At the time of T12-A the snapshot still used
  a full `selectRebuildCandidates` load; T12-B below replaces it with keyset paging. Not implemented
  by T12-A: candidate paging/streaming SQL (now T12-B), budget abort (now T12-B), forced rebuild
  (`current` exists but is wrong) and any rebuild HTTP endpoint.
- Tests: `VideoStatusRebuildProcessedEvidenceTests` (0 candidates, status1-only, mixed statuses,
  600 candidates across the 256 boundary, duplicate keys across batches, Redis failure, length
  mismatch, null entry, unchanged gate/init protocol) and `RedisUtilsTests` pipeline cases (single
  pipeline, order, empty input, numeric replies, null/length/unknown failures). The opt-in
  `VideoStatusIsolatedIntegrationTests#rebuildCountsProcessedEvidenceAcrossBatchBoundary` proves the
  same path against real MySQL/Redis with 300 status0 candidates plus 40 status1 candidates whose
  `processed` keys were deleted. Candidate evidence: unit run `474 tests, 0 failures/errors, 142
  skipped`; the single isolated case passes. The 0/1,000/100,000-candidate performance acceptance
  (peak memory, RTT count, P95 rebuild/gate duration) is still open, as is forced repair.

### T12-B bounded rebuild candidate paging (candidate, not yet accepted)

- T12-B replaces the single unbounded `selectRebuildCandidates(vid)` load with a keyset page query
  `selectRebuildCandidatesAfter(vid, afterId, limit)` (`id > afterId`, no `OFFSET`, page size <=
  `VideoStatusProperties.MAX_REBUILD_CANDIDATE_PAGE_SIZE` = 1000). The whole loop still runs inside
  the existing `VideoStatusRebuildSnapshotServiceImpl#load` `@Transactional(readOnly = true,
  isolation = REPEATABLE_READ)` method, so the baseline row and every page read share one InnoDB
  consistent snapshot; paging is never moved across transactions.
- The SQL carries `FORCE INDEX (idx_video_status_consumed_vid_id)` on purpose. Without the hint the
  optimizer picks `idx_video_status_consumed_pending` (two `process_status` ranges) plus
  `Using filesort`, which re-reads and re-sorts every candidate of that vid on *each* page
  (pages x candidates). With `(vid, id)` the plan is an ordered range scan that reads only the
  matched candidates plus the non-candidate rows it skips, so total index work is bounded by that
  vid's event rows (linear), not by pages x candidates. The index is the T10 reconciliation index
  mirrored in `database/feibijiubi.sql`; if it is missing the query fails instead of silently
  degrading to a full scan. No new migration. This makes
  `database/migrations/t10-video-status-reconciliation-indexes.sql` a **hard deployment dependency
  for cold-cache rebuild**, not just a reconciliation speed-up: with the index missing MySQL raises
  1176, the rebuild fails before `initializeRedis` (no half `current`, the `finally` still releases
  the gate) and the consumer path retries it toward DLQ. Exceeding `rebuild-max-candidates` has the
  same retry-to-DLQ outcome. Neither is auto-repaired: no truncation, no silent limit raise, no
  forced-rebuild entry point.
- Candidates are still returned as the current DTO list, so the memory bound is
  `rebuild-max-candidates + 1` entries (default 100000), not a constant. Exceeding it throws
  `RetryableMessageException`; a truncated snapshot is never returned (truncation would under-count
  confirmed events and write a too-small Redis `current`). A page larger than the requested size, a
  null page, or a non-advancing cursor also fail instead of being accepted or looping forever.
  Every row's `id` must additionally be present and strictly increasing *inside* its page (a missing
  last-row id or an out-of-order id fails with `缺少 id 游标` / `id 未严格递增`); checking only the
  page tail cannot detect a duplicate or skipped row in the middle of a page.
- The read budget uses `System.nanoTime()` (`rebuild-snapshot-budget-ms`, default 10000) and is
  checked before each page fetch and before each row conversion; exceeding it throws
  `RetryableMessageException` with the converted-candidate count. It abandons the read only: it does
  not interrupt an SQL statement already executing and does not promise the SQL returns in budget.
- Unchanged: vid lock, no-TTL gate, token-owned release in `finally`, init Lua keys/args, the T12-A
  processed-evidence batching, counter aggregation and processed-state semantics. Both new failures
  happen before `initializeRedis`, so no half initialisation is published and the next attempt takes
  over with a fresh owner token. Not implemented: forced rebuild (`current` exists but is wrong),
  any rebuild HTTP endpoint, deadlock handling, and production capacity/performance acceptance. The
  0/1,000/100,000 figures below are single-JVM diagnostics from the opt-in scale class, **not**
  acceptance: three samples per scale, pipeline counts are logical calls rather than physical RTTs,
  heap is reported as an upper-bound approximation, and P95 is deliberately not reported.
- Tests: `VideoStatusRebuildSnapshotPagingTests` (16 cases: empty set, spanning pages, exact page
  multiple, max-at-limit vs over-limit, strictly advancing cursor, non-advancing cursor, oversized
  page, second page oversized, null page, last row missing id, non-increasing ids inside one page,
  budget exceeded between pages and during conversion, REPEATABLE_READ annotation, immutable
  candidate list), `VideoStatusRebuildProcessedEvidenceTests#snapshotReadFailurePublishesNoInitializationAndNextAttemptTakesOver`,
  and `VideoStatusRuntimePropertiesContextTests` binding/validation cases for the three new keys. The
  opt-in `VideoStatusIsolatedIntegrationTests#rebuildPagedCandidatesMatchBaselineInsideOneRepeatableReadSnapshot`
  seeds 1000 status1 candidates, hooks the real mapper to commit one extra candidate from another
  thread between page 1 and page 2, and proves both pages run on the same REPEATABLE_READ connection
  and the extra row is invisible; `#rebuildOverCandidateLimitPublishesNothingAndNextAttemptTakesOver`
  proves the over-limit failure publishes nothing, releases the gate, and the next attempt converges.
  Candidate evidence: unit run `497 tests, 0 failures/errors, 144 skipped` (`/tmp/t12b-unit-final.log`);
  full `VideoStatusIsolatedIntegrationTests` `20 tests, 0 failures/errors, 1 skipped`
  (`/tmp/t12b-it-class-final.log`). Parent re-run on the same candidate: `497 tests, 0
  failures/errors, 144 skipped` (`/tmp/improvement-parent-unit-final.log`) and opt-in
  `VideoStatusIsolatedIntegrationTests,VideoStatusStatus3ReplayIsolatedTests` `31 tests, 0
  failures/errors, 1 skipped` (`/tmp/improvement-parent-t12b-it.log`). After the row-level id guards
  and the scale class were added: `504 tests, 0 failures/errors, 147 skipped`
  (`/tmp/t12b-worker-unit.log`, the 3 extra skips are the opt-in scale cases) and
  `VideoStatusIsolatedIntegrationTests` `20 tests, 0 failures/errors, 1 skipped`
  (`/tmp/t12b-worker-it.log`). Test environment only, not production acceptance.
- Scale measurement: the opt-in `VideoStatusRebuildScaleIsolatedTests` (skipped unless
  `-DvideoStatusScale=true`, same `IsolationGuard`, isolated ports only, no listeners, new vid per
  run with the seeded event rows retained) runs 3 cold rebuilds each at 0/1,000/100,000 candidates
  through the real `ensureInitialized(vid)` and asserts a preset oracle every repetition
  (`current = 100 + candidates`, plus DB row count, distinct eventIds, `SUM(delta)`, the other seven
  counters at 0, gate released, no status change). Observed (`/tmp/t12-scale-full.log`, 52.98 s):
  0 -> 18/18/22 ms; 1,000 -> 164/190/379 ms with 4 pipeline calls / 1,000 EXISTS; 100,000 ->
  7,789/8,143/9,649 ms with 391 pipeline calls / 100,000 EXISTS; gate hold tracks rebuild time within
  ~20 ms. A second run under the constrained surefire heap (`-Xms64m -Xmx512m`,
  `/tmp/t12-scale-recovery.log`) reproduced the same order: 0 -> 17/19/24 ms, 1,000 ->
  179/184/405 ms, 100,000 -> 5,349/5,609/6,152 ms, 391 pipelines / 100,000 EXISTS. Pipeline calls are
  exactly `ceil(candidates/256)`. Open risk: default `rebuild-snapshot-budget-ms=10000` is the same
  order as the 100,000-candidate rebuild time, so a near-limit cold rebuild may hit the budget; it was
  never run at the default budget, so no failure is claimed - re-evaluate budget/limit per real scale
  and hardware before production.
- Historical, still unresolved: the two T10-B1 concurrency cases
  (`concurrentColdStartAndDuplicateDeliveryConverge`, `concurrentFlushAndConsumptionPreserveAllEvents`)
  once reported 2 errors. They passed in later isolated runs, but the root cause was never located or
  fixed, so this must not be recorded as resolved and the history stays. T12 remains unchecked:
  forced rebuild (admin authorization, audit trail, concurrency coordination, crash recovery,
  integrity verification) and production capacity acceptance are still missing.
- Recovery-contract coverage in `VideoStatusIsolatedIntegrationTests` (2026-10-23 candidate, **test
  scope only**: no production Java, SQL, schema or migration change). The test `TestApplication` now
  defines a real `VideoStatusDirtyRecoveryScheduler` bean (`@Scheduled` is inactive in that context, so
  tests call `recoverDirtyVids()` explicitly), and the class-wide `await(...)` helper reports a bounded,
  redacted diagnostic on timeout (`vid`, `baseline`, `current`, `process_status` 0/1/2/3 counts, dirty
  membership and dirty cardinality; never payload, eventId, `last_error` or credentials). The 30 s bound
  is unchanged everywhere. `concurrentFlushAndConsumptionPreserveAllEvents` keeps its 4 consumer threads
  + 1 flush thread but now finishes through real recovery **and** real flush instead of flush alone:
  `stats.apply` (Lua `SADD`) and `markRedisApplied` (`0→1`) are not atomic, so a flush that `SPOP`s the
  vid inside that window flushes nothing (`selectPendingForUpdate`/`countPendingByVid` read status 1
  only) and the scheduler never `SREM`s — recovery is the only re-wake path. That is the accepted
  "recovery remains required" contract, not a manual `SADD`.
- New deterministic case
  `dirtyMarkerLostBetweenApplyAndStatusCommitIsRecoveredOnlyByRealRecoveryScheduler` (fresh vid per
  test, no manual `SADD`/`SREM`, no deletion of the global dirty set / rebuild gate / `processed` keys):
  register → `stats.apply` (Redis +1, Lua `SADD`, still status 0) → real flush consumes the dirty marker
  without flushing the status 0 event → `markRedisApplied` (status 1) → asserts the DB pending event
  exists while dirty membership is gone → flush-only rounds keep the old baseline → the real recovery
  scheduler re-adds the vid within a computed bound
  (`ceil(distinct status-1 vids / flush-dirty-batch-size) + 2` rounds) → real flush → independent oracle
  (`baseline == current == 101`, event status 2, `SUM(delta)` over flushed rows == 1). Widening
  `countPendingByVid` to status 0 was rejected: it changes the flush protocol and would spin on
  not-yet-committed events. This is a **deterministic mechanism reproduction**, not evidence for the
  root cause of the two historical errors above — those logs contain no baseline/current/status
  snapshot, so the link between this interleaving and them stays a hypothesis. Both opt-in methods were
  **not re-run** in this pass (the isolated compose stack was stopped); only `test-compile` was run.

### T12-C1 controlled replace primitive (candidate, not opened)

- `src/main/resources/lua/video-status-replace.lua` plus `RedisScriptConfig#videoStatusReplaceScript`
  (`DefaultRedisScript<String>`) are the only new pieces: **no HTTP endpoint, no Java business caller,
  no automatic invocation** (`videoStatusReplaceScript` is referenced only by the config class).
  Forced rebuild still needs admin authorization, an audit trail/operationId, coordination with the
  vid lock / gate / batch flush, crash recovery and an integrity check; until those exist the
  primitive stays unopened and T12 stays unchecked.
- Same keys/args shape as `video-status-init.lua`, opposite precondition: init requires `current` to
  be absent, replace requires it to exist and be complete (`CURRENT_MISSING` on a cold miss, never an
  implicit init). Fixed check order: gate token (`STALE_REBUILD`) -> current type
  (`CURRENT_MISSING` / `INVALID_REDIS_TYPE`) -> dirty/hot type -> current TTL (`CURRENT_HAS_TTL`) ->
  vid -> hot score finiteness -> eight canonical counters `<= Long.MAX_VALUE`
  (`COUNTER_OUT_OF_RANGE`) -> `hasPending` -> stored field set (`UNEXPECTED_FIELDS`) -> stored values
  (`CURRENT_INVALID_VALUE`) -> stored vid equality (`VID_MISMATCH`). Every check runs before the first
  write, so every refusal is zero-write.
- Unknown fields are rejected without `HKEYS`: `HLEN` against the fixed limit 9 plus nine `HGET`s
  (constant 10 commands), so a large hash cannot produce an unbounded reply. Stored values must also
  be a legal `current` (non-zero vid, canonical counters within range); overwriting an already corrupt
  hash would launder the corruption into a plausible-looking statistic.
- Writes: one `HSET` of the nine fields, `SADD dirty` only when `hasPending=true` (`false` does **not**
  `SREM`, the dirty marker is kept conservatively), then `ZADD hot`. Each write is wrapped in `pcall`
  and returns a stage code (`REPLACE_HSET_FAILED` / `REPLACE_DIRTY_FAILED` / `REPLACE_HOT_FAILED`,
  success `REPLACED`). **No rollback promise**: serial Lua execution is not a transaction, a runtime
  error (noeviction OOM, ACL denial, instance failure) can leave earlier stages written, and the
  script never proves execution by reading values back.
- Not done by the script: no `DEL` of `current`, no change to `processed` keys or their TTL, no gate
  release (the caller still releases with the same owner token, an old token always gets
  `STALE_REBUILD`), no unproven mode and no relax switch.
- Tests: opt-in `VideoStatusReplaceLuaIsolatedTests` (24 cases, real Redis 16379, script executed
  through the bean) covers the normal nine-field + hot replacement, `pending=false` dirty retention
  without touching other vids, unchanged processed evidence/TTL, the full refusal matrix with
  before/after state equality, `Long.MAX` and `2^53+1` exactness, and a **real runtime partial
  failure** injected with ACL (a dedicated temporary user denying `HSET`/`SADD`/`ZADD`, deleted
  afterwards, default user untouched). Unit regression `528 tests, 0 failures/errors, 171 skipped`
  (`/tmp/t12c1-unit3.log`). Guide: `docs/tutorial/video-status-replace-primitive.md`.
- Parent re-verification (2026-10-23): `./mvnw -DvideoStatusIT=true -Dtest=VideoStatusReplaceLuaIsolatedTests test`
  (executed with the constrained-heap `argLine` `-Xms64m -Xmx512m -Djdk.attach.allowAttachSelf=true`) = 24
  tests, 0 failures/errors/skipped, log `/tmp/t12c1-parent-it.log`. This only re-confirms the narrow
  replace primitive; it is not T12 acceptance.
- Environment note: on this JDK 21 host Mockito's inline mock maker needs
  `-Djdk.attach.allowAttachSelf=true`; without it ByteBuddy's external attach process fails under
  commit-memory pressure and a whole run reports `Could not initialize plugin: interface
  org.mockito.plugins.MockMaker` (unrelated to the code under test).

### T13 read-path consistency: MySQL stays the user-facing source (mode B, candidate)

- Decision: mode **B** of the T13 plan. Every user-facing statistics read stays on MySQL `video_status`;
  Redis `current` participates in no user-visible response, so "Redis real-time read is not wired" is a
  decision, not unfinished work. Switching to mode A requires a new documented decision plus defined
  miss / corrupt-field / rebuild-gate / Redis-timeout read semantics, never a silent edit.
  Redis `current` nevertheless has **more than one reader**, so no single method may be called "the only
  Redis statistics read" and the un-called `getByVid` must not be described as a user read entry point:
  `VideoStatusServiceImpl#getByVid` is a read-only facade with **no caller** (on a cache miss it falls
  back to the DB row, on malformed/missing/negative/overflow fields it throws
  `VideoStatusCounterRangeException`, on a Redis failure it throws `RedisOperationException` (no DB
  fail-open, no fabricated 0), and it neither reads the rebuild gate, takes the vid lock, writes the cache
  back nor triggers a rebuild), while the admin single-video reconciliation path
  (`VideoStatusReconciliationServiceImpl#readRedisSample`, backing
  `GET /api/admin/video-status/reconciliation`) performs one read-only `multiGet` sample for diagnostics
  and never feeds a user-visible statistics response.
- Read surfaces verified in code: `GET /api/videos/{vid}` -> `VideoStatusMapper#selectByVid` (7 of the 8
  counters, no `unlikeTimes`; missing row = `code 500 视频统计数据异常`); `GET /api/videos/feed` ->
  `VideoMapper#selectFeed`, one `video ⋈ video_status` projection (only `playTimes`/`commentTimes`,
  published + public + non-deleted; a missing statistics row drops the whole video instead of showing 0,
  and no per-video status read happens); `userCount.loveCount` -> `VideoStatusMapper#countLikeByUid`
  = `COALESCE(SUM(like_times), 0)` over published + public + non-deleted videos (no `unlike_times`
  subtraction; empty set = 0); admin video detail -> `selectByVid` with all 8 counters passed through
  unchanged (including values above 2^53), no visibility filter, missing row = `code 404 视频状态为空`
  with no subsequent author/fans query. The per-interface divergences are documented in `docs/api.md`
  rather than silently unified. Another Java statistics read path for Redis `current` is the read-only reconciliation
  sample above; it is diagnostic and adds no user-visible statistics read.
- Delay: eventual consistency only, with **no healthy-path latency SLA**. The nominal order is ~1-2 s
  (`outbox-fixed-delay-ms=1000` + `flush-fixed-delay-ms=500` + processing); retry tiers (10/30/120 s) and
  backlog push it to minutes. The T05 15 s sampling interval, 120 s oldest-pending and 30 s heartbeat
  values are diagnostic thresholds, not guarantees.
- Hot ZSet `feed:hot:videos:v1` is **deferred on purpose**: three writers (`ZINCRBY` per applied event in
  `video-status-increment.lua`, `ZADD` in `video-status-init.lua` and `video-status-replace.lua`) and
  **zero readers**; no TTL, no `ZREM` on video delete, cumulative weighted score without time decay,
  memory O(number of vids) rather than O(events). User-visible benefit today is zero. Wiring it up or
  deleting it needs a separate change that first defines ordering/decay, deletion, historical rebuild and
  pagination.
- Tests: `VideoStatusReadConsistencyTests` (16 cases, pure Mockito) covers detail/feed/loveCount
  normal + missing row + `DataAccessResourceFailureException` propagation; admin detail 8-counter exact
  pass-through (`2^53+1` and `Long.MAX_VALUE`, private video still returned) and missing row = 404
  `视频状态为空` with zero author/fans interaction; a structural guard that
  `VideoServiceImpl`/`UserServiceImpl`/`VideoReviewImpl` declare no Redis/Redisson collaborator (so cache
  miss/corruption/gate/outage cannot reach these results); `getByVid` miss (DB fallback, no write-back,
  no lock, no rebuild), rebuild gate never consulted, corrupted field (explicit exception, no DB
  fail-open), Redis failure; plus VO field-surface and single-`<select>` mapper-XML assertions for the
  documented filters. The existing `VideoStatusCounterReadTests` (8 cases) still owns the cache-hit
  exactness/malformed matrix and was deliberately not duplicated. 2026-10-28 targeted serial run with the
  constrained heap (`MAVEN_OPTS=-Xms64m -Xmx256m`, `argLine=-Xms64m -Xmx512m
  -Djdk.attach.allowAttachSelf=true`, `videoStatusIT/Scale/Baseline=false`, `BackendApplicationTests`
  excluded): `VideoStatusReadConsistencyTests,VideoStatusCounterReadTests` = **24 tests, 0
  failures/errors, 0 skipped** (final run offline with `-o`; log `/tmp/t13-fix-target-unit2-A3BgFA.log`; the same round's first
  run `/tmp/t13-fix-target-unit-E1ZD6a.log` reported the same result); the earlier 14-case class and the
  542-test full unit regression were not re-run this round and their numbers are not restated as current.
  T13 is a **candidate awaiting parent re-verification**: no HTTP endpoint, no schema/migration and no
  statistics-protocol change.
- Explicitly not done: no real end-to-end DB visibility latency measurement (the ~1-2 s figure is derived
  from configuration, not measured), no production capacity work, no hot-list reader/removal, no
  `unlike_times` user-visible read, and the user-detail 500 vs admin-detail 404 wording stays as-is.
  T12 remains unchecked (forced rebuild, production capacity) and T10 remains in progress; recording this
  decision does not change either.

### T14-A read-only capacity audit + archive dry-run (candidate, T14 not done)

- T14-A is a **test-scope tool**, not production code: `src/test/java/.../service/video/capacity/`
  (`VideoStatusCapacityAudit`, `VideoStatusCapacityEstimator`, `VideoStatusCapacityReport`) plus
  `VideoStatusCapacityAuditIsolatedTests` (9 opt-in cases) and three pure unit classes
  (`VideoStatusCapacityEstimatorTests` 11 cases, `VideoStatusCapacityReportTests` 2 cases,
  `VideoStatusCapacityAuditScanTests` 10 cases). There is **no new HTTP endpoint, no production
  scheduler, no schema change and no migration**; the only way to run it is the opt-in isolated test,
  and `docs/api.md` records the absence of new routes.
- Everything is read-only: `information_schema.tables` estimates for `video_status_outbox` /
  `video_status_consumed_event` / `video_status_consumption_repair_log` / `video_status` (**never**
  `COUNT(*)`), a `FORCE INDEX` + time-window + `LIMIT` terminal dry-run, Redis `INFO memory` /
  `CONFIG GET maxmemory*` / `DBSIZE`, and a bounded `SCAN` + `MEMORY USAGE` sample of `processed` keys.
  Forbidden on purpose: `KEYS`, full scans, any DB write/DDL, any Redis write, `CONFIG SET`,
  `FLUSHALL/FLUSHDB`, deleting the rebuild gate or `processed` keys.
- SCAN boundedness is **command-count** bounded, never key-visit bounded: the sample loop issues one
  single-page SCAN per iteration (Lettuce `RedisKeyAsyncCommands#scan(ScanCursor, ScanArgs)` unwrapped
  from `LettuceConnection`, 5 s reply cap), increments a counter per round trip and stops at
  `videoStatusCapacityScanIterations`. `COUNT` (`videoStatusCapacityScanCount`) is only a hint, the
  `MEMORY USAGE` sampling count has its own hard cap (`videoStatusCapacitySampleKeys`), and the report
  records command count / returned keys / sampled keys separately. A non-zero cursor is
  `scanComplete=false` (coverage truncated, also listed under the report's uncertainties); an empty
  match page is never treated as completion. `Cursor#hasNext()` is deliberately gone because it keeps
  requesting SCAN on empty match pages until the whole keyspace is walked, which made the iteration
  budget unenforceable. Evidence: `VideoStatusCapacityAuditScanTests` pins the command counts for
  empty/non-zero-cursor pages, cursor completion, small match sets, a page larger than `COUNT`, the
  sampling cap, read failures and blank cursors; the opt-in
  `scanCommandBudgetIsEnforcedAgainstRealRedisAndMatchesCommandStats` asserts the real `cmdstat_scan`
  delta equals the recorded command count (3 = 3) for a no-match pattern.
- Deletion safety: only terminal rows (`outbox status=2 SENT`, `consumed process_status=2 FLUSHED`) enter
  the dry-run; `status0/1/3` and Outbox `FAILED` are never listed, and the `Window` record rejects any
  `terminalStatus != 2`. **Every terminal candidate is still `NOT_AUTHORIZED_FOR_DELETION`**: the
  retention/replay policy is approved (next bullet), but there is no archive executor, no delete code, no
  archive-success verification and no old-message hard-reject/dedup tombstone, so a `processed` TTL is not
  evidence that deleting DB idempotency rows is safe. The report also fixes `writePerformed=false` /
  `deletionAuthorized=false` and still prints `RETENTION_PERIOD_UNDECIDED` (tool code unchanged); that code
  now means "execution and proof are missing", not "policy undecided".
- **Retention and replay policy approved 2026-10-23 (decision only, nothing implemented)**: successful
  terminal rows (`outbox status=2 SENT`, `consumed process_status=2 FLUSHED`) stay online **90 days** and
  the archive copy keeps them a further **275 days**. DLQ items must be handled within **30 days** — a
  handling SLA, **not** a retention TTL: no automatic expiry and no auto-deletion of unresolved items.
  Operation audit rows are kept **365 days**. The maximum **normal** event replay window is **30 days after
  the event**; beyond it the case goes to manual review and normal replay is refused. Inside 30 days every
  existing guard still applies (age, `processed` TTL, evidence, T07/T09/T10 refusals): the window adds an
  upper bound and relaxes nothing. It does not cover T15 `video_interaction_request` /
  `video_interaction_window`: **their retention is now decided separately** (30 days, T15 cleanup slice) and
  the old "one `requestKey` is never counted twice" promise is replaced by "replayed while the row still
  exists".
- **Start points and archive storage confirmed by the user this round (decision only, no calendar date
  claimed, nothing implemented)**: the 90 online days start at **terminal completion**, i.e.
  `video_status_outbox.sent_at` (written by `markSent` after publisher confirm via
  `VideoStatusOutboxRelay#handleConfirmed`) or `video_status_consumed_event.flushed_at` (written by
  `markFlushed` inside the flush transaction) — **not** `created_at` / `consumed_at`. The 275 archive days
  start at **archive-copy verification success**, not at event creation and not automatically when the
  online window ends, so a delayed archive makes the real total longer than 365 days; do not describe the
  policy as a fixed 365 days from creation. Audit rows count 365 days from **audit row creation**
  (`video_status_consumption_repair_log.created_at`). The DLQ 30-day SLA starts at **first entry into the
  dead-letter queue**, a re-delivery does not reset it, and unresolved items are never auto-deleted. The
  archive copy lives in a **dedicated private COS bucket**; online rows may be deleted only after **file
  integrity and record-count verification** both pass; the development stage may use **isolated test
  storage only**.
- **Field gaps found by source/schema reading (no `application.yml`, no environment access)**: both
  terminal timestamps exist with the right meaning, but `sent_at` is written from the **application JVM
  clock** (`LocalDateTime.now()`), and neither `sent_at` nor `flushed_at` has an index (existing indexes
  are `(status, created_at, id)` / `(process_status, consumed_at, id)`). `flushed_at` is cleared by the
  T10-B1 replay (`resetRepairToReceived`), so it only proves the **latest** flush completion. `consumed_at`
  is the `insertReceived` `NOW(3)` registration time, not flush completion. Audit `created_at` /
  `completed_at` are both DB `NOW(3)` and satisfy the approved basis. **No DLQ first-entry timestamp exists
  at all** (durable `video.status.dlq.v1` queue, no consumer, no DB table, broker dead-letter metadata is
  never read or persisted), and **no archive verification record exists**. `FileStorageService` is
  same-bucket only (`copyObject`), exposes existence checks without size/hash, cannot write a manifest, and
  is therefore **not suitable** for archiving. Rows without a trustworthy terminal timestamp must **never**
  be guessed as `created_at` / `consumed_at` and must never be back-filled; they can only be excluded from
  automatic deletion.
- **Next minimal testable slices (proposals, pending parent review, not implemented)**: S1 = full entry
  coverage for the 30-day old-message rejection — one shared window predicate used by the consumer apply
  path, T07 outbox replay, T10-B1 status3 replay and T09 status0 compensation. **S1 status: the consumer
  registration backstop is implemented (T14-S1 below) and the other three entry points are wired to the
  same predicate by T14-S2 below; the relay and the automatic replay/recovery paths still add no
  rejection, so "every entry point rejects old messages" is still not claimed.** Note the consumer's only
  existing age guard lives on the duplicate/redelivery path (`handleReceived`), compares `consumed_at`
  (registration time, not event time) and therefore disappears once the ledger row is archived/deleted,
  while first-time registration/apply has no age guard at all — the new guard must not depend on the
  ledger row existing. **The relay must not add a rejection** (it would silently drop rows that replay just
  reset); the default `redisEventTtlDays=30` is
  the same order as the 30-day window, so it must land as "strictly less than the processed TTL" — a
  product/config decision for the parent. **That last requirement is superseded by T14-S1 below**: the
  30-day rule is a pure upper bound on event age that never reads `processed` evidence, so it has no
  ordering constraint against `redisEventTtlDays`. S2 = archive time fields plus verification records (the next
  round would add `(status, sent_at, id)` / `(process_status, flushed_at, id)` indexes and an archive
  record table; proof only, **no executor**). The T14-A dry-run tool is unchanged this round and still
  windows on `created_at` / `consumed_at`, so it is **not** an implementation of the approved basis.
- At the time of T14-A no archive executor, no delete code and no 30-day guard existed (the
  consumer-side guard arrived later in T14-S1 below, and the three admin entry points in T14-S2 below);
  production cleanup still requires
  implementing and accepting old-message hard reject or dedup tombstone, terminal-only selection, safe
  archive confirmation, rate-limited paged resumable execution, and a post-archive replay-no-double-count
  drill (including the 30-day guard itself). Guide: `docs/tutorial/video-status-capacity-audit.md`
  (§9.1.1 start points/storage, §9.2 field-gap table, §9.3 next slices).
- Missing index is `NOT_EVALUABLE`, never a fallback scan: the dry-run first verifies the exact column
  order of `idx_video_status_outbox_created (status, created_at, id)` /
  `idx_video_status_consumed_created (process_status, consumed_at, id)` in
  `information_schema.statistics`, then requires `EXPLAIN` `key` to be that index and `type != ALL`,
  and only then queries. Both indexes already ship in `database/feibijiubi.sql` (T05 migration), so this
  slice adds no migration; an environment missing them gets an explicit `INDEX_MISSING` report.
- Capacity semantics: measurable data (table estimates, Redis memory, sampled `processed` key bytes) is
  separated from **configuration assumptions** (`-DvideoStatusCapacityAssumedDailyEvents`,
  `-DvideoStatusCapacityAssumedRetentionDays`, both default 0 = not provided). Insufficient data is
  `UNKNOWN` with a reason and never 0: no assumed event volume, fewer than 5 sampled keys, no assumed
  retention days, or `maxmemory=0` (unlimited) all make the affected estimate `UNKNOWN`. The approved
  90/275-day policy is never substituted for these measurement inputs. The DB daily growth
  is a range (point estimate ×0.5～×1.5 because `information_schema` is an estimate); the Redis
  exhaustion estimate only covers `processed` growth, not `current`/`dirty`/`hot` or fragmentation.
- Report hygiene: no connection string, host, username/password, full eventId, payload or `last_error`;
  the isolated case seeds secret markers and asserts they never appear in the report text.
- Evidence (test environment, not production acceptance): normal regression
  `./mvnw '-Dtest=*Tests,!BackendApplicationTests' test` = **574 tests, 0 failures/errors, 180 skipped**
  (`MAVEN_OPTS='-Xms64m -Xmx256m'`, `argLine='-Xms64m -Xmx512m -Djdk.attach.allowAttachSelf=true'`);
  opt-in `./mvnw -DvideoStatusIT=true -Dtest=VideoStatusCapacityAuditIsolatedTests test` =
  **9 tests, 0 failures/errors/skipped** against MySQL 13307 + Redis 16379, producing a dry-run report
  under the system temp dir (the default-options run showed 1 SCAN command, 177 returned keys, 100/100
  sampled keys and a non-zero cursor, i.e. an honest truncated-coverage line instead of a full scan). Isolated sample only: `used_memory≈9.95 MB`, `DBSIZE=34765`,
  `maxmemory=0`/`noeviction`, sampled `processed` key 104–120 B, and the exhaustion estimate `UNKNOWN`
  because `maxmemory` is unlimited. Not a production capacity conclusion.
- Still missing for T14 beyond the confirmed start points: old-message hard reject or dedup tombstone
  (**the consumer registration backstop exists — T14-S1 below — and the T07/T10-B1/T09 admin entry points
  are wired to it by T14-S2 below; the relay and the automatic replay/recovery paths still add no
  rejection**), safe archive confirmation, batched/limited/resumable archive executor,
  interrupted-archive rerun drill, post-archive replay-no-double-count drill, and index/online-query lock
  impact validation. T14 stays unchecked, the dry-run still reports `NOT_AUTHORIZED_FOR_DELETION`, and
  nothing here is deployed or provisioned (no archive bucket, no new resource created).

### T14-S1 consumer-side 30-day absolute age rejection (candidate, T14 not done)

- Implemented slice: `VideoStatusConsumptionServiceImpl#register` calls the new single-writer guard
  `VideoStatusEventAgeGuard` **after** `event.validate()` + `validatePayloadHash(...)` and **before any
  mapper/serialization work**, so an event whose payload `occurredAt` is older than 30 days throws
  `NonRetryableMessageException` and reaches the existing consumer NonRetryable → dead-letter + ACK path.
  Boundary is explicit and mathematical: `occurredAt.isBefore(now.minusDays(30))` only, so **exactly 30
  days is still allowed** and nanos are not truncated. `MAX_NORMAL_REPLAY_AGE_DAYS = 30` is a fixed
  constant: no new property, no relax switch, no change to `VideoStatusProperties` (neither
  `redisEventTtlDays` nor the 7-day `consumer-recovery-auto-replay-max-age-seconds` validation), and no
  `application.yml` change. `LocalDateTime` stays zoneless; the component uses
  `Clock.systemDefaultZone()` with a second test-injectable `Clock` constructor (same pattern as
  `VideoStatusTelemetry`).
- Because the guard runs before the first mapper call, **existing status1/2 ledger rows are rejected too**:
  `handleDuplicate` is never reached, so an old event is no longer silently absorbed as
  `REDIS_ALREADY_APPLIED` / `ALREADY_FLUSHED`. No extra call was added to `handleReceived`; the old
  `consumed_at`-based 7-day replay guard is untouched and still runs for ages inside 30 days (its default
  7 days is *stricter*, and it is configurable up to just under the processed TTL, so "always 7 days" must
  not be assumed — above 30 days the new guard wins and no ledger row is read or modified).
- Future `occurredAt` values get **no new rejection** (current behaviour kept). This is **not** an
  anti-tampering mechanism: the payload timestamp is client/business-written and unsigned, so a forged
  recent timestamp passes. Missing `occurredAt` is still rejected by the existing `event.validate()`;
  payload/fingerprint semantics are unchanged. The rejection message is a fixed constant
  (`VideoStatusEventAgeGuard.EXPIRED_REASON`) containing neither payload nor eventId.
- Corrected earlier advice: the old proposal "the 30-day window must land as strictly less than the
  processed TTL" **does not hold**. The 30-day rule is a pure upper bound on event age — it never reads
  `processed` evidence, so it has no ordering requirement against `redisEventTtlDays`. Only the
  replay/compensation windows that *do* depend on `processed` (the 7-day auto-replay window, T09) keep
  their "strictly less than TTL" startup validation.
- Scope limits: this is a **consumer-side backstop only**. The T07 outbox replay, T10-B1 status3 replay
  and T09 status0 compensation entry points are wired to the same predicate by T14-S2 below; the relay
  and the automatic replay/recovery paths still do not call it, so "every entry point rejects a 30-day-old
  event" is **not** claimed. The rejection performs no MySQL/Redis write in `register` (the `REQUIRES_NEW`
  transaction rolls back empty), but the flow is **not** write-free end to end: the consumer forwards the
  message to the DLQ (broker write) and telemetry counts a failure. An old consumer that does not have
  this guard still applies the event, so archive safety requires **all consumers upgraded** — after
  mixing versions, deleting archived terminal rows or rolling back to an old consumer would re-admit old
  events. The guard alone is **not** an archive authorization: the dry-run still reports
  `NOT_AUTHORIZED_FOR_DELETION`.
- Tests: `VideoStatusEventAgeGuardTests` (9 pure cases: fixed clock, inside window, exactly 30 days,
  30 days ±1 nano, +1 second, future timestamp, default-zone wall-clock equivalence),
  `VideoStatusConsumptionRegistrationTests` (7 cases: stale event rejected with zero mapper interaction,
  stale event with an existing status1 row neither read nor changed, stale event with a FLUSHED row not
  silently absorbed, exactly-30-days and inside-window registration unchanged, the existing 7-day
  `markRepairRequired` path unchanged inside 30 days, and a missing `occurredAt` rejected by the existing
  `event.validate()` with zero mapper interaction) and one new
  `VideoStatusEventConsumerAckBoundaryTests` case proving the new exception is forwarded to DLQ and then
  ACKed exactly once (not a silent ACK, no retry forward, no `recordConsumerFailure`). Evidence: targeted
  run `50 tests, 0 failures/errors` (`/tmp/t14-s1-target-unit2.log`); normal regression
  `./mvnw '-Dtest=*Tests,!BackendApplicationTests' test` = **684 tests, 0 failures/errors, 201 skipped**
  (`/tmp/t14-s1-unit-full.log`, `MAVEN_OPTS='-Xms64m -Xmx256m'`,
  `argLine='-Xms64m -Xmx512m -Djdk.attach.allowAttachSelf=true'`). No schema/migration/COS/delete/new
  HTTP route; no isolated stack was started or drilled in this pass. Candidate, not production
  acceptance; T14 stays unchecked.

### T14-S2 the same fixed 30-day bound on the three admin entry points (candidate, T14 not done)

- Naming: **T14-S2** is the parent-assigned slice id. It is **not** the "S2 = archive time fields plus
  verification records" proposal in the T14 section above; that archive slice is still unimplemented.
- Scope: wiring the existing fixed bound into existing entry points. **No schema/migration/COS/delete, no
  new property, no new HTTP route.** Four call sites share
  `VideoStatusEventAgeGuard#isOutsideNormalReplayWindow`: consumer registration (T14-S1, unchanged),
  T07 `VideoStatusOutboxReplayService#replay` (after the createdAt/occurredAt double window and **before**
  the consumed-ledger query and `resetFailedToPending`), T10-B1
  `VideoStatusStatus3ReplayService#evaluate` (after the occurredAt window and before the budget **check**,
  `processed` evidence and any write; `precheck` and `replay` share it — the bounded
  `countReplayAttempts` query is a call argument and therefore already ran before `evaluate`, only the
  comparison sits after the guard), and T09
  `VideoStatusStatus0CompensationServiceImpl#evaluate` (`preview` and `compensateOne` share it).
- Refusal code: `VideoStatusEventAgeGuard.REFUSAL_CODE = EVENT_OVER_NORMAL_REPLAY_WINDOW`, the same string
  on the read and write sides (T10 `replay.refusalCode`, T09 `preview.items[].refusalCode`, and the
  rejection message / T09 release reason). It deliberately does **not** reuse
  `CONSUMED_OVER_SAFE_WINDOW` / `STATUS0_OVER_SAFE_WINDOW`: those describe the **configurable** window
  (startup validation only requires it to be smaller than `redisEventTtlDays`, so it can legally be set
  above 30 days), while this is the **fixed policy bound**; mixing them would hide which rule refused and
  make the old messages untrue.
- Priority is unchanged: the new check runs **after** each entry point's existing configurable-window
  check, so with the default 7-day window an over-age event still gets the original reason
  (`CONSUMED_OVER_SAFE_WINDOW` / `STATUS0_OVER_SAFE_WINDOW` / the T07 occurredAt-window message). The new
  code only fires when an operator raises `consumer-recovery-auto-replay-max-age-seconds` above 30 days.
  Exactly 30 days is still allowed by the shared predicate, while the stricter 7-day / TTL / age /
  future-300 s / fingerprint conditions can still refuse.
- Clocks: each service keeps its own existing time source (all write paths use `LocalDateTime.now()`);
  T09 preview takes one JVM `now` per round and shares it with `runOnce` through the same `evaluate`.
  Lease/cooldown/grace comparisons still use the SQL `NOW(3)`, and no `evaluate` reads the clock twice.
  Entry-point tests only use clearly-inside (20/29 days) and clearly-outside (31 days) values; the exact
  nanosecond boundary stays with the fixed-clock `VideoStatusEventAgeGuardTests`.
- T09 write boundary (not overstated): the bound is evaluated before the Redis evidence check and before
  publish, but the refusal path is **not** write-free — it releases the lease and writes the cooldown /
  diagnostic `last_error` per the existing protocol. It does **not** publish MQ, change business counters,
  or auto-quarantine a legal event as status3.
- T09 read side (a consequence of sharing one `evaluate`): `preview.items[]` gains a read-only
  `refusalCode`; `withinSafeWindow` now means "DB age **and** `occurredAt` window" (previously DB age
  only), so `eligible` can no longer be true for a row the write path refuses on age/content grounds;
  `selectStatus0CompensationPreview` also selects `payload` to decode `occurredAt` (still read-only and
  bounded). `preview` still never touches Redis, so `eligible=true` still does not guarantee a send.
  When the payload cannot be parsed, fails `validate()`, or disagrees with the ledger row, `evaluate`
  returns `withinSafeWindow=false` (no `occurredAt` can be proven) with one of the three fixed content
  codes `STATUS0_PAYLOAD_INVALID` / `STATUS0_EVENT_INVALID` / `STATUS0_EVENT_MISMATCH`; the parse branch
  never echoes the raw exception message and falls back to `STATUS0_EVENT_INVALID` for anything else.
  Fingerprint mismatch is **not** part of preview: `runOnce` checks it separately (before the Redis
  evidence read), so `eligible=true` can still be refused by the write path as
  `STATUS0_FINGERPRINT_MISMATCH`.
- Unchanged: the fixed 30-day constant and "exactly 30 days allowed", `VideoStatusProperties`, the
  configurable window and its processed-TTL cross-check, the relay (no new rejection), and the absence of
  schema/migration/HTTP changes.
- Tests (pure Mockito, serial, `-DvideoStatusIT=false -DvideoStatusScale=false`,
  `MAVEN_OPTS='-Xms64m -Xmx256m'`, `argLine='-Xms64m -Xmx512m -Djdk.attach.allowAttachSelf=true'`):
  `VideoStatusOutboxReplayBoundaryTests` 8→**11** (45-day window rejects a 31-day `occurredAt` with zero
  writes, 29 days still replays under that config, default 7-day window still returns the original
  occurredAt reason), `VideoStatusStatus3ReplayServiceTests` 26→**28** (precheck and replay return the
  same code, zero writes, no Redis read; 29 days still replayable under the widened config),
  `VideoStatusStatus0CompensationTests` 31→**35** (31 days released with the same code, preview and
  runOnce report the same code, no publish, no `processed` read; 20 days still republished under the
  widened config; default 7-day window still `STATUS0_OVER_SAFE_WINDOW`), and
  `VideoStatusConsumptionRegistrationTests` 6→**7**. Targeted run **96 tests, 0 failures/errors/skipped**
  (`/tmp/t14-s2-target-unit2.log`), related-class regression **48 tests, 0 failures/errors**
  (`/tmp/t14-s2-related-unit.log`). No isolated stack was started; no IT/scale/container/DB/Redis/COS work.
  Candidate, test-environment evidence only, not production acceptance; T14 stays unchecked.
- Review-fix round on the same candidate (2026-10-24): the T09 parse-failure branch was corrected to
  `withinSafeWindow=false` with a whitelisted fixed content code (previously it returned the DB-age value
  and passed `e.getMessage()` through). `VideoStatusStatus0CompensationTests` 35→**39**: malformed
  payload, `validate()` failure and ledger/payload mismatch each pin read side `withinSafeWindow=false` /
  `eligible=false` / the exact code, and write side `release` with that same code, no publish and no
  Redis read; plus a fingerprint-mismatch case (release `STATUS0_FINGERPRINT_MISMATCH`, no publish, no
  Redis read) and positive `withinSafeWindow=true` / `eligible=true` / `refusalCode=null` assertions on a
  genuine eligible preview fixture. Targeted run **94 tests, 0 failures/errors/skipped**
  (`/tmp/t14-s2-review-fix.log`). No IT/scale, no container/DB/Redis/COS, no schema/migration/route;
  still candidate, not production acceptance.
- Still not done / not claimed: no archive or delete capability (dry-run still
  `NOT_AUTHORIZED_FOR_DELETION`); an un-upgraded old consumer still applies old events (archive safety
  needs an all-consumer same-version deployment); the relay and the automatic replay/recovery paths add
  no rejection; future timestamps are still accepted, so this is not an anti-tampering mechanism; and a
  legal event that is first delivered more than 30 days after it occurred still goes straight to the DLQ
  with no ledger row and no automatic back-counting (the relay's confirm state is independent of
  consumption, so it must not be asserted to always be `SENT`). Guide:
  `docs/tutorial/video-status-capacity-audit.md` §9.3.2.

### T15 request idempotency + rolling 30-minute window (phase 2 wired, candidate, NOT accepted)

- **Phase 2 is wired; the phase-1 "no HTTP endpoint / no caller" statement is history.**
  `POST /api/videos/{vid}/play-count` and `POST /api/videos/{vid}/share` (`UserVideoController`) take
  `Idempotency-Key` + `X-Visitor-Id`, keep `@OptionalLogin`, read the uid from the `currentUserId`
  request attribute and return `ApiResponse<VideoInteractionVO>`. Both headers are declared
  `required = false` on purpose: the service layer answers an explicit 400, because a
  Spring-required header raises `MissingRequestHeaderException`, which `GlobalExceptionHandler` does
  not map and would surface as 500.
- **The old `UserVideoService#increasePlayCount(Integer)` / `#increaseShare(Integer)` signatures are
  gone, with no overload kept** (an overload would be a counting path that bypasses admission):
  `increasePlayCount(Integer currentUserId, Integer vid, String requestKey, String visitorId)` and
  `increaseShare(...)` only delegate to `VideoInteractionAdmissionService#record` and carry no
  `@Transactional` (`record` asserts it is not called inside a transaction).
- Fixed admission order (`VideoInteractionAdmissionServiceImpl`, documented on its interface): validate
  parameters (`requestKey` / `vid` / action, then identity) →
  `PublishedVideoGuard#requirePublishedPlayable` (400/404/500, the single rule that
  like/coin/collect/progress also use) → **read-only MySQL replay lookup** (a hit returns the stored
  result and touches **no** Redis, so a retry spends no quota and a previously suppressed outcome is
  not re-decided) → **Redis admission** (new requests only) → `record`. Two concurrent requests with
  the same `requestKey` can both pass the replay lookup and spend one quota each; `record`'s unique
  key still counts once, so the only cost is an earlier 429.
- Identity (`VideoInteractionActorResolver`): login wins (`USER`, SHA-256 over
  `video-interaction:actor:v1:user:<uid>`; an invalid `X-Visitor-Id` is ignored entirely for a
  logged-in caller). Anonymous callers **must** send a canonical `8-4-4-4-12` hex UUID (lower-cased,
  hashed under `...:anonymous:`); missing/invalid → **400**. No IP, User-Agent or fingerprint is
  collected. This is pseudonymisation, not anonymisation (the uid space is enumerable), and a client
  can mint a fresh UUID at will, so "once per 30 minutes" binds cooperating clients only — a low-cost
  bypass that the docs must state instead of claiming anti-farming is solved.
- Redis admission (`VideoInteractionRateLimitServiceImpl`) reuses
  `lua/fixed-window-rate-limit.lua` in two per-actor dimensions (per action, and all actions
  combined). `VideoInteractionProperties` defaults, startup-validated and with no `application.yml`
  entry: `window-seconds=60`, `max-requests-per-action=60`, `max-requests-per-actor=120`, and
  `perActor >= perAction` is required. Over limit → 429 with the single message
  `操作过于频繁，请稍后再试` (never which dimension, never the remaining quota). Script
  `null`/negative/exception → `RedisOperationException` → **503**, fail-closed. The two dimensions are
  not atomic, and **quota is consumed before the DB transaction, so a DB failure does not refund it**
  (deliberate "rather reject a few" trade-off).
- Response contract: `VideoInteractionVO(counted, duplicate, reason)` only, `reason` =
  `COUNTED` / `WINDOW_SUPPRESSED`; no `requestId` / `eventId` / `actorHash` / window time / remaining
  quota. `duplicate=true` means the call replayed an existing `requestKey`; `counted` describes the
  original request. HTTP status equals the body `code` (400/404/409/429/500/503) because
  `GlobalExceptionHandler` derives the status from `BusinessException.code`, so the `docs/api.md`
  §1.4 note "business errors usually stay HTTP 200" does not hold for these two endpoints.
- Approved decisions now recorded: (1) `Idempotency-Key` is mandatory on both endpoints and
  `X-Visitor-Id` is mandatory for anonymous callers; missing/invalid is 400 and **old clients are
  deliberately unsupported** — no compatibility window is planned, the front end ships together;
  (2) the video check runs **before** the replay lookup, so once a video is unpublished or deleted a
  retry with the same `requestKey` returns **404**, not the historical result (replay is not a promise
  that survives video-state changes); (3) a client-random UUID was approved as the anonymous identity
  and no new identity mechanism is added; (4) the 30-minute window stays non-configurable.
- Approved product semantics: one count per `(actor, vid, action)` inside a rolling **30 minutes**
  window; while its request row still exists (30-day retention from `completed_at`, see the T15 cleanup
  bullet below) one `requestKey` replays the original outcome instead of being counted again;
  cleanup-active deletion makes the same `requestKey` a **new** request that is still bound by the
  30-minute window; a suppressed request does **not** advance the window; anonymous actors use
  a random UUID, **not** a device/browser fingerprint; the DB only stores `actor_type` +
  canonical lowercase 64-hex `actor_hash`.
- Tables (migration `database/migrations/t15-video-interaction-idempotency.sql`, idempotent
  `CREATE TABLE IF NOT EXISTS`, only adds tables, never alters/updates/deletes existing rows; mirrored
  in `database/feibijiubi.sql`): `video_interaction_request` (`request_key` is globally unique and
  **case-sensitive** through a column-level `utf8mb4_bin` collation, `state`
  `0 PENDING / 1 COUNTED / 2 SUPPRESSED`, `event_id` only on the async path, `created_at`,
  `completed_at`) and `video_interaction_window` (unique on
  `actor_type + actor_hash + vid + action`, `last_counted_at` **nullable = never counted**).
- Flow (`VideoInteractionService#record`): idempotent read outside any transaction (hit → replay,
  shape mismatch → 409) → one transaction: INSERT the PENDING placeholder → window `ON DUPLICATE KEY
  UPDATE last_counted_at = last_counted_at` (guarantee the row only; **never** initialise the time —
  initialising it with `NOW(3)` would suppress the very first request) → `SELECT ... FOR UPDATE` →
  guarded update `SET last_counted_at = NOW(3) WHERE id=? AND (last_counted_at IS NULL OR
  last_counted_at <= TIMESTAMPADD(MINUTE,-30,NOW(3)))` (1 row = granted, 0 rows = suppressed **with
  zero writes**) → count only when granted (async reuses the existing `createEvent`, which is
  `Propagation.MANDATORY` and returns the `eventId` stored on the request row; sync reuses the
  existing `increasePlayTimes`/`increaseShareTimes` guarded updates and fails with 500 when the
  affected-row count is not 1) → terminal state CAS on the request row. All four steps commit
  together; any failure rolls the whole thing back.
- Concurrency on the same `requestKey`: the unique-key `DuplicateKeyException` must escape the
  `TransactionTemplate` callback so the attempt transaction rolls back completely; the winner is then
  re-read **outside** the transaction and replayed. Catching it inside the callback and continuing is
  forbidden (rollback-only). `record()` asserts
  `TransactionSynchronizationManager.isActualTransactionActive()` is false and throws
  `IllegalStateException` otherwise; propagation is plain `REQUIRED` on the assumption of no
  enclosing transaction.
- Validation: `requestKey` `[A-Za-z0-9._:-]{1,64}` (case-sensitive), `actorHash` exactly 64 lowercase
  hex, `vid > 0`, `action` in `PLAY`/`SHARE`; rules live in `VideoInteractionValidation` so admission
  can reject before any Redis count. The DB still cannot verify how the hash was produced.
- Tests (phase 1): `VideoInteractionServiceTests` (32 pure Mockito cases) plus two opt-in isolated classes —
  `VideoInteractionIsolatedTests` (7 cases, async, MySQL 13307 + Redis 16379: first request creates
  exactly one outbox event, `-1799s` suppressed with an unchanged window, `-1801s` counted again and
  advanced, a suppressed old `requestKey` still replays SUPPRESSED after the window expires,
  concurrent same `requestKey` counts once, concurrent different keys in one window count once, and a
  real outbox-insert failure via a temporary `t15_fail_outbox` trigger rolls back request + window +
  event) and `VideoInteractionSyncIsolatedTests` (7 cases, sync only, MySQL 13307: guarded update
  counting with `event_id` NULL and no outbox row, window suppression with zero writes, a
  `Long.MAX_VALUE` counter failure rolling back request + window for real, `requestKey` reuse on a
  different vid → 409 without an extra count, replay not counting again, and `requestKey`
  case-sensitivity — two keys differing only in case are two independent requests).
- Tests (phase 2, evidence from existing logs, not rerun by the docs pass): `VideoInteractionHttpMappingTests`
  7 (header/`@OptionalLogin`/VO mapping, old signatures gone), `VideoInteractionActorResolverTests` 7,
  `VideoInteractionAdmissionServiceTests` 17 (order: no quota spent on rejected input, replay before
  Redis), `VideoInteractionRateLimitServiceTests` 9 (both dimensions, exact limit, null/negative/Redis
  failure fail-closed); normal regression
  `./mvnw '-Dtest=*Tests,!BackendApplicationTests' test` = **666 tests, 0 failures/errors, 200
  skipped** (`/tmp/t15b-unit-full.log`, line 14129; the three opt-in T15 isolated classes are skipped
  in that mode) and the targeted run = **72 tests, 0 failures/errors** (`/tmp/t15b-target-unit3.log`).
  Opt-in isolated: `VideoInteractionAdmissionIsolatedTests` **6 tests, 0 failures/errors**
  (`/tmp/t15b-admission-it4.log`: first request counts then the same visitor is window-suppressed;
  over-limit 429 with **zero** request rows; a committed replay still answers after the quota is
  exhausted and the window expired; the actor-total dimension rejects even when each action is under
  its own limit; anonymous and logged-in are separate actor spaces and login ignores the visitor
  header; rejected input creates no admission key). Phase-1 DB regression re-run:
  `VideoInteractionIsolatedTests` + `VideoInteractionSyncIsolatedTests` + `VideoStatusCounterTransactionTests`
  = **20 tests, 0 failures/errors** (`/tmp/t15b-regression-it1.log`). The maintenance drill
  (`VideoStatusIsolatedIntegrationTests#controlledSyncAsyncSyncMaintenanceDrillConvergesWithoutLossOrStaleCache`,
  1 test, `/tmp/t15b-drill-maintenance.log`, printed `T04 maintenance drill vid=7134 asyncEvents=4
  syncWrites=2 baseline=6 current=6`) now goes through the new entry via `countOnePlay()` (fresh random
  visitor + fresh random `requestKey` per call, asserting `counted && !duplicate`).
- **The full isolated class is not green and must not be recorded as green.** The last complete run
  (`/tmp/t15b-drill-it2.log`, line 300-318) is `VideoStatusIsolatedIntegrationTests` **20 tests, 1
  failure, 1 skipped, BUILD FAILURE**: `concurrentFlushAndConsumptionPreserveAllEvents` — "condition did
  not converge in 30 seconds" — which is the pre-existing, still-unlocated historical flake
  (`applyAndMark`-based, does not touch the T15 entry). Later single-method re-runs passed
  (`/tmp/t15b-drill-single.log`, `/tmp/t15b-drill-maintenance.log`), but the root cause was never found
  or fixed, so the history stands and no "whole class green" claim exists.
- Not done: production deadlock retry, capacity/load testing, a server-issued or
  signed visitor identity (or any stronger anti-farming), a compatibility window for old clients, and
  any production anti-abuse acceptance. Retention/cleanup is implemented by the T15 cleanup slice
  below (default off, not a TTL); it still does **not** use the approved T14 90/275-day policy, which
  covers `video_status_outbox` / `video_status_consumed_event` only. Recovery follow-up: a unique-key
  collision without a readable winner now returns 500, not a misleading request-conflict 409;
  rollback is unchanged and logs omit exception text/request keys.
  `VideoInteractionServiceTests`: 33 tests, zero failures/errors/skips
  (`/tmp/t15-resume-service-unit.log`); this targeted unit rerun is not full-suite or IT acceptance.
  A stale `{@link UserVideoService#increasePlayCount(Integer)}` javadoc reference remains in
  `VideoStatusIsolatedIntegrationTests`. T15 stays candidate / incomplete / not live and is **not**
  checked off. Guide: `docs/tutorial/video-interaction-idempotency.md`.

### T15 30-day bounded cleanup (candidate, default off, not accepted)

- Approved keep windows are **fixed constants, not properties**: `video_interaction_request` terminal
  rows 30 days from `completed_at`; `video_interaction_window` 30 days with no count measured from
  `last_counted_at`. Making them configurable would allow `0` days, which deletes live window rows and
  silently defeats the 30-minute window. There is deliberately **no** `retention-days` knob.
- Current semantics replace the old "never counted twice" promise: while the request row still exists a
  repeat replays the original outcome; after cleanup deletes it, the same `requestKey` is a new request
  that is still bound by the 30-minute window. Cleanup is **default off** and a batched maintenance
  task, **not** a "deleted exactly at 30 days" TTL — a 31-day-old row keeps replaying until a round
  reads it.
- Knobs (`app.video-interaction.*`, no `application.yml` entry, defaults are effective):
  `cleanup-enabled=false`, `cleanup-batch-size=500` (validated `1..5000`), `cleanup-fixed-delay-ms=60000`
  (validated positive). Two **distinct** beans: the `@Component` scheduling entry point keeps its default
  name `videoInteractionCleanupScheduler`, and the dedicated single-thread `ThreadPoolTaskScheduler` is
  `videoInteractionCleanupTaskScheduler` (thread prefix `video-interaction-cleanup-`, 5 s bounded
  shutdown), referenced by `@Scheduled(scheduler = ...)`. They must not share a name: with Boot's default
  `allow-bean-definition-overriding=false` a collision is a startup `BeanDefinitionOverrideException`
  (reproduced before the fix and pinned by
  `VideoInteractionCleanupSchedulingWiringTests`, a DB-free real Spring context test). This keeps cleanup
  off the T06 relay/flush/recovery/compensation threads.
- `VideoInteractionCleanupServiceImpl#cleanupOnce` runs three batches per round (`state=1`, `state=2`,
  one idle-window batch), each bounded by `cleanup-batch-size`. Two-phase shape: **non-locking** id
  selection, then **primary-key equality delete that repeats the time/state guard**. No explicit
  transaction (each mapper call commits on its own); the service refuses to run inside an existing
  transaction. `WHERE 1 = 0` fail-closed for an empty id collection; candidate lists larger than the
  batch size, `null` or non-positive ids fail closed before any delete. `timeout=3` is a statement
  timeout intent, **not** a hard 3-second guarantee. The two-phase shape is design intent only: the
  optimizer's actual access path and the lock footprint (gap/next-key locks, lock wait time) are **not**
  guaranteed and must be measured on real MySQL; no such measurement was run, and the isolated class
  that would exercise it is compiled but not executed.
- Never deleted: committed PENDING rows, terminal rows with `completed_at IS NULL`, and window rows with
  `last_counted_at IS NULL` (deleting them would reset the window and make the next request count).
  No HTTP route, no distributed lock, no cursor/watermark: DELETE is idempotent and crash/multi-instance
  overlap only costs a round.
- Clock boundary (do not overstate it): thresholds are computed by the DB
  (`TIMESTAMPADD(DAY, -30, NOW(3))`), so **no new JVM clock threshold** is introduced — an application-clock
  skew cannot become a deletion threshold. That is **not** a claim that the DB clock, the session
  `time_zone` or DST carry no risk: `NOW(3)` is session-time-zone wall time, and the threshold and the
  stored `completed_at`/`last_counted_at` values are self-consistent only while that zone configuration
  stays put. No timezone/clock-switch test was run.
- Migration `database/migrations/t15-video-interaction-cleanup-indexes.sql` is a **hard prerequisite**
  for enabling cleanup: it adds `(state, completed_at, id)` and `(last_counted_at, id)` (mirrored in
  `database/feibijiubi.sql`). Both candidate queries use `FORCE INDEX`, so a missing index raises 1176
  and the round fails visibly instead of degrading to a full scan. `state` must be queried as a single
  value; `state IN (1,2)` would force a filesort and defeat `LIMIT` early termination.
- Residual risks (documented, not fixed): with an Outbox backlog beyond 30 days (itself an incident) a
  retry with the same `requestKey` can create a second `eventId`; whether the old event is then counted
  again depends on the consumer 30-day age guard (T14-S1) and the existing replay/compensation windows,
  so no "necessarily double counted" claim is made. Committed `NULL last_counted_at` rows are never
  cleaned (unreachable in the current code path, so a theoretical leak, not a cleanup target).
- Tests (this round, executed): `VideoInteractionCleanupServiceTests` 19, `VideoInteractionCleanupContractTests`
  9 (real MyBatis parsing that renders the empty-id `WHERE 1 = 0` branch and the non-empty `id IN` + guard
  branch, plus a source-text contract that forbids reverting the isolated `@AfterEach` to a global
  `cleanupOnce()` drain), `VideoInteractionCleanupRetentionTests` 2,
  `VideoInteractionCleanupSchedulingWiringTests` 3 (DB-free real Spring context with bean overriding
  disabled: the two bean names/types are distinct and unique, the `@Scheduled` reference resolves, and a
  missing task-scheduler bean fails refresh) = **33 tests, 0 failures/errors/skipped**
  (`/tmp/t15-cleanup-target2-unit.log`; wiring alone `/tmp/t15-cleanup-wiring-unit.log`); normal regression
  **744 tests, 0 failures/errors, 214 skipped** (`/tmp/t15-cleanup-wiring-full.log`). Reverting the
  thread-pool bean name to the component name makes the wiring test fail with
  `BeanDefinitionOverrideException` (mutation-checked, then restored). The opt-in
  `VideoInteractionCleanupIsolatedTests` (real MySQL 13307) is **compiled but not run**: it covers the
  expired/fresh/NULL/PENDING matrix, window recreation after deletion, replay-vs-new-request, the
  select→advance→delete-zero-rows guard, a real row-lock interleaving, bounded batches with
  convergence, rerun idempotency, `EXPLAIN` index usage, and no statistics/ledger collateral damage.
  Because production cleanup scans globally (no vid scope), the class fails fast instead of touching
  unknown expired rows that it did not create; it never truncates tables. Its `@AfterEach` teardown is
  **id-set scoped** (`deleteExpiredRequestsByIds`/`deleteIdleWindowsByIds` over the tracked own ids,
  same time/state guards) and never calls the global `cleanupOnce()`, so a teardown that runs after a
  failing foreign guard cannot delete anyone else's rows; a source-text contract test pins that shape.
  This slice is test-environment evidence only, not production acceptance, and T15/T16 stay unchecked.

Guides: `docs/tutorial/video-status-counter-range.md`, `video-status-flush-defer.md`,
`video-status-runtime-roles.md`, `video-status-observability.md`,
`video-status-scheduling-isolation.md`, `video-status-outbox-reliability.md`,
`video-status-outbox-lease-migration.md`, `video-status-consumer-retry-ack.md`, `video-status-rebuild-paging.md`,
`video-status-replace-primitive.md`, `video-status-read-consistency.md`,
`video-status-capacity-audit.md`, and `video-interaction-idempotency.md` (all under `docs/tutorial/`). Acceptance progress and
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
environment only; the unproven replay mode remains controlled and default off. T10 is still in
progress: its first phase (failure diagnostics/acknowledge and read-only reconciliation) is merged
on main at `6fc5e7f`, and T10-B1 (consumed status3 single-event proven-only replay) is a candidate
awaiting parent acceptance — it adds `VideoStatusStatus3ReplayServiceTests` and the opt-in
`VideoStatusStatus3ReplayIsolatedTests`. DLQ handling, flush-origin status3 repair, batch actions and
automatic repair remain unimplemented.

T12 scale measurement is `VideoStatusRebuildScaleIsolatedTests`; it is skipped unless
`-DvideoStatusScale=true` is passed explicitly, and it is never part of the `-DvideoStatusIT=true`
class filter. Run `./mvnw -DvideoStatusScale=true -Dtest=VideoStatusRebuildScaleIsolatedTests test`
after `docker compose -f compose.test.yaml up -d --wait`. It seeds a new vid each run and leaves its
event rows in place (it only deletes that vid's own Redis keys).

The dirty-recovery contract is covered inside `VideoStatusIsolatedIntegrationTests` (opt-in) by
`concurrentFlushAndConsumptionPreserveAllEvents` (real recovery + real flush in the final wait) and
`dirtyMarkerLostBetweenApplyAndStatusCommitIsRecoveredOnlyByRealRecoveryScheduler`; both use the real
`VideoStatusDirtyRecoveryScheduler` bean, never a manual dirty `SADD`.
Parent recovery verification: both methods passed together (2 tests, zero failures/errors/skips),
log `/tmp/t18-recovery-contract-parent-it.log`. The deterministic case reached baseline/current 101
with one setup flush and one recovery round. Existing isolated containers were started with operator
approval; no migration or volume recreation. This verifies the recovery mechanism, not the unproven
cause of historical failures, a full-suite pass, or production acceptance.
## T16-B0 test-environment synchronous admission baseline

- The user limited T16 to test-environment baseline measurement, not production acceptance.
  `VideoInteractionAdmissionBaselineIsolatedTests` is opt-in only via `-DvideoStatusBaseline=true`;
  synchronous counting, real MySQL 13307 / Redis 16379, no RabbitMQ or schedulers, cleanup disabled.
- Parent run: 3 tests, zero failures/errors/skips (`/tmp/t16-b0-parent-baseline.log`). Each counted
  scenario has 200 serial samples plus separate warmup; 100 replays add no counts or window updates.
  Admission P95: single video 34.886 ms, ten videos 24.199 ms, replay 2.952 ms. Diagnostic only:
  no HTTP, asynchronous delivery, concurrency/capacity, fault drill or production claim.
- Post-return read timing measures only the SELECT after the service returns, not commit latency or
  an upper bound of commit-to-visibility. Visibility is established by exact counter assertions.
- Existing isolated MySQL/Redis were started after user approval (one failed Docker API attempt,
  followed by one approved successful retry); RabbitMQ remained stopped. No migration/deletion or
  queue purge. The run retains 1 user, 13 videos, 440 requests and 440 windows; outbox rows for the
  seeded videos remain zero. Do not repeat this successful measurement merely to resume work.
- Guide: `docs/tutorial/video-interaction-test-baseline.md`. T15 cleanup IT/migration remain unrun;
  T10/T12/T14 and the remainder of T16 are not completed by this baseline.

## Docker Compose deployment

`compose.yaml` starts MySQL, Redis, RabbitMQ, and the Spring Boot backend together. The backend is
built by the multi-stage `Dockerfile`; MySQL runs `database/feibijiubi.sql` automatically when its
named volume is initialized for the first time. Use `docker compose up -d --build` and access the API
at `http://localhost:8080`. Set `MYSQL_ROOT_PASSWORD`, `MYSQL_DATABASE`, `RABBITMQ_DEFAULT_USER`,
`RABBITMQ_DEFAULT_PASS`, or `BACKEND_PORT` in a `.env` file to override defaults. To recreate the
database from the SQL script, remove the named volume with `docker compose down -v`.
