# T12-C1：受控替换统计 Hash 的 Lua 内部原语

> 状态：**候选内部原语，未开放**。只有脚本 + 一个独立 `RedisScriptConfig` bean + opt-in 隔离
> 测试；**没有** HTTP 入口、**没有** Java 业务调用者、**没有**注册任何自动调用。T12 整项
> 仍然不能勾选：强制重建所需的权限、审计、崩溃恢复与完整性校验都还没有实现。

## 1. 这个原语解决什么问题

`video-status-init.lua` 只能在 `current` **不存在**时写入（冷启动初始化）。但运维/修复场景
里还会遇到“`current` 已存在，但内容错误”的情况：手工改错、旧版本写坏、或某次异常写入留下了
半份 Hash。此时：

- 走 init 会得到 `ALREADY_INITIALIZED`，什么都不会发生；
- 直接 `DEL` 再 init 会先制造一个“统计完全不存在”的窗口，消费链路会把它当成需要重建，
  而且门闩/幂等证据的语义会被打乱。

T12-C1 提供一个受控的“覆盖已有 Hash”原语：**在持有门闩的前提下**，把九个字段整体替换为
调用方给出的新值。它刻意做得很窄——只做一次写入，其余判断全部交回调用方。

## 2. 契约：与 init 完全相同的 keys / args

| 位置 | 含义 |
| --- | --- |
| `KEYS[1]` | `current` 统计 Hash |
| `KEYS[2]` | dirty 集合 |
| `KEYS[3]` | hot 视频 zset |
| `KEYS[4]` | 重建门闩（无 TTL） |
| `ARGV[1]` | vid（canonical 非零十进制） |
| `ARGV[2]` | hot score（有限 double） |
| `ARGV[3..10]` | 八个计数：play/like/unlike/comment/coin/share/collect/danmu |
| `ARGV[11]` | `hasPending`，只接受 `true` / `false` |
| `ARGV[12]` | 门闩 owner token |

形状与 init 一致，所以调用方可以用同一套参数组装逻辑；语义差别只有一条：
**init 要求 `current` 不存在，replace 要求 `current` 已存在且完整**。

## 3. 校验顺序与拒绝码

顺序本身是契约的一部分，测试按此断言：

| # | 校验 | 失败码 |
| --- | --- | --- |
| 1 | 门闩 token 相等 | `STALE_REBUILD` |
| 2 | `current` 存在且是 Hash | `CURRENT_MISSING` / `INVALID_REDIS_TYPE` |
| 3 | dirty 为 none/set、hot 为 none/zset | `INVALID_REDIS_TYPE` |
| 4 | `current` 没有 TTL | `CURRENT_HAS_TTL` |
| 5 | vid 是 canonical 非零十进制 | `INVALID_ARGUMENT` |
| 6 | hot score 有限（拒绝 NaN/±inf） | `INVALID_ARGUMENT` |
| 7 | 八个计数 canonical 且 ≤ `Long.MAX` | `INVALID_ARGUMENT` / `COUNTER_OUT_OF_RANGE` |
| 8 | `hasPending` 是 `true`/`false` | `INVALID_ARGUMENT` |
| 9 | 已有字段集合恰好是九个已知字段 | `UNEXPECTED_FIELDS` |
| 10 | 已有字段取值合法（vid 非零、八计数 canonical 且 ≤ `Long.MAX`） | `CURRENT_INVALID_VALUE` |
| 11 | `ARGV[1]` 与 Hash 里的 `vid` 一致 | `VID_MISMATCH` |

**所有校验都在第一条写命令之前**，因此任何拒绝码都意味着 Redis 数据完全没变
（测试对每个拒绝码都断言“拒绝前后状态相同”）。

几个设计理由：

- **冷 miss 拒绝**：`CURRENT_MISSING` 而不是“顺手初始化”。replace 一旦能初始化，就会变成
  绕过重建分类（processed 证据、候选分页、上限/预算）的后门。
- **带 TTL 拒绝**：`current` 有 TTL 说明它被异常写入过；覆盖它会让过期时间继续生效，
  之后统计凭空消失。修复前必须由人判断这个 TTL 的来历。
- **未知字段拒绝，但不用 `HKEYS`**：`HKEYS` 在大 Hash 上是无界返回。这里先用
  `HLEN` 与固定上限 9 比较，再对九个已知字段逐个 `HGET` 判存在性——`HLEN == 9` 且九个
  已知字段都在，等价于“没有未知字段”，而命令数是常数（1 + 9）。
- **不允许换 vid**：替换只更新同一个 vid 的计数，不改变 Hash 的身份。
- **已有取值必须合法**：`current` 已经损坏时覆盖它，只会把损坏洗白成“看起来正常”的统计。
  这类情况应该走强制重建（尚未实现），而不是 replace。

## 4. 写入阶段与失败边界（不承诺回滚）

校验通过后依次执行三条写命令，每条都用 `pcall` 包裹：

| 阶段 | 命令 | 失败码 | 已知/未知 |
| --- | --- | --- | --- |
| 1 | 一次 `HSET` 写九个字段 | `REPLACE_HSET_FAILED` | 后续写未尝试；Hash 是否被部分改写**未知** |
| 2 | `SADD dirty vid`（仅 `hasPending=true`） | `REPLACE_DIRTY_FAILED` | Hash 写已返回成功；dirty 结果**未知** |
| 3 | `ZADD hot score vid` | `REPLACE_HOT_FAILED` | Hash 与 dirty 都已成功；hot 结果**未知** |
| — | 三条都成功 | `REPLACED` | 只代表命令正常返回，不代表“读回验证” |

必须说清楚的三件事：

1. **脚本执行是串行的**（Redis 单线程执行 Lua），所以不会有其它客户端看到“写到一半”的
   中间态；但**串行不等于原子回滚**：某条命令因为运行时错误（`noeviction` 下 OOM、ACL 拒绝、
   实例故障）失败时，前面已经执行的写不会撤销。
2. 因此阶段码表达的是“**执行到哪一步、哪一步结果未知**”，而不是“整体成功/整体失败”。
   调用方拿到 `REPLACE_DIRTY_FAILED` 时，Hash 事实上已经被替换了。
3. 脚本**不用“读回相等”证明执行成功**：读回相等只能证明某一瞬间的值，无法证明是这次命令
   写进去的，也无法覆盖并发与失败窗口。成功判定只基于命令返回。

部署前提与既有脚本一致：`maxmemory-policy=noeviction`，并且恢复依赖重建链路，不能假设
replace 自带回滚。

## 5. 明确不做的事

- **不 `DEL` current**：不存在“先删再写”的窗口。
- **不改 `processed` 幂等 key**：替换前后 processed 证据与 TTL 完全不变（测试断言）。
- **不释放门闩**：门闩仍由调用方用同一 owner token 通过 `video-status-release-gate.lua` 释放；
  旧 token 永远写不进去（`STALE_REBUILD`）。
- **`hasPending=false` 不 `SREM` dirty**：保守保留 dirty 标记。宁可多刷一次库，也不要因为
  “这次没有待刷事件”而丢掉一次刷库机会；脏标记的清理仍由批量刷库链路负责。
- **没有 unproven 模式、没有“无证据重放”**：本原语不涉及事件重放，也没有任何放宽开关。

## 6. 代码位置与“没有调用者”

- 脚本：`src/main/resources/lua/video-status-replace.lua`
- bean：`RedisScriptConfig#videoStatusReplaceScript`（`DefaultRedisScript<String>`），
  只用于测试与将来的受控入口
- 生产调用者：**无**。`grep videoStatusReplaceScript src/main/java` 只命中配置类本身。

之所以先只做原语：强制重建还缺三块硬需求——管理员权限与操作审计、与 vid 锁/门闩/批量刷库的
并发协调、以及崩溃后“已经替换了一半”的处置流程。没有这三块就开放写入口，等于把一次数据修复
变成一次不可追溯的数据破坏。

## 7. 测试

`VideoStatusReplaceLuaIsolatedTests`（opt-in，`-DvideoStatusIT=true`）共 24 例，全部跑在
`compose.test.yaml` 的真实 Redis 16379 上，复用 `VideoStatusCounterIsolatedTests.IsolationGuard`
（拒绝非隔离端点），并**通过 `RedisScriptConfig` 的 bean 执行脚本**（顺带覆盖 bean 装配）。

覆盖矩阵：

| 类别 | 用例 |
| --- | --- |
| 正常替换 | 九字段全部替换 + hot `ZADD` + 门闩保持 + current 无 TTL |
| dirty 语义 | `pending=true` 加成员；`pending=false` 不 `SREM`，且其它 vid 成员不受影响 |
| 幂等证据 | 替换前后 processed 值与 TTL 不变 |
| 冷 miss / 类型 | `CURRENT_MISSING`、`INVALID_REDIS_TYPE`（current 非 Hash、dirty 类型错、hot 类型错） |
| TTL | `CURRENT_HAS_TTL` 且拒绝后 TTL 原样保留 |
| 字段集合 | 未知字段、缺失字段 → `UNEXPECTED_FIELDS`；损坏取值 → `CURRENT_INVALID_VALUE` |
| 门闩 | 旧 owner token、缺失门闩 → `STALE_REBUILD` |
| vid 身份 | `VID_MISMATCH`、`vid=0` → `INVALID_ARGUMENT` |
| 数值边界 | 八计数 = `Long.MAX` 精确接受；`2^53+1` 原样保留（不经 double）；`Long.MAX+1` → `COUNTER_OUT_OF_RANGE`；`007`/`+1`/`-1`/`0.5`/`1e3`/空串 → `INVALID_ARGUMENT` |
| hot / pending 参数 | `nan`/`inf`/`-inf`/`abc`/空串、`TRUE`/`1`/`yes`/空串 → `INVALID_ARGUMENT` |
| 运行时部分失败 | ACL 只对专用测试用户禁用 `HSET`/`SADD`/`ZADD`，验证阶段码与“已写入的部分” |

隔离约定：每个用例用自己的随机 vid（9 亿段），只删自己的 current/gate 键与自己的 dirty/hot
成员；错误类型用例使用**专用键名**，绝不把共享 dirty 集合或 hot zset 改成别的类型。

### 7.1 运行时部分失败怎么注入的（ACL）

Redis 7 在脚本内**逐条命令**做 ACL 校验，失败会以 Lua error 抛给 `redis.call`，正好可以被
脚本里的 `pcall` 捕获，而不会中断整个脚本。于是：

1. 用 default 连接通过 Lettuce 的 `RedisAclCommands` 建一个**临时用户**
   （`+@all` 后再 `-<一条写命令>`，`~*`/`&*`），只影响这个用户名；
2. 用这个用户名/密码建一条**专用** `LettuceConnectionFactory` 执行脚本；
3. `finally` 里销毁连接并 `ACL DELUSER` 删掉临时用户。

实测三个用例的结果与阶段表一致：

- 禁 `HSET` → `REPLACE_HSET_FAILED`，Hash/dirty/hot 全都没变；
- 禁 `SADD` → `REPLACE_DIRTY_FAILED`，**Hash 已被替换**、dirty 无成员、hot 未写；
- 禁 `ZADD` → `REPLACE_HOT_FAILED`，Hash 与 dirty 都已生效、hot 无分数。

第 2、3 例正是“脚本不承诺整体回滚”的证据：它们不是模拟，而是真实的运行时写失败。

注意：ACL 管理命令不能用 Spring Data Redis 的裸 `RedisConnection#execute` 发送——它用
`ByteArrayOutput` 解码，遇到 `ACL DELUSER` 的整数回复会抛
`ByteArrayOutput does not support set(long)`（命令其实已经在服务端执行成功）。因此测试改用
Lettuce 的 `RedisAclCommands`。全程不改 default 用户、不改任何全局 Redis 配置。

## 8. 运行方式

```bash
docker compose -f compose.test.yaml up -d --wait
MAVEN_OPTS='-Xms64m -Xmx256m' ./mvnw -o -DvideoStatusIT=true \
  -DargLine='-Xms64m -Xmx512m -Djdk.attach.allowAttachSelf=true' \
  -Dtest=VideoStatusReplaceLuaIsolatedTests test
```

普通回归（本类默认 skipped）：

```bash
MAVEN_OPTS='-Xms64m -Xmx256m' ./mvnw -o '-Dtest=*Tests,!BackendApplicationTests' \
  -DargLine='-Xms64m -Xmx512m -Djdk.attach.allowAttachSelf=true' test
```

> `-Djdk.attach.allowAttachSelf=true` 是本机 JDK 21 下 Mockito inline mock maker 的必要参数：
> 内存/提交受限时 ByteBuddy 的“外部进程 attach”会失败并报
> `Could not initialize plugin: interface org.mockito.plugins.MockMaker`。
> 这与被测代码无关，但会让整轮回归看起来全红，复跑时不要误判。

## 9. 还没做的事（T12 不能勾选的原因）

- 强制重建的**入口**：管理员权限校验、操作审计表（谁、何时、为什么、替换前后是什么）、
  幂等 operationId、并发协调（vid 锁 + 门闩 + 批量刷库）；
- **崩溃恢复**：替换成功但门闩未释放、或阶段码显示“部分写入”之后的处置流程；
- **完整性校验**：替换值本身是否正确（与 MySQL 基线/事件账本对账）不在本原语范围内；
- **生产容量/性能验收**，以及 0/1,000/100,000 规模下的替换成本测量。
