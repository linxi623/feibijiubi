# 互动统计链路 T01 基线审计报告（修正版）

> 范围：T01 只做基线审计 + 实测取证，**未修改任何业务代码、配置、数据库、`application.yml` 或既有文档**。
> 凡未实际执行的验证都标为「未执行」或「待实测」，不写作已通过。
> 关联：`docs/tutorial/video-status-improvement-plan.md`（执行计划）、`docs/tutorial/video-status-lock-free-fast-path.md`（协议）。

## 0. 交付物与证据边界

| 项 | 内容 | 证据状态 |
|---|---|---|
| 新增测试 | `src/test/java/com/feibijiubi/backend/service/video/VideoStatusSqlBaselineTests.java`（10 个用例，`-DvideoStatusIT=true` 才运行） | 本 worker **已实跑**，见 §1 |
| 新增报告 | 本文件 | — |
| 隔离实测 | 只连 `feibijiubi-test` 的 MySQL 127.0.0.1:13307（未连 16379 / 15673 / 15674，未启动/停止任何容器） | 本 worker 实际执行 |
| 静态审计 | §3 的缺陷定位基于源码阅读 | 已读源码，未逐条实跑 |
| 既有隔离集成类 | `VideoStatusIsolatedIntegrationTests` | **未运行**（父代理要求避免队列干扰） |
| 全量/无过滤 `mvn test`、`contextLoads` | — | **未运行** |

实跑命令（仅白名单测试类）：

```bash
./mvnw.cmd -o -DvideoStatusIT=true -Dtest=VideoStatusSqlBaselineTests test        # 10/10 通过
./mvnw.cmd -o -DvideoStatusIT=true -Dtest=VideoStatusSqlBaselineTests test        # 加 SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=3 做负向对照：10 个用例全部按预期在连接前被拒绝
```

## 1. 实测结果（MySQL 8.4.11，隔离实例，`play_times BIGINT UNSIGNED`）

环境：`version=8.4.11`，`session_sql_mode=ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION`（与 global 相同），`innodb_strict_mode=1`。

| # | 语句（默认模式） | 实测结果 | 落库值 |
|---|---|---|---|
| 1 | `SET play_times = play_times + (-1)`，基线 0 | **报错** `MysqlDataTruncation` / `BIGINT UNSIGNED value is out of range in (play_times + -(1))`（ERROR 1690） | 仍为 0，无回绕 |
| 2 | `SET play_times = play_times - 1`，基线 0 | **报错** 同上（`play_times - 1`） | 仍为 0 |
| 3 | 守卫式 `SET play_times = play_times + (-1) WHERE vid=? AND play_times + (-1) >= 0`，基线 0 | **报错 1690**，不是「0 行」 | 仍为 0 |
| 4 | 守卫式 `1 + (-1)` | `rows=1` | 0 |
| 5 | 守卫式 `5 + 0`（净零） | `rows=1` | 5 |
| 6 | 守卫式 `3e9 + 1` | `rows=1` | 3000000001（`BigDecimal` 读回一致） |
| 7 | 守卫式 `Long.MAX + 1` | `rows=1`（**MySQL 接受**） | 9223372036854775808 |
| 8 | `video_status` 是否有 CHECK 约束 | `0` 个 | — |

关键点 1：**默认 strict 模式下，`0 + (-1)` 与 `0 - 1` 都是 ERROR 1690**，所以现有 `applyBatchDelta` 的 `AND col + delta >= 0` 守卫**不会**退化成「0 行」，而是直接抛错。

关键点 2：**`Long.MAX + 1` 当前被 MySQL 接受并落库为 2^63**，没有任何上限拒绝。Java 侧读回：

```
jdbc Long read    -> DataIntegrityViolationException: Value '9,223,372,036,854,775,808' is outside of valid range for type java.lang.Long
```

这是「MySQL 接受、Java 不支持」的特征化基线；上限拒绝属于 **T02 待实现**，本测试只记录现状，未断言「应当拒绝」。

关键点 3（Java 映射，实测非推断）：`3e9` 经**真实 MyBatis 映射** `VideoStatusMapper.selectByVid` 读取：

```
VideoStatusMapper.selectByVid -> DataIntegrityViolationException: Error attempting to get column 'play_times'
    from result set. Cause: java.sql.SQLDataException: Value '3000000001' is outside of valid range for type java.lang.Integer
```

即 Connector/J 对 `getInt` 越界**明确抛异常**，不会静默变负。`JdbcTemplate` 以 `Integer.class` 读取结果相同（同一条 `ResultSet.getInt` 路径）。

关键点 4（会话 `sql_mode` 对照，同连接、`finally` 恢复、只动隔离数据）：

| 语句 | 默认模式 | 会话加 `NO_UNSIGNED_SUBTRACTION` |
|---|---|---|
| 守卫式 `0 + (-1)` | 报错 1690 | **仍报错 1690**（该模式只影响 `-` 运算符，不影响 `+ (-1)`） |
| `0 - 1`（无守卫） | 报错 1690 | 报错 `Out of range value for column 'play_times'`（有符号 -1 写不进 UNSIGNED 列） |
| 守卫式 `0 - 1` | 未单测 | `rows=0`（守卫生效，无报错） |
| 守卫式 `1 + (-1)` | `rows=1` → 0 | `rows=1` → 0 |

恢复校验：`finally` 后断言 `@@SESSION.sql_mode` 与原值逐字相同（用例通过）。结论：**靠会话 `sql_mode` 让现有 `col + (-1)` 写法变安全不可行**；只有 `-` 运算符形式 + 该模式才会变成「0 行」，但仍依赖会话级模式，不能作为 T02 的唯一保障。

## 2. 对既有结论的更正

1. **「两个合法事件都被改成 status 3」不成立**。`flushOne` 只在 `applyBatchDelta` 返回非 1 行时抛 `VideoStatusFlushDataException`，由 `handleDataError` 把**该批次自己的** `consumedEventIds` 标成 3（`VideoStatusBatchFlushScheduler`）；同 vid 的另一批次（例如 `+1`）不受影响。而且实测默认模式下守卫是 **1690 报错**，走的是 `DataAccessException` 分支 → `requeueDirty` + WARN **热重试**，并不会进 status 3。默认模式下的窗口表现是「依赖状态未提交期间，`-1` 无法刷库且反复重试」；依赖确认后可同批净零收敛，不能声称必然永远无法落库。
2. **「正增量优先排序保证顺序」不成立**。`selectPendingForUpdate` 的 `ORDER BY CASE WHEN delta > 0 THEN 0 ELSE 1 END, id` 优先选择已确认的正增量进入有限批次，不保证跨状态提交的全局顺序：`+1` 还在状态 0（未进候选）时，`-1` 的批次照样可以先被取走落库。跨批次窗口必须靠 T03 的依赖判定解决。
3. **`Integer` 强转不是「静默变负」**。真实映射是 `SQLDataException: Value '3000000001' is outside of valid range for type java.lang.Integer`，影响是**详情/列表接口直接 500**，不是错误数值。原报告中的 `(int) 3e9 == -1294967296` 断言已删除（那只是 Java 强转语义，不是 MyBatis 映射证据）。
4. **Lua 无法在 `Long` 全范围做精确运算（T02 风险）**。`video-status-increment.lua` 用 `tonumber(ARGV[2])` / `tonumber(HGET)` 得到 IEEE-754 double，守卫 `current + delta < 0` 只在 |值| ≤ 2^53 时精确；`HINCRBY` 本身是 64 位整数精确的，但守卫比较会在大计数（> 9,007,199,254,740,992）时给出错误判定。若 T02 把 Java/DB 计数扩到 `Long` 全范围，必须同时明确 Redis 侧上界（建议 ≤ 2^53 并显式拒绝/告警），不能假设 Lua 能对齐 `Long`。

## 3. 静态审计确认的缺陷（未逐条实跑）

- **D1（P0，T02）**：`VideoStatusMapper.xml` 的 `applyBatchDelta` 守卫 `col + delta >= 0` 在负 delta 且基线不足时抛 1690（§1 #3 已实测），`decreaseLikeTimes` / `decreaseCollectTimes` 的 `GREATEST(col - 1, 0)` 同样先算减法再兜底（§1 #2 实测 `col - 1` 在 0 上即 1690），`GREATEST` 来不及生效。
- **D2（P0，T02）**：`VideoStatus` 8 个计数与 `countLikeByUid` 都是 `Integer`，DB 为 `BIGINT UNSIGNED`；超 2^31-1 时读路径直接异常（§1 关键点 3 已实测）。上限（> `Long.MAX`）目前无人拒绝（§1 关键点 2）。
- **D3（P0，T03）**：负增量跨批次落库窗口 → 见 §2.1，当前表现为 1690 热重试，不是 status 3。
- **D4（P1，T04）**：`outbox-relay-enabled` 在 `VideoStatusProperties` 中不存在，仓库内仅出现在 `AGENTS.md` / `CLAUDE.md` / `application.yml.example` 与文档，属失效开关（静态检索确认，未运行验证）。

## 4. 仍未覆盖 / 待实测

| 编号 | 待测项 | 说明 |
|---|---|---|
| M1 | `+1`（Redis 已生效、状态 0）与 `-1` 先落库的顺序窗口 | 已由真实 MQ 消费者 latch 阻塞测试覆盖 100 轮，父代理复验通过，见 §8 |
| M2 | `applyBatchDelta` 返回 0 行时 `markRepairRequired` 的瞬态/永久分类 | mock 单测证明当前会隔离本批；真实默认模式触发 1690，不触发此分支 |
| M3 | 非 strict `sql_mode`（去掉 `STRICT_TRANS_TABLES`）下 `col + (-1)` 的行为 | 未实测（仅测了默认 strict 与 `NO_UNSIGNED_SUBTRACTION`） |
| M4 | Redis/Lua 侧大计数（> 2^53）的实际行为 | 未实测；需要 16379 与 Lua 用例 |
| M5 | Outbox `markSent` 回写失败、租约接管、旧 owner CAS | markSent 异常/0 行/失败上限已有 4 个 mock 单测；真实回写故障与租约 CAS 留待 T07 |
| M6 | 部署实例 MySQL 版本是否同为 8.4 | 未核对 `compose.yaml` 镜像 tag |
| M7 | `countLikeByUid`（`SUM(BIGINT UNSIGNED)` → `int`）上界 | 未实测 |

## 5. T02 的输入（不含实现）

1. 负增量必须在**进入 SQL 之前**按无符号安全的方式处理：任何时刻都不能让「UNSIGNED 列 + 负数」参与运算（实测两种写法都 1690）；不能依赖会话 `sql_mode`（§1 关键点 4）。
2. 0 行 / 报错 / 行缺失必须在 Java 侧显式分类，不能用异常类型猜原因（`DataAccessException` 目前被当作可重试）。
3. 计数类型统一 `Long`，并明确上界策略（`BIGINT UNSIGNED` 可存 2^63，Java `long` 不能读；Redis/Lua 精确范围只到 2^53）。
4. 迁移前需盘点已有超界数据（`play_times > 9223372036854775807` 等 8 列）。

## 6. 测试类的安全约束（已实测生效）

- 只加载 `classpath:video-status-isolated.properties`，`spring.config.location` 固定，**不读 `application.yml`**。
- `IsolationGuard` 在建立连接前校验 `spring.datasource.url` / `username` / `password` 与隔离值逐字相等。
- 对照现有 `VideoStatusIsolatedIntegrationTests.IsolationGuard`，本类**至少同样严格**：枚举全部 property source，对 `spring.datasource.hikari.*`、`spring.datasource.jndi*`、`spring.datasource.url.*`、`spring.datasource.xa.*`、`spring.datasource.driver-class-name`、`spring.datasource.type` 一律拒绝，并同时检查原名与 relaxed（下划线→点）形式。
  负向对照：设置 `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=3` 后运行，10 个用例全部在连接前失败于 `Unexpected datasource override: SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE`（未打印任何口令值）。
- `@BeforeEach` 再断言 `DatabaseMetaData.getURL()` 前缀为 `jdbc:mysql://127.0.0.1:13307/feibijiubi`。
- 只装配 DataSource + JdbcTemplate + MyBatis mapper，排除 Redis / RabbitMQ / Redisson 自动配置；不发布消息、不清队列、不写非隔离库。
- 每个用例自建 `users` / `video` / `video_status` 新行（随机用户名），只操作自己创建的 vid；会话级 `sql_mode` 改动在 `finally` 恢复并断言恢复成功。

## 7. 结论

- T01 基线**已具备可复跑证据**：10 个 SQL 用例在隔离 MySQL 8.4.11 上 10/10 通过，输出被本节引用。
- T02 **未开始**：上限拒绝、负增量安全写法、`Long` 化均未实现；`Long.MAX + 1` 目前仍会被 MySQL 接受。
- T03 的顺序窗口已由真实消费者阻塞复现；默认模式实测为暂态 1690，依赖确认后收敛。T02 改成无异常的 0 行守卫后必须同步防止合法负增量被 status3 隔离，不可单独上线 T02。

## 8. 父代理验收（2026-10-21）

- 基线源码：`23ae66c`；子代理测试提交：`7c91669`、`02788e3`、`ba5a054`、`bd37fb0`。父代理审查并修正结论后按文件合并，保留用户原有暂存删除。
- 命令：`./mvnw '-Dtest=*Tests,!BackendApplicationTests,!VideoStatusIsolatedIntegrationTests,!VideoStatusSqlBaselineTests' test`：31 tests，0 failures/errors。
- 命令：`./mvnw -DvideoStatusIT=true '-Dtest=VideoStatusSqlBaselineTests,VideoStatusIsolatedIntegrationTests' test`：25 tests，0 failures/errors，1 skipped（可选 benchmark）。环境仅 `compose.test.yaml` 项目，SQL mode 与 §1 相同；Java 21.0.9，编译目标 17。
- 真实消费者 `markRedisApplied` 前用 latch 暂停 +1；每轮新 vid，-1 先确认，触发 flush，再释放 +1。100/100 轮：窗口内 DB=0、Redis=0、+1 status0、-1 status1；释放后 DB=Redis=0，两个事件均 status2；0 次误隔离。初始值/输入事件是独立 oracle，不仅比较两份统计。
- `VideoStatusFlushOrderingTests` 2 个 mock 用例确认守卫返回 0 行时当前调度器错误隔离合法 -1；这不是对真实默认 MySQL 已发生 status3 的宣称。
- dirty 丢失并发单测、残留门闩接管、旧 token、失败快照、无锁快路径、重复消费、并发 flush 与真实 Outbox/MQ 全部回归通过。
- 测试机为本机 Windows + Docker Desktop；硬件/固定大负载吞吐预算尚未量化，因此不批准任何性能承诺或上线，T16 仍需固定机器和目标负载。测试产生的新行留在独立测试卷，没有删除失败记录或清空部署 Redis。
- 判定：T01 正确性复现与现有可运行基线通过；SQL/类型修正与顺序分类分别交 T02/T03。未证实假设、未演练的真实进程故障仍按 §4 保留。

### 变更记录

- 初版：报告 + `VideoStatusSqlBaselineTests`（未运行）。
- 修正版（本次）：`Long.MAX+1` 改 `BigDecimal` 读并特征化为「MySQL 接受 / Java 不支持」；删除 `(int) 3e9` 虚假结论，改为真实 `VideoStatusMapper.selectByVid` 映射实测；新增同会话 `NO_UNSIGNED_SUBTRACTION` 与默认模式对照（`finally` 恢复并断言）；guard 升级为拒绝全部 `spring.datasource.hikari.*`（含 relaxed 形式）并做负向对照；更正 status 3 / 正增量排序 / Lua 精度结论；实跑 10/10 通过。
