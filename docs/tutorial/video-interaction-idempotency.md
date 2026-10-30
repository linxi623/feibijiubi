# T15：播放/分享的请求幂等与滚动窗口限计（DB 核心 + 第二阶段接线，候选未验收）

> 状态：**2026-10-23 候选实现，等待父代理验收。T15 未验收、未勾选、未上线。**
> 本文覆盖两个阶段：**第一阶段 = DB 核心**（两张新表 + 一个事务服务，§3—§9），
> **第二阶段 = HTTP 接线 + Redis 准入**（§11，已接线）。
>
> 一句话边界：**两个 HTTP 入口已接线**（`POST /api/videos/{vid}/play-count`、`/share` 必填
> `Idempotency-Key`，匿名必填 `X-Visitor-Id`），旧的无 actor/无幂等键方法签名已删除；但这是
> **测试环境证据**，不是生产防刷验收：匿名身份可随时更换、无容量压测。两张表的 30 天保留期清理已实现
> 但**默认关闭**（§12），且不是“到点必删”的 TTL。
> “已经防住刷播放”仍然不能作为对外结论。

## 1. 要解决的问题

旧路径（`UserVideoServiceImpl#recordStatus`）对每次请求都做一次计数：

- 同步模式：直接 `UPDATE video_status SET play_times = play_times + 1`；
- 异步模式：`createEvent` 生成一个新的 `eventId` 写 Outbox，消费者累计。

两条路都**没有请求级幂等**：客户端重试、页面刷新、脚本刷量都会各算一次，而且异步路径每次
重试都会生成一个新的合法 `eventId`，消费端的 `processed` 幂等键对“新事件”完全无效。
T15 要区分两件常被混为一谈的事：

| 概念 | 谁负责 | 现状 |
|---|---|---|
| **消息幂等** | 消费端 `eventId` + `processed` 键 | 已有（T01/T02） |
| **请求幂等** | 本阶段的 `video_interaction_request` | 本阶段新增 |

## 2. 已批准的产品语义（本阶段的实现依据）

1. **同一 actor 对同一 vid 的同一动作，滚动 30 分钟内最多计一次。**
2. **同一 `requestKey` 在记录保留期内只计一次**：重复提交重放第一次的结果，包括“被抑制”这个结果。
   记录被清理任务删掉之后（默认关闭，见 §12），同一 `requestKey` 按**新请求**处理，而仍受 30 分钟
   滚动窗口约束。保留期是 30 天（自 `completed_at` 起算），写死为常量、不可配置；清理**不是** 30 天
   到点的 TTL，到期与真正删除之间可能隔着很多轮调度。
3. **抑制不推进窗口**：窗口按“实际计数时间”滚动，不是固定周期。
4. **Redis 失败一律拒绝计数**（fail-closed）。第一阶段不涉及 Redis；第二阶段已接线并验证
   `null`/负数/异常都拒绝（§11）。
5. 匿名 actor 用**随机 UUID**，不是设备/浏览器指纹；DB 只存 `actor_type` + 规范化 64 位十六进制
   `actor_hash`，不存 uid、IP、User-Agent 或任何可回推个人的原文。

为什么是“30 分钟滚动窗口”而不是“按天/按次计数”：刷播放的收益来自重复请求的累积，滚动窗口
既能压住短时间刷量，又不会让正常用户（同一视频反复观看/分享）在长时间尺度上永久失去计数机会。
为什么窗口不可配置：把它做成 `application.yml` 开关会让“防刷语义”随环境漂移，而产品语义没有
对应的决策记录；要改就改代码 + 改本文。

## 3. 表结构

迁移：`database/migrations/t15-video-interaction-idempotency.sql`（幂等 `CREATE TABLE IF NOT EXISTS`，
只新增表，不 `ALTER`/`UPDATE`/`DELETE` 任何既有行）。镜像：`database/feibijiubi.sql`。

### 3.1 `video_interaction_request`（请求幂等）

| 列 | 类型 | 语义 |
|---|---|---|
| `id` | BIGINT PK | |
| `request_key` | VARCHAR(64) **utf8mb4_bin** | 业务请求幂等键，**大小写敏感**，全局唯一 |
| `actor_type` | VARCHAR(16) | `USER` / `ANONYMOUS` |
| `actor_hash` | CHAR(64) | 规范化小写 64 位十六进制 |
| `vid` | INT | FK → `video(vid)` |
| `action` | VARCHAR(16) | `PLAY` / `SHARE` |
| `state` | TINYINT | `0=PENDING`（占位，只在未提交事务里）/ `1=COUNTED` / `2=SUPPRESSED` |
| `event_id` | VARCHAR(64) NULL | 仅异步计数路径有值（`createEvent` 返回的 eventId）；同步路径为 NULL |
| `created_at` | DATETIME(3) | DB 时钟 |
| `completed_at` | DATETIME(3) NULL | 写终态的 DB 时间 |

唯一键 `uk_video_interaction_request_key (request_key)`。**大小写敏感**靠列级 `utf8mb4_bin`：
表默认排序规则仍是 `utf8mb4_unicode_ci`（与仓库其它表一致），只有这一列是 bin。若整表用 ci，
`'Req-1'` 与 `'req-1'` 会互相冲突，客户端换个大小写就能绕过幂等。

### 3.2 `video_interaction_window`（滚动窗口）

| 列 | 类型 | 语义 |
|---|---|---|
| `id` | BIGINT PK | |
| `actor_type` / `actor_hash` / `vid` / `action` | | 唯一键 `uk_video_interaction_window_actor` |
| `last_counted_at` | DATETIME(3) **NULL** | **NULL = 从未计数**；只由“真正计数”的那次事务推进 |
| `created_at` | DATETIME(3) | DB 时钟 |

`last_counted_at` 允许 NULL 并且**必须**允许 NULL，这是本设计最容易写错的一点，见 §5。

## 4. 一个请求的完整流程

> 下面是 **DB 核心 `record()` 的内部流程**。第二阶段的 HTTP 路径在它之前还有“参数校验 → 身份解析 →
> 视频校验 → 只读重放 → Redis 准入”五步，顺序与理由见 §11；本节只描述 `record()` 自己做了什么。

```
record(command)                                     ← 要求：调用方不在事务里
 ├─ 校验 requestKey / actorHash / vid / action     → 400
 ├─ [事务外] SELECT request WHERE request_key = ?   ← 幂等读
 │    ├─ 命中：形状一致 → 重放历史结果（duplicate=true）
 │    └─ 命中但 actor/vid/action 不一致 → 409
 └─ TransactionTemplate（无外层事务，REQUIRED）
      ├─ INSERT video_interaction_request(state=0 PENDING)     ← 唯一键冲突 = 并发同键
      ├─ INSERT video_interaction_window ... ON DUPLICATE KEY UPDATE last_counted_at = last_counted_at
      │                                                       ← 只保证行存在，不初始化时间
      ├─ SELECT ... FOR UPDATE                                ← 行锁，同 actor+vid+action 串行
      ├─ UPDATE ... SET last_counted_at = NOW(3)
      │     WHERE id=? AND (last_counted_at IS NULL
      │                    OR last_counted_at <= TIMESTAMPADD(MINUTE,-30,NOW(3)))
      │                                                       ← 1 行 = 获准；0 行 = 抑制且不写任何列
      ├─ 获准才计数：异步 createEvent（同事务，MANDATORY）/ 同步 increase*Times 守卫更新
      └─ UPDATE video_interaction_request SET state=?, event_id=?, completed_at=NOW(3)
            WHERE id=? AND state=0                            ← 终态 CAS
```

要点：

- **到期判定和推进是同一条 SQL**，判定用 **DB 时钟** `NOW(3)`。多实例部署时 JVM 时钟偏差会
  直接变成“窗口提前放开”或“窗口永久抑制”，所以整条链路不用 JVM 时间做业务判断。
- **边界取 `<=`**：恰好 30 分钟前计数的那次可以再计。
- **`FOR UPDATE` 之后仍然用守卫更新做判定**，不是“先读后写”。读到的值只用于日志/诊断，
  真正的判定与推进在同一语句里，避免 TOCTOU。
- **抑制路径没有任何写操作**：守卫更新 0 行，窗口时间不变。
- **`event_id` 复用既有事件**：异步路径直接使用 `VideoStatusService#createEvent` 返回的
  `VideoStatusChangedEvent.eventId()`（该方法本来就是 `Propagation.MANDATORY`，天然加入本事务）。
  事件协议、消费者、刷库、Redis Lua 一行没改；不存在“为幂等改事件协议”。
- **同步路径**复用既有守卫更新 `increasePlayTimes` / `increaseShareTimes`（`DECIMAL(20,0)` 上界
  守卫）。影响行数不为 1（缺行 / 已达 `Long.MAX_VALUE`）时抛 500 并整体回滚——与
  `UserVideoServiceImpl` 现有行为一致。

## 5. 三个必须守住的反例

### 5.1 第一条请求必须计数

如果窗口行插入时把 `last_counted_at` 初始化成 `NOW(3)`（或 `0`、或 `CURRENT_TIMESTAMP` 默认值），
第一条请求会被自己的初始值判成“刚计过”，**每个新 actor 的第一次播放永远不计**——防刷直接变成
“防正常用户”。所以：

- 列可 NULL，插入值显式为 `NULL`；
- ODKU 命中分支是自赋值 `last_counted_at = last_counted_at`，语义上“什么都不改”；
- 判定条件第一支就是 `last_counted_at IS NULL`。

### 5.2 抑制不推进窗口

抑制路径如果顺手把 `last_counted_at` 刷新成 `NOW(3)`，会变成“每次刷新都重置计时器”：
持续刷新的脚本可以让正常用户永远排不到计数窗口。所以抑制必须**零写**。

### 5.3 重复旧请求不重新判定

`requestKey` 已经有终态（COUNTED/SUPPRESSED）时，直接重放快照，不再查窗口。否则“先被抑制、
30 分钟后重试”会变成一次新判定，同一个业务请求可能被计两次。真实隔离用例
`suppressedRequestKeyStillReplaysSuppressedAfterWindowExpires` 把窗口推到 2 小时前来证明这一点。

## 6. 并发同 `requestKey`：回滚后再读赢家

唯一键冲突（`DuplicateKeyException`）在 InnoDB 里意味着**对方已经提交**（否则我们的 INSERT 会在
唯一索引锁上等到对方回滚后成功）。正确处理只有一种：

1. 让异常**穿出** `TransactionTemplate` 回调，本次尝试事务整体回滚；
2. 回滚完成后，在**事务外**重新读取赢家行并重放其结果。

**禁止**在回调里 `catch (DuplicateKeyException)` 后继续用同一个事务：Spring 已经把该事务标记为
`rollback-only`，后续任何写都会在提交时炸成 `UnexpectedRollbackException`，而且此前写入的
占位行也不会真的落库——读起来像成功，实际上是半死状态。

因此本服务有明确的调用契约：

- `record()` **要求调用方不在事务里**；
- `record()` 入口用 `TransactionSynchronizationManager.isActualTransactionActive()` 断言，
  违反直接抛 `IllegalStateException`（不静默降级）；
- 尝试事务用默认 `REQUIRED`：没有外层事务时它自己开一个，行为可预测。

## 7. 原子性边界

请求占位、窗口推进、计数（Outbox 事件或同步守卫更新）、请求终态在**同一个 MySQL 事务**里，
任一步失败整体回滚。不会出现“占位成功但没计数”“计数成功但请求仍 PENDING”“窗口被推进但没计数”。

这条不变量**不是 Mockito 能证明的**：单测只能证明“异常穿过 `TransactionTemplate` 并触发
rollback、后续写没有执行”。真实回滚由两个隔离用例证明：

- `VideoInteractionIsolatedTests#outboxInsertFailureRollsBackRequestAndWindowAndRetryStillCounts`：
  用真实 MySQL 触发器让 Outbox 插入失败（`SIGNAL SQLSTATE '45000'`），断言请求行不存在、
  窗口行不存在、无 Outbox 行；移除触发器后同一 `requestKey` 仍能首次计数。
- `VideoInteractionSyncIsolatedTests#syncCountFailureAtLongMaxRollsBackRequestAndWindow`：
  把 `video_status.play_times` 置为 `Long.MAX_VALUE` 让守卫更新 0 行，断言请求行与窗口行都不存在、
  计数不变；修好计数条件后同一 actor 的新请求立刻能计数（窗口没被吃掉）。

## 8. 代码位置与 API（DB 核心；HTTP 入口见 §11）

| 内容 | 位置 |
|---|---|
| HTTP 入口 | `controller/UserVideoController#increasePlayCount` / `#increaseShare` |
| 准入编排 | `service/video/interaction/VideoInteractionAdmissionService` + `impl/.../VideoInteractionAdmissionServiceImpl` |
| 身份解析 | `impl/.../VideoInteractionActorResolver` |
| Redis 准入 | `impl/.../VideoInteractionRateLimitServiceImpl` + `config/VideoInteractionProperties` |
| 视频校验（共用） | `service/video/PublishedVideoGuard` |
| 类型 | `service/video/interaction/VideoInteractionActorType`、`VideoInteractionAction` |
| 命令/结果 | `.../VideoInteractionCommand`、`.../VideoInteractionResult` |
| 服务接口 | `.../VideoInteractionService#record(VideoInteractionCommand)` |
| 实现 | `service/impl/video/interaction/VideoInteractionServiceImpl` |
| 清理服务（§12） | `service/video/interaction/VideoInteractionCleanupService` + `impl/.../VideoInteractionCleanupServiceImpl` |
| 清理调度（§12） | `impl/.../VideoInteractionCleanupScheduler` + `config/VideoInteractionCleanupSchedulingConfig` |
| 清理索引迁移（§12） | `database/migrations/t15-video-interaction-cleanup-indexes.sql` |
| Mapper | `mapper/VideoInteractionMapper` + `resources/.../VideoInteractionMapper.xml` |
| 实体 | `entity/VideoInteractionRequest`、`entity/VideoInteractionWindow` |

```java
VideoInteractionResult record(VideoInteractionCommand command);

record VideoInteractionCommand(
        VideoInteractionActorType actorType,   // USER / ANONYMOUS
        String actorHash,                      // 规范化小写 64 hex
        Integer vid,                           // > 0
        VideoInteractionAction action,         // PLAY / SHARE
        String requestKey);                    // [A-Za-z0-9._:-]{1,64}，大小写敏感

record VideoInteractionResult(
        String requestKey,
        Long requestId,
        String state,        // COUNTED / SUPPRESSED
        boolean counted,     // 本次（或重放对应的原始请求）是否真的计了一次
        boolean duplicate,   // 是否命中已存在的 requestKey（幂等重放）
        String eventId);     // 仅异步路径；同步路径为 null
```

错误码：`400` 参数非法（`requestKey` 非法字符/超长、`actorHash` 非规范化 64 hex、`vid<=0`、
`action` 非 PLAY/SHARE）；`409` `requestKey` 被不同形状占用 / 已提交行竟是 PENDING；
`500` 唯一键冲突后读不到赢家，或计数/写入影响行数异常（整事务回滚）。

## 9. 测试证据（测试环境，不是生产验收）

- 纯单测 `VideoInteractionServiceTests`：**33 例**（第一阶段交付时 25 例，第二阶段补齐后 32 例，
  恢复轮新增 Outbox 唯一键冲突回滚用例后为 33 例）。覆盖首次计数、窗口抑制、占位 PENDING、
  幂等重放（COUNTED / SUPPRESSED）、形状冲突（vid/action/actorHash）、PENDING 与未知 state、
  并发同键回滚后重放赢家、并发同键读不到赢家、同步/异步两条计数路径、计数失败与事件创建失败的
  异常传播与 rollback、影响行数异常（占位 0 行 / 窗口行缺失 / 终态 0 行）、参数校验、
  以及“在已有事务中调用直接拒绝”。
- opt-in 隔离 `VideoInteractionIsolatedTests`（异步路径，MySQL 13307 + Redis 16379）：**7 例**。
  首次计数恰好一条 Outbox 事件且 `video_status` 不被同步更新；`-1799s` 抑制且窗口不变、
  `-1801s` 再计并推进；被抑制的旧 `requestKey` 在窗口过期后仍重放 SUPPRESSED；
  并发同一 `requestKey` 只计一次（两个调用指向同一 eventId，恰好一个 duplicate）；
  并发不同 `requestKey` 命中同一窗口只计一次；Outbox 插入故障整事务回滚。
- opt-in 隔离 `VideoInteractionSyncIsolatedTests`（同步路径，只连 MySQL 13307）：**7 例**。
  同步 PLAY/SHARE 走守卫更新、`event_id` 为 NULL、无 Outbox 行（用一个“被调用就失败”的
  `VideoStatusService` 桩证明同步路径绝不建事件）；窗口抑制零写；`Long.MAX` 计数失败整事务回滚；
  同一 `requestKey` 换 vid 返回 409 且不额外计数；重放不再计数；**`requestKey` 大小写敏感**
  （仅大小写不同的两个键是两个独立请求，若列是 ci 唯一键则第二个会撞唯一键被当成重放）。
- 普通回归 `./mvnw '-Dtest=*Tests,!BackendApplicationTests' test`：第一阶段日志为 **613 tests，
  0 失败/错误，194 skipped**（`/tmp/t15-unit-full2.log`）；第二阶段当前工作区为 **666 tests，0 失败/错误，
  200 skipped**（`/tmp/t15b-unit-full.log`，`Tests run` 在第 14129 行，`BUILD SUCCESS` 在第 14132 行；
  三个 opt-in T15 隔离类在该模式下整体 skip）。受限堆串行。

两个隔离类合并跑（`./mvnw -DvideoStatusIT=true '-Dtest=VideoInteractionIsolatedTests,VideoInteractionSyncIsolatedTests' test`）
= **14 tests，0 失败/错误/跳过**（`/tmp/t15-interaction-it.log`）。
隔离用例只使用 `compose.test.yaml` 的 13307/16379/15673，不连正式资源、不清库；迁移只对隔离
MySQL 执行。故障注入用临时触发器 `t15_fail_outbox`，用例前后都 `DROP TRIGGER IF EXISTS`。

## 10. 明确未做（阻塞 T15 收尾）

第一阶段的 1—4 条已由第二阶段实现（见 §11），这里保留原条目并标注现状；当前仍然欠的是：

1. ~~没有 HTTP 接口、没有调用方~~ → **已接线**（§11），但只是测试环境证据，不是生产防刷验收。
2. ~~没有身份解析~~ → **已实现**（`VideoInteractionActorResolver`），但匿名身份是客户端随机 UUID，
   可随时更换，因此不构成不可绕过的防刷墙。
3. ~~没有 Redis 接线~~ → **已接线**（两维固定窗口，fail-closed → 503），代价是额度在事务之前消耗、
   失败不退，且两个维度不是原子的。
4. ~~没有超限的统一业务响应~~ → **已实现**（429 + 单一文案 `操作过于频繁，请稍后再试`，不暴露维度/剩余额度）。
5. **没有生产死锁重试**：并发不同 `requestKey` 命中同一窗口时的死锁概率、是否需要重试策略，
   仍按“不加生产重试”处理；隔离用例只覆盖两条明确的并发路径。
6. **窗口/请求表清理**：~~保留期与清理策略**未决策**，没有任何清理代码~~ → 已实现 30 天有界清理
   （默认关闭，§12），但它不是 TTL、也不承诺 30 天到点必然删除。`video_interaction_request` 仍然是
   每个请求一行，开启清理后才按批下降。
7. **没有旧客户端兼容窗口**：缺 `Idempotency-Key`/`X-Visitor-Id` 直接 400，前端必须同步发布（已批准，
   不是遗漏）。
8. 未做容量/压测，未做旧数据回填（两张表初始为空，不代表历史交互已去重）。
9. 恢复轮修正：唯一键冲突后读不到赢家时返回 500，不再误报请求冲突 409，事务仍整体回滚；
   日志不输出底层异常文本或请求键。新增 Outbox 唯一键冲突回滚单测，定向实跑
   `VideoInteractionServiceTests` 33 tests，0 failures/errors/skipped（`/tmp/t15-resume-service-unit.log`）。
   这不是全量或隔离验收；隔离套件最近一次完整运行仍有 1 个历史并发用例失败（§11.7）。

## 11. 第二阶段：HTTP 接线与 Redis 准入（当前事实）

### 11.1 入口与请求契约

`POST /api/videos/{vid}/play-count` 与 `POST /api/videos/{vid}/share`（`UserVideoController`）都是
`@OptionalLogin`，都接收 `Idempotency-Key` 与 `X-Visitor-Id` 两个头，并返回
`ApiResponse<VideoInteractionVO>`。两个头在 Controller 上都声明 `required = false`，由服务层给明确的 400：
若交给 Spring 必填校验，缺头会抛 `MissingRequestHeaderException`，而全局异常处理器没有该分支，会退化成 500。

- `Idempotency-Key`：**必填**，`[A-Za-z0-9._:-]{1,64}`，大小写敏感（列级 `utf8mb4_bin`）。
- `X-Visitor-Id`：匿名**必填**，canonical UUID（`8-4-4-4-12` 十六进制，大小写不敏感，归一化小写后哈希）；
  登录态下整个忽略（包括非法值）。客户端自己生成，服务端不生成、不下发 Cookie。
- 响应只有三个字段：`counted`、`duplicate`、`reason`（`COUNTED` / `WINDOW_SUPPRESSED`），
  不返回 `requestId`/`eventId`/`actorHash`/窗口时间/剩余额度。
- HTTP 状态与响应体 `code` 相同（400/401/404/409/429/500/503），因为 `GlobalExceptionHandler` 按
  `BusinessException.code` 设置 HTTP Status。

旧签名 `UserVideoService#increasePlayCount(Integer)` / `#increaseShare(Integer)` 已删除，**没有保留重载**：
留一个旧方法就等于留一条完全绕过准入的计数入口。新签名是
`increasePlayCount(Integer currentUserId, Integer vid, String requestKey, String visitorId)`（`share` 同形），
两个方法都只做编排委派、**故意不加 `@Transactional`**。

### 11.2 准入顺序（`VideoInteractionAdmissionServiceImpl`）

1. **参数校验**（`VideoInteractionValidation`，与 DB 核心同一份规则）——必须早于任何 Redis 计数，
   否则非法请求会先吃掉一份额度；
2. **身份解析**（`VideoInteractionActorResolver`）——登录优先，域分离前缀
   `video-interaction:actor:v1:user:` / `...:anonymous:`；
3. **视频公开有效性**（`PublishedVideoGuard#requirePublishedPlayable`）——400/404/500，与
   点赞/投币/收藏/进度共用同一份规则（原来散在 `UserVideoServiceImpl#validateVideo` 里）；
4. **只读重放查询**（MySQL）——命中直接返回，**不碰 Redis**；未命中才进入限流，所以重复请求不消耗额度；
5. **Redis 准入**；
6. **`record`**（§4）。

竞态：查重放与 `record` 之间有窗口期，两个并发同 `requestKey` 的请求可能都走到限流、各消耗一份额度，
但 `record` 内部会再查一次并由唯一键保证只计一次。代价只是“提前触发 429”，不会多计业务次数。

### 11.3 身份解析的诚实边界

- 这是**伪名化**，不是不可逆匿名化：uid 空间很小，哈希前缀公开且无密钥，看到 64 hex 就能枚举反查；
- 匿名访客可随时换 UUID 获得新身份，因此“同一访客 30 分钟一次”只是**合作客户端的约束**，
  绕过成本很低；
- 不采集 IP、User-Agent、Canvas/设备指纹（跨站稳定标识与最小化存储冲突），代价是“同一自然人换 UUID”不可识别。

### 11.4 Redis 准入（`VideoInteractionRateLimitServiceImpl`）

复用既有 `lua/fixed-window-rate-limit.lua`，每个维度一次脚本调用（`INCR` + 首帧/无 TTL 时 `EXPIRE`）：

| 维度 | 默认 | 配置键 |
|---|---|---|
| 窗口长度 | 60 s | `app.video-interaction.window-seconds` |
| 同一 actor 单动作 | 60 次/窗口 | `app.video-interaction.max-requests-per-action` |
| 同一 actor 全部动作合计 | 120 次/窗口 | `app.video-interaction.max-requests-per-actor` |

启动期校验（`VideoInteractionProperties#validate`）：范围 1..3600 / 1..10000，且
`max-requests-per-actor >= max-requests-per-action`（否则单动作维度会被静默关掉，直接启动失败）。
`application.yml` 里**没有**这三个键，默认值就是生效值。

两个必须写明的代价：

1. **两个维度不是原子的**：先单动作、再合计。第二个维度失败时第一个可能已经 +1，只影响“提前用完额度”；
2. **额度在业务成功之前消耗**：准入通过后 DB 事务失败（视频刚被删、`Long.MAX` 上界拒绝等）额度**不退还**。
   退回需要两阶段补偿、补偿自身也会失败，所以选择“宁可少放行”。

超限：`BusinessException(429, "操作过于频繁，请稍后再试")`，两个维度共用同一文案，不暴露是哪个维度或剩余额度。
Redis 异常/`null`/负数：`RedisOperationException` → 503（fail-closed），绝不 fail-open。

### 11.5 第二阶段新落地的已批准决策

- **两个请求头强制、不兼容旧客户端**：缺 `Idempotency-Key`（匿名还缺 `X-Visitor-Id`）→ 400；
  不做灰度或兼容窗口，前端与接口同步发布。
- **视频校验先于幂等重放**：视频下架/删除后，同一个 `requestKey` 重试返回 **404**，不是历史结果。
  这是有意的取舍：重放保证的是“同一个已提交请求不重复计数”，不是“视频状态变化后仍返回旧结果”。
- **匿名身份 = 客户端随机 UUID**，不新增服务端下发/签名机制；防刷局限已写入 §11.3。
- **30 分钟窗口不可配置**（`VideoInteractionServiceImpl.WINDOW_MINUTES`）。

### 11.6 第二阶段测试证据（测试环境，不是生产验收）

| 类 | 数量 | 日志 |
|---|---|---|
| `VideoInteractionHttpMappingTests` | 7 | `/tmp/t15b-target-unit3.log`（头/`@OptionalLogin`/VO 映射、旧签名不存在） |
| `VideoInteractionActorResolverTests` | 7 | 同上（登录优先、非法 visitor 头、大小写归一） |
| `VideoInteractionAdmissionServiceTests` | 17 | 同上（顺序：非法输入不消耗额度、重放先于 Redis） |
| `VideoInteractionRateLimitServiceTests` | 9 | 同上（两维、恰好上限、`null`/负数/Redis 异常 fail-closed） |
| `VideoInteractionAdmissionIsolatedTests`（opt-in） | 6 | `/tmp/t15b-admission-it4.log`，0 失败/错误/跳过 |
| `VideoInteractionIsolatedTests` + `VideoInteractionSyncIsolatedTests` + `VideoStatusCounterTransactionTests`（opt-in 回归） | 20 | `/tmp/t15b-regression-it1.log`，0 失败/错误 |

定向单测 `-Dtest=VideoInteractionHttpMappingTests,VideoInteractionActorResolverTests,VideoInteractionAdmissionServiceTests,VideoInteractionRateLimitServiceTests,VideoInteractionServiceTests`
= **72 tests，0 失败/错误**（`/tmp/t15b-target-unit3.log`，`BUILD SUCCESS`）。

准入隔离 6 例（真 MySQL 13307 + Redis 16379）：同一访客首次计数后窗口抑制；超限 429 且**零 DB 请求行**；
重放在额度耗尽 + 窗口过期后仍返回历史结果；actor 合计维度拦截；匿名/登录双身份空间且登录忽略 visitor 头；
校验失败不产生任何限流键。

全链路演练入口已改为 `VideoStatusIsolatedIntegrationTests#countOnePlay()`（每次随机访客 + 随机 `requestKey`，
断言 `counted && !duplicate`），维护演练 1 例通过（`/tmp/t15b-drill-maintenance.log`，
`T04 maintenance drill vid=7134 asyncEvents=4 syncWrites=2 baseline=6 current=6`），即新入口已被真实
relay/consumer/flush 异步路径与同步基线路径演练覆盖。

### 11.7 未绿的一项（历史并发用例，未定位未修复）

隔离套件最近一次**完整**运行（`/tmp/t15b-drill-it2.log`）是 `VideoStatusIsolatedIntegrationTests`
**20 tests，1 failure，1 skipped，BUILD FAILURE**：`concurrentFlushAndConsumptionPreserveAllEvents`
报 `condition did not converge in 30 seconds`（`/tmp/t15b-drill-it2.log:300-318`）。该用例只用自己的
`applyAndMark(event())`，**不经过 T15 新入口**，与[历史上下文快照](../history/agent-context-before-slimming.md)已登记的未解决历史 flake 同名；之后单独补跑
通过的日志是 `/tmp/t15b-drill-single.log`、`/tmp/t15b-drill-maintenance.log`，但根因从未定位或修复，
所以本文**不得**把它写成已解决，也不存在“整套 opt-in IT 全绿”的可引用运行。

## 12. 30 天有界清理（本轮切片，默认关闭，候选未验收）

> 状态：**候选实现，未父验收、未上线、未运行隔离用例**。下面的边界与风险必须和代码一起读。

### 12.1 用户已确认的保留期语义

| 对象 | 保留期 | 起算点 |
|---|---|---|
| `video_interaction_request`（终态行） | 30 天 | `completed_at`（写入终态的那次 DB 时间） |
| `video_interaction_window` | 连续 30 天**没有计数** | `last_counted_at`（最近一次真正计数） |

- 保留期**写死成常量**：`VideoInteractionCleanupService.REQUEST_RETENTION_DAYS` /
  `WINDOW_RETENTION_DAYS`，理由与 30 分钟窗口同源——做成配置项就能配成 0 天，窗口行每轮被删、
  30 分钟限计被静默绕过。这也是本切片**没有** `retention-days` 旋钮的原因。
- **当前语义**：记录还在且未被清理 → 同一 `requestKey` 重放历史结果；记录已被清理 → 同一
  `requestKey` 按新请求处理，仍受 30 分钟滚动窗口约束。这取代了文档里“`requestKey` 永远只计一次”
  的旧表述。
- **清理默认关闭**，且是**按批推进的维护任务**，不是“30 天到点必然发生的 TTL”：一条 31 天前的记录
  在下一轮清理读到它之前仍会重放。不要向调用方承诺“过期即不可重复提交”。

### 12.2 配置（`app.video-interaction.*`，`application.yml` 无条目时即默认值）

| 键 | 默认 | 校验 | 含义 |
|---|---|---|---|
| `cleanup-enabled` | `false` | — | 关闭时调度器与服务都不执行任何删除（服务内二次判断） |
| `cleanup-batch-size` | `500` | `1..5000` | 每批（每条语句）删除上限；上限是硬上界，不是容量旋钮 |
| `cleanup-fixed-delay-ms` | `60000` | 正数 | 轮次间隔 |

调度分成**两个不同名的 bean**，不能同名（同名会让 Spring Boot 在关闭 bean 覆盖时启动失败）：

- 调度组件 `VideoInteractionCleanupScheduler`（`@Component`，bean 名 `videoInteractionCleanupScheduler`）：
  只有 `@Scheduled` 入口，运行期检查 `cleanup-enabled`；
- 专用线程池 bean `videoInteractionCleanupTaskScheduler`（`ThreadPoolTaskScheduler`，单线程，线程名
  `video-interaction-cleanup-*`，5 s 有界停机）：由 `@Scheduled(scheduler = ...)` 按名字引用。

它**不**与 T06 的投递/落库/恢复/补偿共享线程：清理可能在等交互事务的行锁，把它塞进统计链路只会让维护动作
出现在业务心跳上。装配区分由 `VideoInteractionCleanupSchedulingWiringTests` 在真实 Spring 上下文（关闭 bean
覆盖、无 DB）里钉住。

### 12.3 一轮做什么（`VideoInteractionCleanupServiceImpl#cleanupOnce`）

1. `state=1 COUNTED` 一批：非锁定选 id（`LIMIT batch-size`）→ 按主键带守卫删；
2. `state=2 SUPPRESSED` 一批：同上；
3. 空闲窗口一批：`last_counted_at IS NOT NULL AND <= NOW(3) - 30 天` → 按主键带守卫删。

三条硬约束：

- **先选 id，再按主键等值删**。单条范围 DELETE 在 REPEATABLE READ 下会取 next-key 锁，可能挡住
  `completeRequest` 的索引项搬迁（`state 0→1/2`）与新窗口行插入。这只是**设计意图**：**不保证**优化器
  一定走主键等值访问路径，也**不承诺锁足迹**（是否取间隙锁/next-key、锁等待多久）——那必须用真实 MySQL 的
  `EXPLAIN` 与锁观测实测，本轮未运行该隔离用例，也没有任何实测数据。
- **DELETE 里原样重复时间 + 状态守卫**。候选读与删除之间没有事务：窗口行可能在两者之间被
  `advanceWindowIfDue` 推进到 `NOW(3)`，只按 id 删就会删掉一个刚计数的窗口行——下一次请求重新计数，
  30 分钟限计被静默绕过。这条时间守卫是**唯一**防线。
- **不新增 JVM 时钟阈值**：阈值由 SQL 计算（`TIMESTAMPADD(DAY, -?, NOW(3))`），不把 JVM `LocalDateTime`
  当参数传，所以不会把应用侧时钟偏差变成一个删除阈值。这**不**等于“DB 时钟没有风险”：`NOW(3)` 取的是
  MySQL 会话时区的墙上时间，阈值与 `completed_at`/`last_counted_at` 都由同一个 DB 时钟写入并比较，因此
  同一 `time_zone` 配置下二者自洽；但会话/服务端时区被改动、或 DST 让 DAY 运算跨过不存在的墙钟时间时，
  30 天边界仍会平移（相对 30 分钟窗口差 1440 倍，只会让清理提前/推迟，不会删掉仍在窗口内的行）。
  本轮没有任何时区/时钟切换测试。

不开显式事务：单条 DELETE 自身原子，跨语句原子性不需要（守卫会重判），而开事务会把整批锁持有到提交、
放大 `record` 的等待窗口。服务入口因此**拒绝**在已有事务里运行（`IllegalStateException`），保证每个
mapper 操作各自提交。

`timeout=3` 只是语句超时意图（MyBatis/JDBC 层），**不是**硬 3 秒截止保证。

### 12.4 永不清理的行（放宽它们就是改语义，属于另一个 bug）

- `state=0 PENDING`：占位与终态同事务提交，已提交的 PENDING 行在当前代码下不可达；真出现应作为独立
  告警处理，不能靠清理“顺手删掉”。
- `completed_at IS NULL` 的终态行：无法证明保留期起点，只能保守排除，**不得**回填成 `created_at`。
- `last_counted_at IS NULL` 的窗口行：删掉等价于把窗口重置成“从未计数”，下一次请求必然计数。

### 12.5 索引是开启清理的硬前置

`database/migrations/t15-video-interaction-cleanup-indexes.sql`（幂等，只加索引，镜像进
`database/feibijiubi.sql`）补两条索引：

- `(state, completed_at, id)`：`state` 必须**单值**查询；写成 `IN (1,2)` 会让两个 range 需要全局排序，
  `LIMIT` 提前终止失效。
- `(last_counted_at, id)`：NULL 段不在任何时间范围里。

两条候选查询带 `FORCE INDEX`：**缺索引时 MySQL 直接报 1176，清理在候选查询这一步失败、下一轮重试，
不会静默退化成全表扫描**。这与 T12-B 把 T10 reconciliation 索引升级成“硬部署依赖”的处理方式一致。
首次开启时积压的下降速率要**分两类**看（默认 `batch-size=500`、`fixed-delay-ms=60000`）：
请求行 `2 × 500 / 分钟 = 1000 行/分钟 ≈ 1.44 M/天`（两个终态各一批），窗口行最多 `500/分钟 ≈ 0.72 M/天`，
合起来 `3 × batch-size / fixed-delay-ms = 1500 行/分钟 ≈ 2.16 M/天`。这是**理想上限，不是实测速率**：
实际取决于积压里两类行的比例、每轮的语句耗时、与 `record` 的行锁等待、以及缺索引/慢查询——本轮没有
做任何吞吐测量，也没有容量结论。百万级积压需要临时调大批大小或调小间隔——这是运行说明，不是新增旋钮。

### 12.6 残余风险（只记录，不在本切片修）

1. **异步路径下请求行删除不等待 Outbox 终态**。如果 Outbox 积压超过 30 天（本身已是事故），同
   `requestKey` 重试会生成**第二个 `eventId`**，原事件之后仍可能被消费。这里**不写成“必然重复计数”**：
   旧事件还受消费侧 30 天年龄守卫（T14-S1，`VideoStatusEventAgeGuard`）与既有重放/补偿窗口约束，
   实际结果是“取决于旧事件何时被消费、是否仍在窗口内”的分支，不是确定结论。修它需要 JOIN Outbox，
   与“不碰统计账本”冲突，因此明确不修。
2. 按已批准语义，`last_counted_at IS NULL` 的窗口行**永不清理**；已提交的 NULL 行在当前代码下不可达，
   所以这是理论泄漏，真出现应作为独立 bug。
3. 多实例并发清理：两边都拿到同一批 id，一个删 N 行、一个删 0 行；极端情况同一主键死锁 → 语句级回滚 +
   下一轮重试。因此**不加**分布式锁（只多一个“锁泄漏导致清理永久停摆”的故障模式）。

### 12.7 测试（本轮实际执行范围）

纯单测（本轮实跑，`-DvideoStatusIT=false -DvideoStatusScale=false`，受限堆串行）：

| 类 | 用例数 | 覆盖 |
|---|---|---|
| `VideoInteractionCleanupServiceTests` | 19 | 默认关闭零 mapper、空批不删、两终态且从不查 `state=0`、只删选中 id + 固定 30 天、影响行数少于候选不算错、批满标志、候选超批/非法 id/null 列表 fail closed、select/delete 失败传播且下一轮重新选、属性边界与默认值、调度器关闭不调服务/异常不冒泡 |
| `VideoInteractionCleanupContractTests` | 9 | 两条 SELECT 有界 + `FORCE INDEX` + 单值 state + 仅 DB `NOW(3)` 阈值（不新增 JVM 时钟阈值）；两条 DELETE 重复时间/状态守卫 + 空集合 `WHERE 1 = 0`；**用真实 MyBatis 解析后渲染动态 SQL**（空 id → `WHERE 1 = 0`、非空 id → `id IN (?,?)` + 守卫、候选查询保留 `FORCE INDEX`/`ORDER BY`/`LIMIT`）；**隔离类 `@AfterEach` 只能是跟踪 id 的主键删、禁止调用全局 `cleanupOnce()`（源码文本契约）**；清理语句不含 `video_status*`/`PROCESSED`/`REDIS`；迁移幂等且不含删除类语句；schema 镜像两条索引；清理/调度器无 Redis/统计/队列协作者 |
| `VideoInteractionCleanupRetentionTests` | 2 | 保留期常量 = 30/30；保留期远大于 30 分钟窗口 |
| `VideoInteractionCleanupSchedulingWiringTests` | 3 | 真实 Spring 上下文（无 DB、`allow-bean-definition-overriding=false`）：`@Component` 组件 bean `videoInteractionCleanupScheduler` 与线程池 bean `videoInteractionCleanupTaskScheduler` 类型/名称不同且各自唯一；`@Scheduled(scheduler=...)` 引用该线程池并能解析；缺该 bean 时 refresh 必须失败并点名（证明引用不是被静默忽略）。回退成同名 bean 时该测试以 `BeanDefinitionOverrideException` 失败（实测） |

本轮实际运行的命令与结果（受限堆串行）：
`./mvnw -DvideoStatusIT=false -DvideoStatusScale=false '-Dtest=VideoInteractionCleanupServiceTests,VideoInteractionCleanupContractTests,VideoInteractionCleanupRetentionTests,VideoInteractionCleanupSchedulingWiringTests' test`
= **33 tests，0 失败/错误/跳过**（`/tmp/t15-cleanup-target2-unit.log`；调度装配单独跑为 3 tests，
`/tmp/t15-cleanup-wiring-unit.log`）；
`./mvnw -DvideoStatusIT=false -DvideoStatusScale=false '-Dtest=*Tests,!BackendApplicationTests' test`
= **744 tests，0 失败/错误，214 skipped，BUILD SUCCESS**（`/tmp/t15-cleanup-wiring-full.log`，其中
`VideoInteractionCleanupIsolatedTests` 13 例整体 skip）。**未运行**任何隔离 IT、scale、容器、DB、Redis
或迁移；上面的隔离覆盖是“已写好但未执行”，不能当成通过证据。

opt-in 隔离类 `VideoInteractionCleanupIsolatedTests`（真 MySQL 13307；**本轮只编译、未运行**）覆盖：
过期/新鲜/`completed_at IS NULL`/PENDING/`last_counted_at IS NULL` 矩阵；删掉过期窗口后“首次计数 + 第二次
抑制”仍成立；29 天记录重放 vs 记录被删后同 `requestKey` 变成新请求；先选 id 再推进时间再删 = 0 行；
真实行锁交错下不删刚推进的窗口行；空 id 集合 fail closed；批大小有界 + 多轮收敛 + 重跑幂等；
两条候选查询的 `EXPLAIN` 命中新索引且无 filesort；不碰 `video_status`/Outbox/消费账本。

**全局清理与测试数据边界**：生产清理没有 vid 作用域（必须扫全表才能推进），所以隔离用例也不能给自己
划 vid 边界。该类的做法是：每轮真正执行清理之前（含两个“先选候选再推进时间”的确定性用例）先检查
“隔离库里是否存在不是本类这次造出的过期候选行”，只要存在就**立即失败退出、不执行任何 DELETE**，失败消息
要求操作者确认后在全新隔离库上重跑。它的 `@AfterEach` 收尾**不能**跑全局 `cleanupOnce()`（那会在 guard
失败后照样执行、把刚拒绝的别人的行删掉），而是只用本次跟踪的 id 集合走 `deleteExpiredRequestsByIds` /
`deleteIdleWindowsByIds`（同样带时间/状态守卫）；跟踪完整性由 foreign guard 兜底（任何未跟踪的过期候选
都会被判为外来数据），并由 `VideoInteractionCleanupContractTests` 的源码文本契约钉住收尾形状。它从不清空表、
不删别人的行，也不会偷偷跑。
