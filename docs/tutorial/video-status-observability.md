# 视频统计链路最小可观测能力（T05）

> 状态：T05 已由父代理独立验收并合入（实现提交 `46af28b`，验收日期 2026-10-22）。
> 本文只描述本实例指标 + 共享数据库积压的观测方式，不代表已批准生产上线。
>
> 范围：互动事件 → Outbox → RabbitMQ → Redis → 消费事件 → 批量落库 → 重建/恢复。
> 观测<b>只读</b>：不写 Redis、不改事件状态、不为门闩加 TTL、不降低任何幂等语义。

## 1. 为什么不用现成监控栈

项目没有 Micrometer/actuator 监控端点，也明确不暴露未鉴权的 `/actuator/**`。
T05 的目标是“故障后能定位”，不是一次建整套监控平台，因此采用：

- **JDK 自带的有限枚举计数器**（`LongAdder` / `AtomicLong`），无新依赖、无侵入；
- **只读 SQL 采样**回答“积压多少、最老等了多久”这类必须看共享库的问题；
- **管理员只读 HTTP 接口**输出快照，走既有登录 + `@AdminOnly` 拦截器。

## 2. 组件与职责

| 组件 | 位置 | 职责 |
|---|---|---|
| `VideoStatusTelemetry` | `metrics/` | 本实例 operation × outcome 计数、耗时、心跳、端到端延迟 |
| `VideoStatusBacklogSampler` | `metrics/` | 每 15s 只读采样共享库积压，产出告警与可信度状态 |
| `VideoStatusMetricsMapper` + XML | `metrics/` | 只读 SQL：封顶计数、最老年龄、索引存在性 |
| `VideoStatusMetricsConfig` | `config/` | 独立单线程 metrics 调度器（daemon，固定线程名） |
| `VideoStatusMetricsController` | `controller/` | `GET /api/admin/video-status/metrics`、`GET /api/admin/video-status/gate` |
| `VideoStatusMetricsVO` / `VideoStatusGateVO` | `vo/` | 响应结构（含只读边界说明） |
| 索引迁移 | `database/migrations/t05-video-status-observability-indexes.sql` | 补观测所需索引；`database/feibijiubi.sql` 同步新库初始化 |

`VideoStatusTelemetry` 与 `VideoStatusBacklogSampler` 都提供“生产构造 + 测试构造”两个构造器，
生产构造显式标注 `@Autowired`（否则 Spring 找不到无参构造会启动失败）。
`VideoStatusMetricsComponentWiringTests` 用 `ApplicationContextRunner` + mock mapper/executor
走真实构造器注入，不连接任何基础设施，也不启动自动采样线程。

## 3. 指标语义：只有有限枚举 key

```java
public enum Operation { PUBLISH, FLUSH, CONSUME, RETRY_FORWARD, DEAD_FORWARD,
                        LOCK, REBUILD, RELAY_TICK, FLUSH_TICK, RECOVERY_TICK }
public enum Outcome   { SUCCESS, FAILURE, DEFERRED, TIMEOUT }
```

共享 API（埋点方只能调用这四个方法，签名不得变更）：

```java
void record(Operation operation, Outcome outcome, long elapsedNanos);
void heartbeat(Operation operation);
void recordFlushedPayload(String payload); // 解析 occurredAt，事务提交后才调用
Snapshot snapshot();
```

要点：

1. **key 数量固定**：10 个 operation × 4 个 outcome，永远不会因为视频数增长而爆炸。
   `eventId` / `vid` 只允许出现在日志或按 id 查询的接口里，**不能做 label**。
2. `record` 的耗时是**本地** measured 时间；负值按 0 处理，不产生负延迟。
3. `heartbeat` 记录“本轮调度跑过”，是区分“没有数据”和“调度已停”的唯一手段。
4. `recordFlushedPayload` 解析 payload 里的 `occurredAt`（ISO 字符串、epoch 毫秒或
   Jackson 数组格式均可），算出“事件创建 → 落库提交”的毫秒数；
   **只有事务提交成功后才能调用**，否则会把回滚事件算进成功延迟。
   解析失败只累加 `unknown` 计数，不猜、不返回假 0。
   本地时间（业务侧 `LocalDateTime.now()` 产生的字符串/数组）按注入 `Clock` 的时区解析；
   默认时钟是 `Clock.systemDefaultZone()`，与主机时区一致。若错误地固定成 UTC，
   `+08:00` 主机会得到 8 小时偏差，并被“负值按 0”伪装成健康延迟。
5. `snapshot()` 返回 10 个 operation 的完整结构；没有数据时计数为 0、
   端到端 `avg/max/last` 为 `null`——区分“确实是 0”与“没有样本”。

本实例语义：这些计数**只是本实例**的 operation 计数。多实例部署时不要相加，
也不要用它们去校验全局积压。

## 4. 积压采样：封顶、索引、15 秒

### 4.1 为什么必须封顶

`video_status_outbox` / `video_status_consumed_event` 会无限增长。无界
`COUNT(*)` 每次抓取都要扫全表，抓取越频繁越像 DDoS。因此：

```sql
SELECT COUNT(*) FROM (SELECT 1 FROM video_status_outbox WHERE status = ? LIMIT 10001) capped
```

返回 `10001` 的语义是 **`count >= 10001`**（接口同时给出 `countCapped=true`），
不再追求精确值；真实数字应该去排查积压原因，而不是继续加大 COUNT。

### 4.2 最老时间必须按真实时间排序

```sql
SELECT CAST(TIMESTAMPDIFF(MICROSECOND, created_at, NOW(3)) / 1000 AS SIGNED)
FROM video_status_outbox
WHERE status = ?
ORDER BY created_at, id
LIMIT 1
```

- 不能 `ORDER BY id` 假冒最老：回填/重放/事务提交顺序都会让 id 与时间错序；
- 年龄由数据库 `NOW(3)` 计算，避免应用与数据库时钟/时区差异；
- 索引依赖：`(status, created_at, id)` 与 `(process_status, consumed_at, id)`，
  分别由 `idx_video_status_outbox_created`、`idx_video_status_consumed_created` 提供。

### 4.3 旧库没迁移时：诊断，不扫表

采样先查 `information_schema.statistics`：

- 两个索引都在 → 正常计数；
- 缺任意一个 → 状态置 `DEGRADED`，返回“缺哪个索引 + 执行哪个 migration”的诊断，
  **跳过本次计数查询**，保留上次成功数值并标记 stale。

因此升级顺序是：先执行 migration（在线 DDL，建议低峰），再发布/重启应用；
即使顺序反了，也只会看到明确诊断，而不是一条拖垮库的全表扫描。

### 4.4 采样节奏与线程隔离

- 固定延迟 15 秒，首次延迟 5 秒；
- 跑在 `VideoStatusMetricsConfig` 提供的**独立单线程** daemon 调度器上，
  与发布/落库/恢复线程完全分离：发布 confirm 把业务线程卡住时，监控仍在心跳；
- 只有采样线程查库。`GET /metrics` 只读缓存快照，非采样时刻**没有额外查询**，
  抓取频率再高也不放大数据库压力；
- 每条 SQL 都带 `timeout`（3 秒），采样卡住时失败并记录 stale，不拖住监控线程。

### 4.5 采样覆盖的状态

| 表 | 状态 | 语义 |
|---|---|---|
| `video_status_outbox` | 0 / 1 / 2 / 3 | PENDING / SENDING / SENT / FAILED（全部状态都要有读数，`SENT` 不能漏） |
| `video_status_consumed_event` | 0 / 1 / 3 | RECEIVED / REDIS_APPLIED_PENDING_FLUSH / REPAIR_REQUIRED |

consumed 的 `2 (FLUSHED)` 是已完成量而不是积压，当前不采样（不要求计数）。
`SENT` 只用于确认投递量/对账，不参与 `PENDING_TOO_OLD` 告警（该告警只看 0/1）。

## 5. 状态与告警

### 5.1 可信度状态

| state | 含义 | 数值 |
|---|---|---|
| `OK` | 本次采样成功 | 可信 |
| `STALE` | 本次失败或快照超过 45s 未更新，保留上次成功值 | `stale=true`，`lastSuccessAtEpochMillis` 为上次成功时间 |
| `UNKNOWN` | 从未成功过 | 计数为 `null`，**不是 0** |
| `DEGRADED` | 缺观测索引，主动跳过计数 | 诊断说明 + 上次成功值（如有） |

`count=null` 表示未知，`count=0` 才表示数据库里确实为空。
最老年龄 `oldestAgeMillis=null` 表示“没有待处理行”或“未知”，同样不能当 0 读。

快照保存最近一次**尝试**（成功、失败或 DEGRADED），而不是只保存 lastSuccess。
`GET /metrics` 读取时会按当前时钟判断：距离 `sampledAtEpochMillis` 超过 **45 秒**
（连续 3 个采样周期）仍没有新尝试时，`state` 从 `OK` 降为 `STALE` 并给出说明，
同时用本实例遥测重算调度心跳告警。因此采样线程停摆不会表现为“接口永远 OK”，
而读取快照仍然**不查数据库**。

### 5.2 告警阈值（测试阈值，生产阈值由 T16 确认）

| code | 条件 | 级别 |
|---|---|---|
| `PENDING_TOO_OLD` | outbox status 0/1 或 consumed status 0/1 最老 > **120s**（严格大于） | WARN |
| `BACKLOG_CAPPED` | 任一状态计数 `>= 10001` | WARN |
| `OUTBOX_FAILED` | outbox status 3 (FAILED) 数量 > 0 | ALERT |
| `REPAIR_REQUIRED` | consumed status 3 (REPAIR_REQUIRED) 数量 > 0 | ALERT |
| `SCHEDULER_STALLED` | 心跳年龄 > **30s**；或角色已开启但从启动起超过 30s 仍从未心跳 | ALERT |

心跳的“未知”处理很重要：启动后 30 秒宽限期内，从未上报心跳只显示 `UNKNOWN`，
**不产生假告警**；角色开启但超过宽限期仍无任何心跳（以组件构造时刻为起点）时，
升级为 `STALLED` 并告警，避免 `UNKNOWN` 永远掩盖“调度根本没启动”。
曾经上报过、现在超过阈值同样报警。角色开关关闭的实例（例如
`outbox-relay-enabled=false`）不会因为 `RELAY_TICK` 缺失而报警。

## 6. 门闩（rebuild gate）停留时间

门闩是**无 TTL** 的字符串，本身没有创建时间；为观测加 TTL 会破坏重建安全性，
因此不允许。观测方案是写者把 token 改成 `<epochMillis>-<uuid>`，读侧：

- `GET /api/admin/video-status/gate?vid=123`
- 新格式 → `tokenFormat=EPOCH_MILLIS_TOKEN`，返回 `tokenAgeMillis`；
- 老格式（纯 UUID）→ `tokenFormat=LEGACY_UNKNOWN`，`tokenAgeMillis=null`，明确“未知”；
- 不存在 → `present=false`。

该接口只做一次 Redis GET：不写、不删、不刷新 TTL、不改 timeout。门闩残留本身
是安全失败（下一位持锁者接管），不要用观测手段“顺手清理”。

## 7. 多实例怎么读

- `instanceTelemetry`：本实例 operation 计数与耗时，用于判断“这台机器是否在做、
  做得多快、失败多少”；
- `backlog`：来自共享 MySQL，是**全局**积压（所有实例合计的待处理量），
  采样值是全局的、但可能 stale；
- 二者不可相加、不可互相校验；跨实例汇总请对每台实例 `/metrics` 各自取值后
  在监控侧按 instance 维度聚合。

## 8. 授权与只读保证

- 路径在 `/api/admin/**` 下，由既有 `LoginInterceptor` + `AdminInterceptor` +
  方法级 `@AdminOnly` 校验；不新增 actuator、不新增未鉴权端口；
- mapper 只有 SELECT：单测会断言 XML 中不存在 `insert/update/delete` 语句，
  采集不可能改变事件状态或修复日志；
- 响应不含 eventId/vid 维度列表、不含连接串、密钥；异常信息只保留异常类型，
  不带消息正文：SQL 文本、payload、凭证都不会进入 HTTP 响应。

## 9. 埋点接入（已实现）

```java
long start = System.nanoTime();
try {
    // 业务动作
    telemetry.record(Operation.FLUSH, Outcome.SUCCESS, System.nanoTime() - start);
} catch (VideoStatusFlushDeferredException e) {
    telemetry.record(Operation.FLUSH, Outcome.DEFERRED, System.nanoTime() - start);
} catch (RuntimeException e) {
    telemetry.record(Operation.FLUSH, Outcome.FAILURE, System.nanoTime() - start);
}
```

- 调度器每轮结束（含“本轮没有数据”）调用 `heartbeat(RELAY_TICK/FLUSH_TICK/RECOVERY_TICK)`；
- 落库事务提交成功后调用 `recordFlushedPayload(payload)`；
- 不要在事务里、不要在 `finally` 里无条件调用，避免把失败算成成功。

## 10. 验证

T05/T06 的聚焦验收命令（不启动任何基础设施的部分）：

```bash
./mvnw '-Dtest=VideoStatusMetricsSamplerTests,VideoStatusMetricsTelemetryTests,VideoStatusMetricsComponentWiringTests,VideoStatusMetricsAuthorizationTests,VideoStatusTelemetryInstrumentationTests,VideoStatusSchedulingConfigTests,VideoStatusSchedulingIsolationTests' test
```

父代理 2026-10-22 独立复跑结果：**69 tests，0 failures / 0 errors / 0 skipped**；日志
`/tmp/improvement-t05-t06-unit.log`。其中 T06 调度类也在同一批运行，验收记录不把 69 拆成
T05/T06 的虚假细分。

独立基础设施验收：

```bash
docker compose -f compose.test.yaml up -d --wait
./mvnw -DvideoStatusIT=true '-Dtest=VideoStatusMetricsIsolatedTests,VideoStatusSchedulingRealFlowTests' test
```

父代理独立复跑结果：**8 tests，0 failures / 0 errors / 0 skipped**；日志
`/tmp/improvement-t05-t06-it.log`。该批同时包含 T05 指标隔离测试与 T06 真实链路测试，
所以也不能把 8 拆成两项单独数字。

单测覆盖：封顶（`>=10001` 标记）、最老年龄阈值（恰好 120s 不告警、120s+1 告警）、
采样失败保留上次成功值并 stale、从未成功的 UNKNOWN、缺索引时 DEGRADED 且不计数、
调度心跳 STALLED/角色关闭不告警、从未心跳 30s 宽限期后 STALLED、快照 45s 未更新转 STALE、
outbox `SENT(2)` 读数、异常消息不含 SQL/payload/凭证、mapper XML 只读且带 timeout、
`@AdminOnly` + `/api/admin/**` 拦截器覆盖、门闩老格式年龄未知、
`ApplicationContextRunner` 真实 autowire（mock mapper/executor，不启动自动采样与基础设施）、
以及非 UTC 固定时钟下端到端延迟按正确时区计算。

真实隔离库（MySQL 13307）验收：

- 迁移脚本连续执行两遍都是幂等 no-op，索引列数与数据不变；
- 实际制造 OUTBOX FAILED、OUTBOX PENDING(1) 超过 120s、CONSUMED RECEIVED(0) 超过 180s、
  CONSUMED REPAIR_REQUIRED(3) 四类样本后，`sampleOnce()` 给出有限 code/subject 的告警，
  管理员控制器读取缓存路径也能查到；
- 采样前后 outbox / consumed / video_status 行逐字段相等，控制器不触发新采样；
- 两条最老查询的 `EXPLAIN` 命中 T05 新索引；
- 真实 flush 提交时 spy 到一次 `recordFlushedPayload`，外层事务回滚时零次且样本数不增；
- 门闩新格式返回停留时间，老格式返回 `LEGACY_UNKNOWN`，只调用一次只读 `getString`。

索引迁移核对（隔离测试库 13307）：

```sql
SELECT table_name, index_name, seq_in_index, column_name
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND index_name IN ('idx_video_status_outbox_created','idx_video_status_consumed_created')
ORDER BY table_name, seq_in_index;
```

## 11. 风险与未覆盖

- **索引成本**：两个二级索引会增加写放大量（outbox/consumed_event 每次插入多维护
  两棵索引），大表建索引期间有 IO 压力，需低峰执行并观察主从延迟；
- **采样是近似值**：计数封顶、最老年龄来自单次 `LIMIT 1`，与并发写入存在秒级窗口差；
- **内存计数无持久化**：进程重启后本实例 operation 计数归零，跨重启趋势要靠
  日志/外部采集（不在 T05 范围）；
- **无自动处置**：FAILED/status3/积压只告警，处置在 T10/T11；
- **日志/HTTP 告警无外部投递**：目前只写应用日志并在管理员 `/metrics` 快照中返回，
  没有邮件/IM/PagerDuty 等外部通道，也没有自动通知；值守必须主动拉取或采集日志；
- **验收边界**：四类故障样本已在独立测试库与真实 MyBatis/事务组件上验收，但未在正式环境、
  正式告警通道和目标负载下演练，交 T16；**索引迁移必须先于采样上线**，否则只会得到
  `DEGRADED` 诊断而不会计数；
- **未做**：生产监控平台、外部告警投递、跨重启趋势存储与生产阈值调优；
- **不做**：不给门闩加 TTL、不清空 Redis、不自动重放、不引入 actuator。
