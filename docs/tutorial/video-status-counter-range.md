# 互动计数的取值范围契约（T02）

> 适用范围：`video_status` 的 8 个计数列（`play_times` / `like_times` / `unlike_times` /
> `comment_times` / `coin_times` / `share_times` / `collect_times` / `danmu_times`）。
> 关键实现：`common/VideoStatusCounterRange.java`、`common/VideoStatusCounterRangeException.java`。
> 相关文档：[互动统计全链路设计](video-status-full-flow-design.md)、
> [批量落库实现](video-status-batch-aggregation-implementation.md)、
> [改进执行计划](video-status-improvement-plan.md)。

## 1. 一句话契约

**一个合法的互动计数只能是 `[0, Long.MAX_VALUE]` 内的整数。**

- `0`：合法（没有互动，或正负刚好抵销）。
- `< 0`：非法。取消互动必须先由业务状态证明存在对应正向计数；**不允许用
  `GREATEST(..., 0)` / `MAX(..., 0)` / `Math.max(0, ...)` 把非法取消静默改成 0**。
- `> Long.MAX_VALUE`：非法。数据库列能装下，Java 装不下，必须在入口拒绝，而不是让 MySQL
  接受 2^63 之后再在读取时抛异常。

## 2. 为什么是 `0 .. Long.MAX_VALUE`，而不是更大的范围

三处存储/运行时的上限并不一致，取交集就是 Java `long` 的正半轴：

| 环节 | 类型 | 上限 |
|---|---|---|
| MySQL 列 | `BIGINT UNSIGNED` | `2^64-1` |
| Redis `HINCRBY` | 有符号 64 位整数 | `2^63-1`（超过即报错，不回绕） |
| Java | `long` | `2^63-1`（= `Long.MAX_VALUE`） |

因此：

1. **不能**因为列是 UNSIGNED 就以为 Java 侧可以“随便大”，UNSIGNED 比 `long` 多出的那一半
   在 Java 里无对应类型，MyBatis 映射 `ResultSet.getLong` 时会失败。
2. **不要**为了迁就 UNSIGNED 而统一 `CAST AS SIGNED`。那只是把“读不出来”换成“读出来是负数”，
   问题被推迟到业务层。
3. 选 `long`（而不是 `BigInteger`）是显式取舍：Redis 有符号 64 位保证累计在
   `HINCRBY` 处就会失败（而不是回绕），Java 侧配合同一上限即可用 `Math.addExact` 精确判定。

## 3. 解析：不允许“解析失败就返回 0”

旧实现在 Redis Hash 缺字段或值异常时会静默返回 0，等于把一次**数据不一致**伪装成
“这个视频没有互动”，随后批量落库会把 0 写回 MySQL，真实计数永久丢失。

现在统一走 `VideoStatusCounterRange.parse(raw, field)`：

| 输入 | 结果 |
|---|---|
| `"3000000000"` | `3000000000L` |
| `"9223372036854775807"` | `Long.MAX_VALUE` |
| `""` / `null` | `MISSING` |
| `"abc"` / `"1.5"` / `"1e3"` | `MALFORMED`（只接受可选正负号 + 十进制数字） |
| `"-1"` | `NEGATIVE` |
| `"9223372036854775808"` | `OVERFLOW` |

`VideoStatusCounterRangeException` 刻意**不**继承 `RetryableMessageException`：同一份坏数据
重试一万次也不会变合法。它只携带 `field` + `reason`（`MISSING` / `MALFORMED` / `NEGATIVE` /
`OVERFLOW`），**如何分类（暂态依赖还是永久数据错误）由 T03 统一决定**，本层不替调度层做决定。

## 4. 两种“相加”必须分清

这是 T02 被退回时最核心的争议点，不要混为一谈。

### 4.1 `VideoStatusCounterRange.addExact(current, delta)`：非负当前值的更新

```java
long next = VideoStatusCounterRange.addExact(current, delta, "likeTimes");
```

- `current` 必须是已合法计数（`>= 0`），否则 `NEGATIVE`。
- `delta` **是有符号的**：取消点赞 / 取消收藏时就是要用负数抵销。
- **结果必须仍 `>= 0`**：
  - `addExact(1, -1) == 0` ✅（合法的净零抵销）
  - `addExact(0, -1)` ❌ `NEGATIVE`（不能减到负数，也不能 clamp 成 0）
  - `addExact(1, -2)` ❌ `NEGATIVE`
- 数学溢出（`Long.MAX_VALUE + 1`）❌ `OVERFLOW`，不静默回绕。
- 判定顺序很重要：先 `Math.addExact`（捕获 long 溢出），再判结果是否 `< 0`。
  这样“非负下界”和“long 上界”是两个可区分的原因，而不是笼统一个异常。

> 也就是说：**这个方法不是“只许加不许减”，而是“允许有符号增量、但不允许负结果”。**
> 早先的实现把 `Math.addExact` 的结果直接返回，漏掉了结果非负判定，所以
> `addExact(0, -1)` 会返回 `-1`；修复方式是显式拒绝，而不是把测试断言改成 `-1`。

### 4.2 `VideoStatusDelta.plus(other)`：有符号的批次累计

`VideoStatusDelta.plus` 是**纯有符号加法**，只负责“把一批事件的净增量求和”：

- 允许中间结果为负、最终结果为负（例如同一批次里先 +1 再 -1，或本批次净减）。
- 绝不 clamp，绝不在这里判非负。
- 溢出用 `Math.addExact` 显式抛 `ArithmeticException`，不允许回绕。

为什么这一层不能判非负？因为批次的**顺序不承载语义**：`+1` 和 `-1` 谁先出现不影响净结果。
如果在逐个相加时判非负，就会把“合法的乱序净零批次”误判为非法。正确顺序是：
**先求净增量（有符号），再对落库/重建后的最终计数判定非负。**

## 5. 重建链路（snapshot / rebuild）的累计审查结论

`VideoStatusRebuildServiceImpl` 的 `Totals` 有两处相加：

1. `add(VideoStatusDelta)`：基线 + 已确认待落库事件的净增量。
2. `add(Totals)`：基线 Totals + pending Totals。

两处都用 `Math.addExact`，**已经**具备溢出防护，本轮不需要新增累计函数；审查结论如下：

- **溢出**：`Math.addExact` 抛 `ArithmeticException`，被 `classifyConfirmedPending` 捕获后
  包装成 `RetryableMessageException`。这里刻意保留 `ArithmeticException` 这条通道：重建期间
  的“累计暂时对不上”属于可重试的暂态不一致，具体是否延后/退避由 T03 决定。
- **负结果**：`Totals.requireNonNegative(vid)` 是保留的防御性闸门（不删除、不改写），
  负值绝不写入 Redis 初始化脚本。
- **基线**：`VideoStatusRebuildSnapshotServiceImpl.load` 现在在读取时就校验
  `[0, Long.MAX_VALUE]`（`baselineValue` → `VideoStatusCounterRange.require`）。
  理由：MySQL 基线为负只可能是 schema 漂移或人工篡改，属于**永久数据错误**，
  让它带着负值进入累计、再被当成“暂态溢出”无限重试是错误的分类。
  列是 `BIGINT UNSIGNED`，`> Long.MAX_VALUE` 的值在 JDBC 映射阶段就会失败，不会以负值抵达 Java。
- **hotScore**：`double` 聚合后用 `Double.isFinite` 守卫，`NaN` / `Infinity` 同样按暂态溢出处理。

本轮**未**新增重建累计的独立单测：`Totals` 是 `VideoStatusRebuildServiceImpl` 的私有静态内部类，
直接测试需要反射或改变可见性（超出本次窄任务范围）。该路径的覆盖应放在 T03 或隔离测试
（`-DvideoStatusIT=true`）里，不能因为“代码看起来对”就宣称已验证。

## 6. JSON 与前端：`long` 转成 JSON number 之后并不安全

后端 VO 的计数是 `Long`，Jackson 默认序列化成 **JSON number**，例如：

```json
{ "playTimes": 9223372036854775807 }
```

而 JavaScript 的 `Number` 是 IEEE-754 double，**只能精确表示到 `2^53-1`
（9007199254740991）**。超过这个范围：

```js
JSON.parse('{"playTimes":9223372036854775807}').playTimes
// 9223372036854776000 —— 已经不是原值了
```

所以：

1. **不能用 `Number(...)` / `parseInt(...)` / `JSON.parse` 的默认行为去处理大计数。**
   比较、展示、回传都会被静默改写，甚至“看起来没变”的值也已经在末位失真。
2. 需要无损读取时，二选一：
   - **BigInt**：`JSON.parse(text, (k, v, ctx) => ...)` 配合 `ctx.source`（或 `json-bigint`
     之类的库）把大整数转成 `BigInt`；注意 `BigInt` 不能和 `Number` 混用，展示前转字符串。
   - **字符串传输**：后端/网关把计数字段序列化为字符串（`ToStringSerializer`），前端再按
     字符串或 `BigInt` 处理。这条路要改协议，属于后续任务，本轮**没有**改。
3. 本项目当前的真实数据远小于 `2^53`（3,000,000,000 也是安全的），但“当前安全”不等于
   “契约安全”。任何前端写 `Number(json.playTimes)` 的地方，都要记一条待办：
   上限一旦接近 `2^53` 就会失真。
4. 后端解析方向同理：`ObjectMapper` 读 `long` 是精确的（Jackson 用 `BigDecimal` 中转），
   不要手工用 `double` 解析计数。

## 7. 不改 schema，只做部署前盘点与回滚约束

**本轮 T02 不修改 `database/feibijiubi.sql`，也不提供 migration。** 原因是列已经是
`BIGINT UNSIGNED`，改动没有任何收益；需要治理的是应用侧的类型、解析和累计。

真正需要提前做的是**上线前盘点**：确认没有超过旧版 `Integer` 能力的数据。

```sql
-- 1) 有多少视频的任一计数已经超过 Integer.MAX_VALUE (2147483647)
SELECT COUNT(*) AS affected_videos
FROM video_status
WHERE play_times   > 2147483647 OR like_times    > 2147483647
   OR unlike_times > 2147483647 OR comment_times > 2147483647
   OR coin_times   > 2147483647 OR share_times   > 2147483647
   OR collect_times> 2147483647 OR danmu_times   > 2147483647;

-- 2) 列出具体行，便于评估回滚风险
SELECT vid, play_times, like_times, unlike_times, comment_times,
       coin_times, share_times, collect_times, danmu_times
FROM video_status
WHERE play_times   > 2147483647 OR like_times    > 2147483647
   OR unlike_times > 2147483647 OR comment_times > 2147483647
   OR coin_times   > 2147483647 OR share_times   > 2147483647
   OR collect_times> 2147483647 OR danmu_times   > 2147483647;

-- 3) 顺便确认没有超过 long 上限的值（被 UNSIGNED 允许、但 Java 读不出来）
SELECT COUNT(*) AS beyond_long
FROM video_status
WHERE play_times   > 9223372036854775807 OR like_times    > 9223372036854775807
   OR unlike_times > 9223372036854775807 OR comment_times > 9223372036854775807
   OR coin_times   > 9223372036854775807 OR share_times   > 9223372036854775807
   OR collect_times> 9223372036854775807 OR danmu_times   > 9223372036854775807;
```

**回滚约束（重要）**：旧版本把计数当 `Integer`/`int` 处理。只要库里存在
`> 2147483647` 的计数，回滚到旧版本就会读到错误值或直接报错。

- 盘点结果 `affected_videos = 0` → 回滚安全，按常规方式回滚镜像即可。
- 盘点结果 `affected_videos > 0` → **不能直接回滚**。要么先在维护窗口把超限计数降级
  （需要业务确认，不允许静默清零），要么回滚到仍包含 T02 计数类型的版本。
- `beyond_long > 0` → 属于数据本身超出应用契约，先单独处置数据，不要上线。

## 8. 本轮验证到哪一步

已执行（本工作树）：

```bash
./mvnw -Dtest=VideoStatusCounterRangeTests test
# Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
```

该用例是纯单元测试，不启动 Spring 上下文、不连接 MySQL/Redis/MQ，覆盖：

- `parse`：30 亿、`Long.MAX_VALUE`、超上限、缺失、小数、科学计数法、负数。
- `addExact`：`1 + (-1) = 0`、`0 + (-1)` → `NEGATIVE`、`1 + (-2)` → `NEGATIVE`、
  负 `current` → `NEGATIVE`、`0 + Long.MIN_VALUE` → `NEGATIVE`、
  `Long.MAX_VALUE + 1` → `OVERFLOW`。
- `VideoStatusDelta.plus`：有符号净增量（含负结果）、溢出抛 `ArithmeticException`。
- VO 透传：30 亿经 `VideoConverter` 到 `VideoDetailVO` / `AdminVideoDetailVO` 不变 0、不截断。

**未执行 / 不属于本轮**：

- DB/Redis 真实链路的隔离测试（`-DvideoStatusIT=true -Dtest=VideoStatusIsolatedIntegrationTests`）。
- Lua 脚本首次写入前的格式校验、`HINCRBY` 与 `SET EX` 的原子失败窗口（由并行的 Lua 任务负责）。
- 重建 `Totals` 溢出的独立单测（见 §5）。
- 前端大整数解析改造（见 §6）。

因此本文只说明**契约与已跑过的单测**，不代表“DB/Redis 大计数全链路已验证通过”。
