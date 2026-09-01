# 菲比啾比 Java 后端面试八股与项目深挖手册

> 整理日期：2026-08-31。本文以当前仓库代码为准，不把设计文档中的设想当成已经实现的功能。

## 0. 面试前必须先修正的简历表述

当前简历写的是：

> 互动先落 Redis，经 Outbox 表可靠投递到 RabbitMQ，消费端幂等消费，最终批量聚合写入 MySQL。

但当前代码的真实顺序是：

1. 点赞、收藏、投币等接口在本地事务中先更新 `user_video` / `users` 等业务数据。
2. 同一数据库事务调用 `VideoStatusServiceImpl.createEvent()` 写 `video_status_outbox`。
3. `VideoStatusOutboxRelay` 定时领取 Outbox，发布 RabbitMQ，确认成功后标记 `SENT`。
4. `VideoStatusEventConsumer` 收到消息，先向 `video_status_consumed_event` 登记事件。
5. 消费端调用 `VideoStatusServiceImpl.apply()`，通过 Lua 原子更新 Redis current、delta、dirty set、热度榜和 processed key。
6. `VideoStatusBatchFlushScheduler` 从消费事件表中选取 `REDIS_APPLIED_PENDING_FLUSH` 事件，按 vid 聚合增量并更新 MySQL。
7. 写入 `video_status_flush_batch` 后，再由清理任务按批次从 Redis delta 中做幂等减法。

因此建议把简历改为：

> 设计“事务性 Outbox + RabbitMQ 异步削峰 + Redis/Lua 实时聚合 + 消费事件表批量落库”的最终一致性链路。互动业务状态与 Outbox 在同一 MySQL 事务提交，Relay 可靠投递 MQ；消费端以事件表和语义哈希实现幂等登记，再通过 Lua 原子更新 Redis 实时统计，最终按视频维度聚合已消费事件写回 MySQL，并通过 flush batch、generation、脏数据恢复与缓存重建处理异常窗口。

这个版本与代码一致，也更能体现你真正解决的是“双写、重复投递、重复消费、落库与缓存清理”问题。

## 1. 项目介绍标准答案

### 1.1 30 秒版本

菲比啾比是我独立实现的 mini-Bilibili 前后端分离项目，后端使用 Java 17、Spring Boot 3、MyBatis、MySQL、Redis、Redisson、RabbitMQ 和腾讯云 COS。项目覆盖注册登录、JWT 鉴权、视频直传与投稿、审核状态机、视频 Feed、点赞投币收藏以及互动统计。最有挑战的是互动统计链路：我用本地事务 Outbox 保证业务状态和事件同时落库，用 RabbitMQ 削峰，通过消费事件表、语义哈希和 Redis processed key 做幂等，再按视频批量聚合写 MySQL，并设计了租约恢复、重试死信、Redis 重建和增量清理机制。

### 1.2 2 分钟版本

先按“业务、难点、方案、结果、边界”五段讲：

1. **业务**：用户投稿视频，经管理员审核后发布；用户可以播放、点赞、点踩、投币、收藏和分享；首页使用游标分页展示已发布视频。
2. **难点**：热门视频的互动是高频写。如果每次都直接更新 `video_status`，会形成热点行锁竞争；如果简单异步发 MQ，又存在数据库提交成功但消息丢失、消息重复以及 Redis 与 MySQL 不一致的问题。
3. **方案**：业务事务内写 Outbox；Relay 用数据库行锁和 lease token 抢任务，并等待 publisher confirm；消费者先登记 eventId，再执行 Redis Lua；数据库定时按 vid 聚合事件增量；清理 Redis 时不是清零，而是减去本批已经落库的增量。
4. **可靠性**：消息采用至少一次投递，重复由 eventId 唯一索引、载荷哈希和 Redis processed key 吸收；Relay 有指数退避和过期租约回收；消费失败分为可重试、不可重试和需要修复；Redis 丢数据时可由 DB 基线加未刷事件重建。
5. **边界**：这是单机学习项目，没有宣称生产级高可用或真实超高 QPS；密码目前仍为明文比较，是明确需要优先整改的安全欠账。

### 1.3 项目结构如何回答

- Controller：接收参数、读取拦截器写入的当前用户信息、返回统一响应。
- Service：承载事务和业务规则，例如投稿、审核、互动、认证、限流。
- Mapper + XML：MyBatis SQL、条件更新、批量状态流转、游标查询。
- DTO / VO / Converter：请求模型、响应模型和对象转换分离。
- Interceptor + Annotation：登录、可选登录、允许已注销 token、管理员权限。
- MQ / Scheduler：Outbox relay、消费、批量刷库、清理、恢复、重建。
- Lua：把多条 Redis 命令和条件判断合并为原子操作。

## 2. 核心项目深挖：互动统计链路

### 2.1 为什么不直接更新 MySQL

直接执行 `UPDATE video_status SET play_times = play_times + 1 WHERE vid = ?` 的问题：

- 同一热门视频对应同一行，更新需要获取行锁，热点流量会串行化。
- 每次互动都产生事务、redo log、binlog 和刷盘压力。
- 接口响应时间会直接受数据库抖动影响。
- 多种统计字段会形成大量细粒度写，难以批量合并。

项目的思路是将高频事件先可靠记录并异步处理，把多个事件合并成一次数据库增量更新。代价是放弃实时强一致，接受一个可控的最终一致窗口。

### 2.2 请求事务与 Outbox

代码依据：

- `UserVideoServiceImpl` 的互动方法带 `@Transactional`。
- 异步开关开启时，`recordStatus()` 调用 `videoStatusService.createEvent()`。
- `createEvent()` 使用 `Propagation.MANDATORY`，没有外层事务就直接报错。
- Outbox 的 `event_id` 有唯一索引。

`MANDATORY` 的价值是强制 Outbox 必须加入调用方已有事务。例如点赞时，`user_video.liked` 修改与 Outbox 插入要么一起提交，要么一起回滚，避免“点赞状态成功但统计事件没有创建”。

**常见追问：为什么不能事务里直接发 MQ？**

- 先发 MQ 再提交 DB：DB 回滚，消费者却已经处理消息。
- 先提交 DB 再发 MQ：进程在两步之间宕机，消息永久丢失。
- Outbox 把“业务变更”和“待发送事件”变成同库本地事务，随后通过可重试 relay 完成外部投递。

**必须会说的边界**：Outbox 解决的是本地数据库与消息系统之间的原子性，不代表 exactly-once；它通常提供 at-least-once，因此消费端必须幂等。

### 2.3 Relay 如何支持多实例

`VideoStatusOutboxClaimService.claimBatch()` 在事务内执行：

- 查询可发送记录，并使用 `FOR UPDATE` 类机制锁定候选行。
- 为每条记录生成唯一 `leaseToken`。
- 条件更新为 `SENDING` 并记录 `sendingAt`。
- 发布成功、失败、退回待重试时，都携带 lease token 做所有权校验。

`VideoStatusOutboxRelay` 每轮先回收超时 `SENDING` 记录，再领取新任务。发布失败采用指数退避，最大约 300 秒，累计 10 次后标记失败。

**宕机窗口分析**：

- 领取后、发送前宕机：租约过期后其他实例重新领取。
- Broker 已收到、Outbox 尚未标记 SENT 时宕机：会再次发送，形成重复消息，由消费端幂等处理。
- 标记 SENT 成功：正常结束。

**常见追问：为什么需要 lease token，只有状态不够吗？**

旧实例可能暂停很久后恢复。如果租约已过期且记录已被新实例领取，旧实例不能再把新实例的任务标成成功或失败。lease token 相当于 fencing token 的轻量所有权凭证，使更新只作用于自己领取的那一版任务。

### 2.4 RabbitMQ 可靠投递

代码中的交换机和队列：

- 主 DirectExchange + 主队列。
- Retry Exchange + TTL 10 秒的重试队列；到期后死信回主交换机。
- Dead Exchange + Dead Queue。
- 队列和交换机均持久化，消息使用持久化模式。
- 消费端手动 ACK，prefetch 为 20，当前并发消费者为 1。

Publisher confirm 只能说明消息到达交换机，不能天然保证一定路由到队列；面试时还要补充 mandatory return 或 alternate exchange 的概念。当前项目重点依靠既定 binding，生产环境应同时监控 returned message、unroutable、confirm timeout 和队列堆积。

**为什么选 RabbitMQ**：业务是可靠任务分发，强调 ACK、路由、重试与死信，吞吐量还没有达到必须使用 Kafka 的程度。Kafka 更适合高吞吐、可回放的事件日志；RabbitMQ 更贴合低延迟任务队列和灵活路由。

### 2.5 消费端三层幂等

第一层：`video_status_consumed_event.event_id` 唯一索引。

第二层：`VideoStatusEventFingerprintService` 生成 64 位语义哈希。发生 eventId 冲突时，不仅看 ID，还比较 vid、type、delta、payload hash：

- 同 ID 同语义：是正常重投，根据原状态继续或直接返回。
- 同 ID 不同语义：属于数据污染，拒绝处理，不能悄悄当重复消息吞掉。

第三层：Redis `processed:{eventId}` key。Lua 写统计前检查该 key，避免“Redis 已经成功，但数据库状态尚未更新”窗口中的重复入账。

事件状态大致为：

- `RECEIVED`：事件已登记，Redis 尚未确认应用。
- `REDIS_APPLIED_PENDING_FLUSH`：Redis 已应用，等待刷 MySQL。
- `FLUSHED`：已聚合落库。
- `REPAIR_REQUIRED`：无法自动安全恢复，需要修复。

**为什么登记使用 `REQUIRES_NEW`**：消费登记要独立提交。若后续 Redis 暂时失败，登记记录仍然存在，重投时可以从 `RECEIVED` 继续；若和整个消费过程共用一个事务，后续失败会回滚登记，丢失恢复依据。

**危险窗口题**：Redis Lua 已成功，但 `markRedisApplied()` 前宕机怎么办？

重投后数据库仍是 `RECEIVED`，消费者会尝试再次 apply；Lua 发现 processed key 已存在，返回 `DUPLICATE`，然后继续把数据库状态标成 Redis 已应用，所以不会重复计数。

### 2.6 Lua 原子脚本到底做了什么

`video-status-increment.lua` 一次完成：

1. 检查 current hash 和 delta hash 是否存在，否则要求 rebuild。
2. 校验 key 类型以及字段白名单。
3. 读取当前值、待刷增量和参数，确认数据完整。
4. 检查 processed event key。
5. 阻止计数更新后变为负数。
6. `HINCRBY` 更新实时 current。
7. `HINCRBY` 更新 pending delta。
8. `SADD` 把 vid 放入 dirty set。
9. `ZINCRBY` 更新热门视频分值。
10. 设置带 TTL 的 processed key。

为什么 Lua 优于在 Java 中连续调用命令：

- Redis 在执行脚本期间不会穿插执行其他命令，多步读写具备原子性。
- 一次网络往返，减少 RTT。
- 可以基于中间读取值做校验与分支。
- 所有校验都在第一条写命令之前，避免脚本返回错误时已经部分写入。

为什么不只用 `MULTI/EXEC`：事务能保证命令顺序执行，但不能方便地把中间结果拿回客户端再决定后续命令；`WATCH` 属于乐观锁，高冲突热点 key 会频繁重试。Lua 更适合这种“读取、判断、再多 key 写入”的短逻辑。

注意：Redis 原子性不等于数据库与 Redis 的跨系统原子性，也不等于脚本可以无限执行。Lua 必须保持短小，避免阻塞 Redis 主线程。

### 2.7 为什么还要 Redisson 分布式锁

`VideoStatusVidMutex` 按 vid 获取 `RLock`，等待最多 10 秒，不显式传 leaseTime，因此使用 Redisson watchdog 自动续期。它把同一视频的 apply、重建和关键状态操作串行化，防止“某线程正在重建 current/delta，另一线程同时写增量”造成基线覆盖。

需要区分：

- Lua 保证一次 Redis 脚本内部的原子性。
- 分布式锁保证跨多个 Redis/DB 操作组成的长流程互斥。

**Redisson 高频考点**：

- `SET key value NX PX ttl` 的 value 必须是唯一值，释放时要用 Lua 比较 value 后删除，避免误删别人的锁。
- watchdog 在持锁线程存活时周期续期，解决业务执行时间不确定的问题。
- Redisson 锁可重入，内部会记录线程标识和重入次数。
- 锁超时、网络分区、GC pause 仍可能带来风险；关键外部写需要 fencing token 或数据库条件更新兜底。
- 本项目没有宣称 Redlock 或多 Redis 节点强一致锁。

### 2.8 批量聚合为什么以消费事件表为依据

`VideoStatusBatchFlushServiceImpl.flushOneVideo()`：

1. 按 vid 查询待刷事件，使用 `FOR UPDATE SKIP LOCKED`，允许多个调度实例处理不同记录。
2. 将事件转换为 `VideoStatusDelta` 并求和。
3. 使用一条 `applyBatchDelta` 更新八个统计字段。
4. SQL 条件保证任一字段更新后不能小于 0。
5. 把这些消费事件标为 `FLUSHED`。
6. 同事务插入 `video_status_flush_batch`，记录 batchId、vid、generation 和各项 delta。

这里 MySQL 消费事件表是落库依据，Redis delta 是实时镜像和待清理状态。这样即使 Redis 丢失，也能通过数据库记录判断哪些事件尚未刷库。

**为什么 `SKIP LOCKED`**：没有它时，多实例可能等待同一批锁；使用后，被其他事务锁住的记录直接跳过，各实例可以并行处理不同事件。代价是需要持续调度，确保暂时跳过的数据之后仍会被处理。

### 2.9 为什么 Redis 清理用减法而不是 DEL/清零

假设刷库线程读到 delta=10，刷 MySQL 期间又来了 3 次互动，此时 Redis delta=13：

- 直接清零会把新增的 3 丢掉。
- 减去本批的 10，剩余 3，下一批继续处理。

`video-status-delta-subtract.lua` 还使用：

- `flush-cleaned:{batchId}` 防止同一批重复清理。
- `generation` 检测清理期间是否发生过 Redis 重建。
- 有剩余增量时保留 dirty vid；全部归零时移出 dirty set。
- delta hash 不删除，保留八个零字段，避免下一事件误判需要重建。

**最经典故障题：MySQL 已提交，Redis 减法失败怎么办？**

flush batch 已经持久化且标记待清理，清理调度可以重试；batchId 幂等 key 防止重复减。即便应用在 DB 提交后立刻宕机，也能从批次表恢复。

### 2.10 重建与自愈

面试回答应说明目标，而不是只报类名：

- current hash 丢失：以 MySQL `video_status` 为已落库基线。
- 尚未落库的事件：从消费事件表中查询 `RECEIVED` / `REDIS_APPLIED_PENDING_FLUSH` 候选并恢复。
- 重建时生成新的 generation，防止旧 flush batch 清理新一代 Redis 数据。
- dirty recovery 扫描长期停留状态，尝试自动重放；超过安全年龄的 `RECEIVED` 事件进入人工修复，避免无边界自动重放。

要会解释“为什么超龄事件不能永远自动重放”：processed key 有 TTL。若 TTL 已过，系统无法确认 Redis 当年是否已应用；贸然重放可能重复计数，所以宁可转人工修复，也不制造静默错误。

### 2.11 消息顺序问题

点赞 +1 与取消点赞 -1 是增量操作，加法在不触发非负约束时满足交换律，因此多数乱序最终仍可收敛。代码刷库时还优先选择正 delta，再选择负 delta，降低同一批次中先减导致非负校验失败的概率。

但不能笼统说“完全不需要顺序”：

- 用户行为表 `user_video` 保存最终状态，仍需要并发控制。
- 两个并发请求都基于旧状态判断时，可能产生业务竞争。
- 状态机类消息不能仅靠可交换增量解决，应按业务 key 分区、版本号或条件更新保证顺序。

## 3. JWT、登录与账号安全

### 3.1 JWT 结构和流程

JWT 通常由 Header、Payload、Signature 三部分组成。项目 token 中包含 userId、role、tokenVersion、jti、签发时间和过期时间，并通过服务端 secret 验签。

登录后请求经过 `LoginInterceptor`：

1. 检查 `Authorization: Bearer ...`。
2. 解析 JWT 并验证签名、过期时间和必要 claim。
3. 查询 Redis 黑名单。
4. 查询用户当前状态、role 和 token_version。
5. 将 `tokenContext`、`currentUserId`、`currentUserRole` 写入 request attribute。
6. 管理接口再经过 `AdminInterceptor` 判断 ADMIN / SUPERADMIN。

### 3.2 无状态 JWT 怎么立即退出

纯 JWT 签发后服务端无法主动撤回。本项目用 jti 黑名单补充状态：退出时把 jti 写 Redis，TTL 等于 token 剩余寿命。优点是立即失效且黑名单会随 token 自然过期，不会永久增长。

`@AllowRevokedToken` 用于允许已经进黑名单的 token 再次调用退出，使 logout 幂等。

### 3.3 改密后如何让所有设备退出

用户表有 `token_version`：

- 签发 token 时写入当前版本。
- 每次鉴权将 token 中版本与数据库版本比较。
- 改密时数据库版本加一，之前所有设备的 token 版本都落后，从而统一失效。

代价是每次鉴权需要查数据库，削弱了 JWT 完全无状态的性能优势。可优化为把用户认证状态缓存到 Redis，并设计更新与失效策略，但安全场景不能只依赖可能陈旧的本地缓存。

### 3.4 当前安全欠账必须诚实回答

`UserAccountServiceImpl` 当前直接保存并比较明文密码。面试时不要包装成“passwordHash”：

- 正确方案是 BCrypt、Argon2 或 PBKDF2 等带盐慢哈希，Java/Spring 常用 `BCryptPasswordEncoder`。
- 注册时保存 hash，登录用 `matches(raw, encoded)`。
- 不能使用 MD5/SHA-256 直接哈希密码，因为速度太快，容易暴力破解和彩虹表攻击。
- 存量迁移可在用户下一次成功登录时重新编码，也可增加 hash algorithm/version 字段。
- 改密接口应校验旧密码、更新 hash、递增 token_version，并考虑敏感操作审计。

还应准备：JWT secret 必须通过环境变量或密钥管理服务注入，不能提交到 Git；生产日志不能打印 token、密码或 STS 密钥。

### 3.5 固定窗口限流

`fixed-window-rate-limit.lua` 原子执行 `INCR`，第一次请求或发现 key 无 TTL 时设置 `EXPIRE`。项目用于：

- 登录失败计数。
- COS 临时凭证申请频率限制。

固定窗口缺点是边界突刺：窗口结束前打满 N 次，下一窗口开始又打满 N 次，短时间可能达到 2N。

算法对比：

- 固定窗口：简单、省内存，适合登录失败等低精度防刷。
- 滑动窗口日志：ZSet 保存请求时间，精确但内存和操作成本较高。
- 滑动窗口计数：多个小窗口近似统计，精度与成本折中。
- 令牌桶：允许一定突发，长期速率稳定。
- 漏桶：输出速率平滑，不适合需要合理突发的接口。

当前登录限流按 username，攻击者可轮换用户名，也可能恶意锁定他人账号。生产中通常组合账号、IP、设备指纹和全局策略，并区分“请求限流”与“认证失败锁定”。

## 4. COS 大文件直传

### 4.1 当前流程

1. 用户请求上传准备接口。
2. 后端校验文件名、类型、大小并执行限流。
3. 生成限定到 `temp/videos/{uid}/...` 的 object key。
4. 通过腾讯云 STS 返回临时 SecretId、SecretKey、SessionToken 和有效期。
5. 后端写入 `upload_temp_file`，记录 uid、key、类型、大小、状态和过期时间。
6. 前端直接把视频上传到 COS，不经过 Spring Boot。
7. 投稿时后端检查临时记录归属、类型、状态、过期时间，并调用 COS 检查对象存在。
8. 将临时对象复制到正式目录，创建 video 和 video_status，再把临时记录标为已提交。

### 4.2 为什么不用后端中转

- 2 GB 视频会长时间占用业务服务器连接、带宽和线程。
- Multipart 中转可能产生内存、临时磁盘和网关超时压力。
- 水平扩容业务服务也被文件带宽成本牵制。
- 直传让数据面走客户端到对象存储，后端只负责控制面鉴权和元数据。

### 4.3 STS 安全考点

- 临时凭证必须短时有效。
- 权限遵循最小权限，只允许指定 bucket、路径和操作。
- object key 必须由后端生成，包含用户隔离目录，不能完全信任客户端路径。
- 服务端投稿时仍要校验对象归属、大小、类型和存在性；客户端声明的 Content-Type 不能作为唯一安全依据。
- 视频内容还应有转码、病毒扫描、内容审核和元数据探测流程。
- Bucket 应避免匿名写；播放可使用 CDN、签名 URL 或防盗链策略。

### 4.4 投稿事务与对象存储的一致性

MySQL 事务无法回滚 COS 操作。当前实现先复制 COS，再写数据库；运行时异常会尽力删除已复制的正式对象。这个属于补偿事务思路，但 `delete...Quietly` 失败只记录日志，仍可能留下孤儿文件。

生产改进：

- 投稿先写状态为 `PROCESSING` 的数据库任务，再异步复制/转码，成功后改为 `PENDING_REVIEW`。
- 为对象清理建立持久化任务表和重试机制，而非只依赖 catch 中的一次删除。
- 定时扫描长期未提交临时文件和无数据库引用的正式对象。
- 使用幂等 object key 和任务状态机处理重复投稿。

## 5. 视频审核、删除和分页

### 5.1 审核状态机与乐观并发控制

`VideoReviewStatus.canTransitionTo()` 控制合法状态迁移。更新 SQL 带旧状态：

```sql
UPDATE video
SET status = :newStatus
WHERE vid = :vid
  AND status = :oldStatus
  AND deleted_at IS NULL;
```

两个管理员同时审核时，只有第一个更新成功；第二个影响行数为 0，返回 409。这里不是显式 version 字段，但本质是 compare-and-set 式乐观锁。

悲观锁与乐观锁对比：

- 悲观锁 `SELECT ... FOR UPDATE`：先锁后改，适合冲突频繁、事务短的场景。
- 乐观锁：更新时校验 version 或旧状态，适合读多写少、冲突少；冲突后由调用方重试或刷新。

### 5.2 游标分页

Feed SQL 使用 `(created_at, vid)` 复合排序：

```sql
WHERE created_at < :cursorTime
   OR (created_at = :cursorTime AND vid < :cursorVid)
ORDER BY created_at DESC, vid DESC
LIMIT :size;
```

为什么 vid 必须作为第二排序键：created_at 可能相同，只使用时间会漏掉或重复同一时间点的数据。

为什么比 offset 好：大 offset 需要扫描并丢弃前面的行；keyset pagination 可从索引位置继续，越翻页性能越稳定，对前面新插入的数据也更不容易重复。

建议索引：根据过滤条件设计类似 `(status, visibility, deleted_at, created_at DESC, vid DESC)`，分类 Feed 可评估把 `mc_id/sc_id` 放到更靠前的位置。是否命中必须用 `EXPLAIN ANALYZE` 验证，不能只凭感觉宣称。

当前游标是明文 `LocalDateTime_vid`，可继续改为 Base64 编码的结构化 JSON，并把非法游标返回 400，而不是当前部分路径中的 500。

### 5.3 逻辑删除与事务提交后清理

`deleteVideo()` 先按 uid 和 deleted_at 条件软删除。事务提交后通过 `TransactionSynchronization.afterCommit()` 删除 COS 文件：

- 若数据库回滚，不应提前删掉仍被数据库引用的文件。
- 若数据库提交成功但 COS 删除失败，数据库状态仍正确，但会有孤儿对象，需要后台补偿清理。

逻辑删除优点是可恢复、可审计；缺点是所有查询都必须带删除条件，唯一索引设计和数据归档也更复杂。

## 6. Redis 缓存基础与项目结合

### 6.1 Category Cache-Aside

`CategoryServiceImpl`：先读 Redis JSON，未命中查 MySQL、组装树并写回缓存。这是典型 Cache-Aside。

当前风险：热点 key 失效时多个请求会同时回源，存在缓存击穿。可采用：

- Redisson 互斥锁，只允许一个线程重建。
- 逻辑过期，后台异步刷新，读请求暂时返回旧数据。
- 分类数据很少变化时设置较长 TTL，并在后台修改分类后主动删除缓存。

### 6.2 穿透、击穿、雪崩

- 穿透：反复查询根本不存在的数据，缓存永远 miss。方案为空值缓存、布隆过滤器、参数校验。
- 击穿：单个热点 key 失效，大量请求同时回源。方案为互斥重建、逻辑过期、热点不过期。
- 雪崩：大量 key 同时失效或 Redis 整体不可用。方案为 TTL 随机抖动、多级缓存、限流降级、高可用部署。

### 6.3 Redis 数据结构考点

- Hash：一个视频的多个统计字段聚合在一个 key 内，支持 `HINCRBY`。
- Set：dirty vid 去重，一个视频再多事件也只保留一个成员。
- ZSet：热门视频以热度为 score，可按分值排序。
- String：JWT 黑名单、processed event、限流计数器。

### 6.4 Redis 持久化和高可用

Compose 当前 Redis 开启 AOF。准备以下区别：

- RDB：定时快照，文件紧凑、恢复快，但可能丢失最后一次快照后的数据。
- AOF：记录写命令，数据丢失窗口更小，但文件更大、恢复可能更慢。
- 混合持久化：结合 RDB 快速加载和 AOF 增量。
- 主从复制解决读扩展和副本；Sentinel 负责监控与故障转移；Cluster 通过槽位分片扩容。

项目当前只是单 Redis 容器，不能在简历中写成 Redis 高可用集群。

## 7. MySQL 与 MyBatis 必备八股

### 7.1 InnoDB 事务 ACID

- 原子性：undo log 支持回滚。
- 一致性：由原子性、隔离性、持久性和业务约束共同保证。
- 隔离性：锁和 MVCC 隔离并发事务。
- 持久性：redo log 和刷盘策略保证提交后恢复。

### 7.2 MVCC

InnoDB 通过隐藏事务 ID、回滚指针、undo log 版本链和 Read View 实现一致性读。RC 通常每条语句生成 Read View；RR 通常事务第一次一致性读生成后复用，因此可重复读。

当前读、快照读：普通 SELECT 多为快照读；`SELECT ... FOR UPDATE` 是当前读，要读取最新已提交版本并加锁。

### 7.3 隔离级别和并发异常

- Read Uncommitted：可能脏读、不可重复读、幻读。
- Read Committed：避免脏读，仍可能不可重复读和幻读。
- Repeatable Read：MySQL 默认，MVCC + next-key lock 可在很多场景避免幻读。
- Serializable：隔离最强，并发能力最低。

### 7.4 索引必考

- InnoDB 主键索引叶子节点保存整行数据，二级索引叶子保存主键值。
- 回表：先从二级索引找到主键，再查聚簇索引。
- 覆盖索引：查询字段都在索引中，无需回表。
- 最左前缀：联合索引从左到右匹配；范围条件后的列通常难以继续用于定位。
- 索引下推：存储引擎层先按索引中可用条件过滤，减少回表。
- 索引失效常见原因：对索引列做函数、隐式类型转换、前导模糊匹配、低选择性及不符合最左前缀。

结合项目至少准备这些表：`video` Feed 索引、Outbox 轮询索引、消费事件 pending/recovery 索引、`upload_temp_file` 的 uid/status/expire 组合索引以及各 eventId 唯一索引。

### 7.5 锁

- Record Lock：锁索引记录。
- Gap Lock：锁索引间隙，防止范围内插入。
- Next-Key Lock：记录锁 + 间隙锁。
- 意向锁：表级标识某事务准备对行加共享/排他锁。
- 死锁：事务形成循环等待；数据库检测后回滚其中一个事务。应用应缩短事务、统一加锁顺序并对可重试死锁做有限重试。

### 7.6 Spring 事务常考

- `@Transactional` 基于 AOP 代理，自调用绕过代理时可能不生效。
- 默认只对 RuntimeException 和 Error 回滚；项目多处显式 `rollbackFor = Exception.class`。
- 方法必须通常是可代理的 public 方法。
- 事务边界应放在 Service，不应把耗时网络调用长期包在数据库事务里。
- `REQUIRED`：加入当前事务，没有则新建。
- `REQUIRES_NEW`：挂起外层事务，创建独立事务。
- `MANDATORY`：必须存在外层事务，否则异常。

### 7.7 MyBatis

- `#{}` 使用 PreparedStatement 参数，占位绑定，可防 SQL 注入。
- `${}` 是字符串直接拼接，仅可用于严格白名单控制的表名、列名、排序方向。
- 一级缓存默认是 SqlSession 级；二级缓存是 namespace 级，需要显式配置，分布式和一致性场景要谨慎。
- `resultMap` 适合复杂字段映射；项目也启用了下划线转驼峰。
- N+1 问题：循环里逐条查关联数据会放大 SQL 次数，应使用 JOIN、批量 IN 或预加载。
- PageHelper 本质上通过 MyBatis 插件拦截 SQL 并追加分页语句。

## 8. Java 基础八股清单

### 8.1 集合

必须掌握：

- ArrayList 扩容、随机访问、插入删除复杂度。
- LinkedList 的节点结构与为什么实际业务中经常不如 ArrayList。
- HashMap 数组 + 链表 + 红黑树、负载因子、扩容、hash 扰动、树化条件、允许 null、线程不安全。
- ConcurrentHashMap 在 Java 8 中使用 CAS + synchronized 控制桶级并发，不再使用旧版 Segment。
- CopyOnWriteArrayList 读多写少，写时复制，迭代器是快照。
- HashSet 底层基于 HashMap。

高频问题：为什么重写 `equals()` 必须重写 `hashCode()`；可变对象为什么不适合作为 HashMap key。

### 8.2 JVM

必须掌握：

- 运行时数据区：堆、虚拟机栈、本地方法栈、程序计数器、方法区/元空间。
- 对象创建：类加载检查、分配内存、零值初始化、对象头、构造方法。
- 对象头：Mark Word、类型指针、数组长度。
- 可达性分析和 GC Roots。
- 强、软、弱、虚引用。
- 新生代复制、老年代标记整理等基本算法。
- G1 的 Region、Remembered Set、Mixed GC、停顿目标；了解 ZGC 的低停顿定位。
- 类加载：加载、验证、准备、解析、初始化；双亲委派及其意义。
- OOM 与 StackOverflowError 的区别；用 jstack、jmap、jcmd、JFR 排查的基本路径。

### 8.3 并发

必须掌握：

- 进程、线程、协程的区别。
- Java 内存模型：主内存、工作内存、可见性、有序性、原子性。
- happens-before 规则。
- `volatile` 保证可见性和有序性，不保证复合操作原子性。
- `synchronized` 的对象锁、类锁、可重入和 monitor。
- ReentrantLock 的可中断、公平锁、Condition、显式释放。
- CAS、ABA 问题、AtomicStampedReference。
- AQS 的 state、同步队列、独占/共享模式。
- ThreadLocal 的 ThreadLocalMap、弱引用 key、内存泄漏风险以及线程池中必须 remove。
- 线程池七参数、执行流程、拒绝策略；不建议无界队列和 `Executors.newFixedThreadPool()` 的原因。
- CompletableFuture 的组合与异常处理。

结合项目：RabbitMQ listener 的并发数、prefetch、手动 ACK；`@Scheduled` 是否可能重叠；Redisson watchdog；数据库锁与分布式锁各自保护什么。

### 8.4 Java 语言细节

- `==` 与 `equals()`。
- String 不可变、常量池、`StringBuilder` 与 `StringBuffer`。
- 泛型擦除、上界下界、PECS。
- checked / unchecked exception。
- 反射与注解，Spring 如何扫描和创建 Bean。
- record 的不可变数据载体特性，项目事件类可联系讲解。
- Java 17 常用特性及为什么使用 LTS。

## 9. Spring Boot / Spring MVC 八股

### 9.1 IoC 与 AOP

- IoC：对象创建和依赖关系交给容器管理；依赖注入是实现方式。
- Bean 生命周期：实例化、属性注入、Aware、前置处理、初始化、后置处理、销毁。
- Spring 默认单例 Bean 不等于线程安全；Service 尽量无状态。
- AOP 通过代理织入事务、日志、鉴权等横切逻辑。
- JDK 动态代理基于接口，CGLIB 基于子类；final 类/方法存在代理限制。

### 9.2 MVC 请求流程

请求进入 DispatcherServlet，查找 HandlerMapping，执行拦截器 preHandle，调用 HandlerAdapter 和 Controller，参数解析与校验后进入 Service，返回值经 HttpMessageConverter 序列化，异常由 `@ControllerAdvice` 处理。

项目可讲：`WebMvcConfig` 注册 LoginInterceptor 和 AdminInterceptor，自定义注解改变鉴权行为，`GlobalExceptionHandler` 统一包装业务异常。

### 9.3 Spring Boot 自动配置

核心是条件化配置：starter 引入依赖，自动配置类通过 `@ConditionalOnClass`、`@ConditionalOnMissingBean`、配置属性绑定等条件创建默认 Bean，用户自定义 Bean 可以覆盖部分默认行为。

### 9.4 当前响应设计的争议

项目业务错误多数 HTTP 状态仍返回 200，只在 body 中放业务 code。面试时应承认这是一种前后端约定，但更符合 HTTP 语义的做法是：参数错误 400、未认证 401、无权限 403、资源不存在 404、冲突 409、限流 429、服务异常 500，同时 body 保留稳定业务错误码。

## 10. RabbitMQ 基础八股

必须准备：

- Producer、Exchange、Binding、Queue、Consumer。
- Direct、Topic、Fanout、Headers 交换机区别。
- 消息持久化需要交换机持久、队列持久、消息持久三个条件，但仍要结合 publisher confirm。
- confirm 与 return 的区别。
- ACK、NACK、reject；requeue true 可能造成热循环。
- prefetch 控制未确认消息数量，影响吞吐与公平性。
- 死信来源：reject/nack 且不 requeue、TTL 到期、队列超长。
- 延迟消息可用 TTL + DLX，但不同 TTL 混在同一队列可能有队头阻塞；更灵活可用延迟消息插件。
- 至少一次、至多一次、恰好一次的含义；跨系统 exactly-once 通常转化为 at-least-once + 幂等。
- 消息积压处理：先定位生产/消费速率和失败原因，再扩容消费者、临时分流、批量消费，同时保护下游数据库。

## 11. Docker、Linux、Git 基础

### 11.1 Docker

- 镜像是只读分层模板，容器是镜像的运行实例。
- Dockerfile 分层缓存；先复制依赖描述再下载依赖，可提升构建缓存命中率。
- volume 用于持久数据，bind mount 映射宿主机目录。
- Compose 用一个文件描述多服务、网络、端口、卷、环境变量和健康检查。
- 项目 Compose 仅部署 Redis 与 RabbitMQ，Redis 开 AOF，两个服务端口只绑定 `127.0.0.1`。
- `depends_on` 不等于业务真正 ready，需要 healthcheck 或应用重试。

### 11.2 Linux 常用命令

至少能现场解释：`ps`、`top`、`free`、`df`、`du`、`ss`、`lsof`、`curl`、`tail -f`、`grep`、`awk`、`sed`、`find`、`chmod`、`chown`、`systemctl`、`journalctl`、`kill`。排障流程要会从 CPU、内存、磁盘、端口、日志、线程和依赖服务逐层定位。

### 11.3 Git

- working tree、staging area、repository。
- merge 保留分支历史，rebase 重放提交形成线性历史。
- `reset` 移动分支指针，`revert` 创建反向提交，公共分支优先 revert。
- 冲突解决后要重新 add 并继续 merge/rebase。
- 会使用 `status`、`diff`、`log`、`show`、`branch`、`switch`、`restore`、`stash`、`cherry-pick`、`reflog`。

## 12. 面试官可能连续追问的问题

以下问题要练到能脱稿回答，每题先给结论，再给代码依据和取舍。

1. 你的项目最难的部分是什么？为什么难？
2. 当前互动链路的准确时序是什么？
3. 为什么是 Outbox 先于 Redis，而不是请求线程直接写 Redis？
4. `Propagation.MANDATORY` 在这里解决什么问题？
5. Outbox 能保证消息绝不丢失吗？有哪些前提？
6. Publisher confirm 成功代表什么？能否证明消息已经进入目标队列？
7. Relay 在确认成功但标记 SENT 前宕机会怎样？
8. 两个应用实例如何避免同时发送同一 Outbox？
9. lease token 与普通分布式锁有什么区别？
10. 为什么消费端要先登记事件，再写 Redis？
11. 为什么登记事务用 `REQUIRES_NEW`？
12. eventId 唯一索引已经存在，为什么还要 payload hash？
13. Redis processed key 为什么要设置 TTL？TTL 到期后怎么办？
14. Lua 已成功、数据库状态更新失败会不会重复计数？
15. 为什么 current 和 delta 都要维护？
16. 为什么 dirty set 用 Set，不用 List？
17. 为什么热点榜使用 ZSet？
18. 为什么 Redis key 丢失时先 rebuild 再重试？
19. Lua 已经原子，为什么还需要 Redisson 锁？
20. watchdog 如何工作？业务线程永久卡死怎么办？
21. 批量刷库为什么不直接读取 Redis delta？
22. `FOR UPDATE SKIP LOCKED` 的作用和风险是什么？
23. 落库期间又有新互动，为什么不会被清掉？
24. flush batch 表解决哪个宕机窗口？
25. generation 为什么能阻止旧批次污染新缓存？
26. +1/-1 消息乱序是否一定安全？
27. 同一用户并发点赞两次，业务状态是否可能竞争？如何改进？
28. Redis 宕机时接口如何表现？是否有降级策略？
29. RabbitMQ 宕机后 Outbox 会如何积压和恢复？
30. 消费速度跟不上时怎样扩容？同 vid 锁会带来什么瓶颈？
31. 为什么 JWT 还要查数据库，它还是无状态吗？
32. 黑名单 Redis 丢失会发生什么？
33. token_version 和 jti 黑名单分别解决什么问题？
34. 当前密码存储有什么问题，如何无停机迁移？
35. 固定窗口为什么有边界突刺？
36. STS 临时密钥如何限制用户只能写自己的目录？
37. 客户端伪造 Content-Type 怎么办？
38. COS 复制成功、DB 事务失败会怎样？补偿失败又怎么办？
39. 两个管理员同时审核为什么只有一个成功？
40. 游标分页为何需要 `(created_at, vid)` 两列？
41. 首页 Feed 当前读的是 MySQL 统计还是 Redis 统计？有什么一致性表现？
42. 分类缓存失效时有没有击穿问题？
43. 为什么业务异常返回 HTTP 200 有争议？
44. 项目有没有做过压测？没有数据时如何诚实回答？
45. 项目当前离生产环境还差哪些部分？

## 13. 高频场景题答案框架

### 13.1 Redis 和 MySQL 数据不一致怎么排查

1. 确认是 current、delta、dirty set 还是 MySQL 基线异常。
2. 按 eventId 检查 Outbox 状态、RabbitMQ 重试头、消费事件状态。
3. 检查对应 vid 的 flush batch 和 cleanup 状态。
4. 比较 Redis generation，判断是否发生过重建。
5. 不直接手改某一个计数；先冻结该 vid 写入或持有 vid mutex，再基于 MySQL 基线和未刷事件重建。
6. 记录 repair operation，保证修复本身可审计、可幂等。

### 13.2 RabbitMQ 消息积压怎么处理

1. 看 ready、unacked、publish rate、ack rate 和消费者异常日志。
2. 判断是消费者实例不足、单消息变慢、Redis/MySQL 下游瓶颈还是毒消息重试。
3. 隔离毒消息，避免不断进入 retry。
4. 在下游承载范围内提高消费者并发；本项目同 vid 有锁，热门单 vid 无法靠无限加线程线性扩展。
5. 临时扩大批量落库吞吐并监控数据库锁等待。
6. 积压清空后复盘容量、报警阈值和降级策略。

### 13.3 接口突然变慢怎么定位

按入口到依赖排查：网关/网络 → Tomcat 线程池 → JVM GC → 应用慢方法 → MySQL 慢 SQL/锁 → Redis 延迟 → RabbitMQ/COS 外部调用。使用请求 traceId、P95/P99、线程 dump、GC 日志、连接池指标、慢查询日志和依赖监控，不只看平均响应时间。

## 14. 简历中不要夸大的内容

- 不写“支持百万 QPS”，除非有可复现压测报告。
- 不写“强一致”或“恰好一次”，当前是至少一次投递加幂等、最终一致。
- 不写“Redis 集群、RabbitMQ 集群、MySQL 主从”，Compose 是单实例。
- 不写“密码安全存储已经完成”，当前是明文。
- 不写“完整视频处理平台”，目前主要是直传、投稿和审核，没有完整转码流水线。
- 不说所有读取都走 Redis；例如视频详情当前直接通过 `videoStatusMapper.selectByVid()` 读取 MySQL，可能看到短暂延迟数据。
- 不把 docs 中的未来设计自动算作已实现功能，回答时必须能指出真实类、方法、表或 Lua。

## 15. 建议学习优先级

### 第一优先级：必须能答顺

1. 项目 30 秒和 2 分钟介绍。
2. 互动统计完整时序与全部宕机窗口。
3. Outbox、至少一次、消费幂等、ACK/confirm/DLX。
4. Redis Lua、Redisson 锁、缓存三大问题。
5. Spring 事务传播和失效场景。
6. MySQL 索引、MVCC、锁、游标分页。
7. JWT 黑名单、token_version、密码哈希。
8. COS STS 直传与跨系统补偿。

### 第二优先级：Java 后端基础盘

1. HashMap / ConcurrentHashMap。
2. synchronized / volatile / CAS / AQS / ThreadLocal。
3. 线程池。
4. JVM 内存结构、GC、类加载。
5. Spring IoC/AOP/MVC/自动配置。
6. MyBatis 参数绑定、缓存、插件和 N+1。

### 第三优先级：工程与运维

1. Docker/Compose、网络、卷、健康检查。
2. Linux 排障命令。
3. Git merge/rebase/reset/revert。
4. 日志、指标、链路追踪、压测和容量评估。

## 16. 七天冲刺安排

### Day 1：项目叙事

背熟项目介绍，手画整体架构和互动时序图。要求能从一次点赞讲到最终落库。

### Day 2：Redis + Redisson

逐行讲两个 Lua 脚本，复习数据结构、缓存问题、持久化、主从/哨兵/Cluster 和分布式锁。

### Day 3：RabbitMQ + 一致性

准备 confirm、return、ACK、prefetch、DLX、Outbox、幂等、重试、消息顺序和积压处理。

### Day 4：MySQL + 事务

复习索引、MVCC、锁、隔离级别、redo/undo/binlog、Spring 事务传播。对项目关键 SQL 做 EXPLAIN。

### Day 5：Java + JVM + 并发

集合、线程池、JMM、锁、CAS/AQS、ThreadLocal、内存区、GC、类加载。

### Day 6：认证、COS、Spring

JWT、密码哈希、限流、STS、补偿事务、IoC/AOP/MVC/自动配置/MyBatis。

### Day 7：模拟面试

随机抽取第 12 节的 45 道题，每题控制在 1 到 3 分钟。所有回答遵循：结论 → 代码依据 → 为什么这样设计 → 失败场景 → 当前边界/改进。

## 17. 最终检查表

- 能准确说出当前链路是 Outbox → MQ → Redis → MySQL，而不是 Redis → Outbox。
- 能画出 request、outbox、relay、consumer、Redis、flush、cleanup 七个阶段。
- 能解释三层幂等和两个最危险宕机窗口。
- 能说明 Lua 与 Redisson 锁各自负责的原子性范围。
- 能说明减法清理、batchId 和 generation。
- 能诚实指出密码明文、单点部署、无压测数字、COS 补偿不持久等不足。
- 每个简历亮点至少能对应到一个类、一个表或一个脚本。
- 不背名词堆砌，回答中始终包含问题、取舍和边界。
