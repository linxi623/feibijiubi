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
- `scheduling-enabled` – master switch for the batch-flush / cleanup / recovery schedulers.
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
- `utils/redis/` – `RedisUtils`, `RedisKeyUtils`, `RedisConstants`, typed Hash/Set/ZSet operation helpers.
- `utils/rabbitmq/` – `RabbitConstants` (exchange/queue/routing-key names).

### Video-statistics aggregation pipeline (current focus area)

Interaction counts (play / like / coin / collect / share) are **not** written straight to the DB.
The flow is:

1. Interaction → atomic Redis counter update (`lua/video-status-increment.lua`) and a
   `VideoStatusChangedEvent` / `VideoStatusDelta`.
2. **Outbox pattern**: `VideoStatusOutbox` rows are claimed (`VideoStatusOutboxClaimService`) and relayed
   to RabbitMQ (`VideoStatusOutboxRelay`) with publisher confirms.
3. **Consumer** (`VideoStatusEventConsumer` → `VideoStatusConsumptionServiceImpl`) applies deltas
   idempotently, guarded by `VideoStatusConsumedEvent` + a fingerprint service and a per-vid Redisson
   mutex (`VideoStatusVidMutex`).
4. **Batch flush** (`VideoStatusBatchFlushScheduler` → `...BatchFlushServiceImpl`) aggregates accumulated
   deltas from Redis into the `video_status` table in batches.
5. **Recovery / cleanup / rebuild** schedulers handle dirty records, delta cleanup, and rebuilding Redis
   state from the DB (`VideoStatusRebuild*`).

Consumer retry/dead-letter behavior is driven by the typed exceptions in `common/`:
`RetryableMessageException`, `NonRetryableMessageException`, `RepairRequiredMessageException`.

Design docs: `docs/video-status-full-flow-design.md`,
`docs/video-status-batch-aggregation-implementation.md`, `docs/video-stats-redis-mq-design.md`.

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
