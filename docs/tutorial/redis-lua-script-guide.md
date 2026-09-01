# Redis Lua 脚本语法与实战指南

> 本文结合“菲比啾比”项目中的固定窗口限流和视频统计脚本，讲解 Redis 中 Lua 脚本的基础语法、`KEYS` / `ARGV`、Redis 命令调用、返回值、原子性、Spring Boot 执行方式，以及常见问题。

---

## 1. 为什么 Redis 需要 Lua 脚本

假设我们要实现固定窗口限流：

1. 请求计数器加一；
2. 如果这是第一次请求，设置过期时间；
3. 返回当前请求次数。

如果 Java 分三次发送 Redis 命令：

```text
INCR rate:user:1001
TTL rate:user:1001
EXPIRE rate:user:1001 60
```

这些命令之间可能插入其他客户端的操作，而且应用可能在执行 `INCR` 后崩溃，导致计数器没有过期时间。

Lua 脚本可以把多个操作交给 Redis 一次执行：

```lua
local current = redis.call('INCR', KEYS[1])
local ttl = redis.call('TTL', KEYS[1])

if current == 1 or ttl < 0 then
    redis.call('EXPIRE', KEYS[1], ARGV[1])
end

return current
```

主要收益：

- **原子执行**：脚本执行期间，不会穿插执行其他客户端的普通命令；
- **减少网络往返**：Java 只需向 Redis 发送一次脚本执行请求；
- **适合“判断后修改”**：将读取、校验、写入放在一个不可分割的过程里；
- **保证多个 Redis 修改一起完成**：避免只完成前一半操作。

需要注意：Redis Lua 脚本的原子性不等于 MySQL 事务，也不等于“执行失败自动回滚”，后文会详细说明。

---

## 2. Redis 使用的是哪种 Lua

传统 Redis Lua 脚本环境使用 **Lua 5.1 风格语法**。编写 Redis 脚本时，不要随意使用较新 Lua 版本才提供的语法或标准库功能。

脚本通常保存为 `.lua` 文件，例如：

```text
src/main/resources/lua/fixed-window-rate-limit.lua
```

它本质上仍然是一段 Lua 程序，只是由 Redis 执行，并且 Redis 额外提供了：

- `KEYS`：脚本要访问的 Redis Key；
- `ARGV`：脚本的普通参数；
- `redis.call(...)`：执行 Redis 命令；
- `redis.pcall(...)`：捕获 Redis 命令错误；
- `redis.log(...)`：写 Redis 日志。

---

## 3. Lua 最常用的基础语法

## 3.1 注释

单行注释以 `--` 开头：

```lua
-- 这是单行注释
local current = 1
```

多行注释：

```lua
--[[
这是多行注释
可以写多行内容
]]
```

项目里的脚本在文件开头明确写出 `KEYS` 和 `ARGV` 的含义，这是一种很好的工程习惯：

```lua
-- KEYS[1]: 限流计数器的 Redis Key
-- ARGV[1]: 窗口长度，单位为秒
```

Lua 参数没有 Java DTO 那样的字段名，因此必须通过注释维护参数协议。

---

## 3.2 局部变量：`local`

```lua
local current = 10
local field = ARGV[1]
```

推荐始终使用 `local` 声明临时变量。

如果省略 `local`：

```lua
current = 10
```

就会变成全局变量。Redis 的脚本环境会限制不安全的全局变量使用，而且全局变量也会降低代码可读性，因此业务变量应统一使用 `local`。

Lua 不需要声明变量类型：

```lua
local count = 1
local name = 'likeTimes'
local exists = true
local nothing = nil
```

---

## 3.3 常用数据类型

Lua 常见类型包括：

| 类型 | 示例 | 用途 |
|---|---|---|
| number | `1`、`3.14` | 数值计算 |
| string | `'hello'` | Redis Key、字段、参数 |
| boolean | `true`、`false` | 条件判断 |
| nil | `nil` | 表示不存在或没有值 |
| table | `{}` | 数组、集合、映射 |
| function | `function() end` | 函数 |

可以使用 `type` 查看类型：

```lua
local value = 10
local valueType = type(value) -- 'number'
```

---

## 3.4 字符串

单引号和双引号都可以：

```lua
local field1 = 'likeTimes'
local field2 = "likeTimes"
```

字符串拼接使用 `..`，不是 Java 的 `+`：

```lua
local key = 'video:status:' .. ARGV[1]
```

不过 Redis 脚本不推荐在脚本内部动态拼接要访问的 Key。更规范的做法是由调用方构造完整 Key，然后通过 `KEYS` 传入。

字符串长度运算符为 `#`：

```lua
local length = #'hello' -- 5
```

---

## 3.5 数字转换：`tonumber`

`ARGV` 中传进来的参数通常是字符串：

```lua
local delta = ARGV[2]
```

如果需要进行数值比较或运算，应使用 `tonumber`：

```lua
local delta = tonumber(ARGV[2])
```

转换失败时返回 `nil`：

```lua
local delta = tonumber('abc') -- nil
```

所以生产脚本应先检查：

```lua
local delta = tonumber(ARGV[2])
if delta == nil then
    return 'INVALID_DELTA'
end
```

将值转成字符串可以使用 `tostring`：

```lua
local text = tostring(123)
```

### 一个容易忽略的问题

Redis 中保存的整数可能是字符串形式，但 `HINCRBY`、`INCRBY` 等命令会按照整数解析。Lua 中若要自己做加减，则必须先使用 `tonumber`。

---

## 3.6 条件判断

完整形式：

```lua
if condition then
    -- 操作
elseif anotherCondition then
    -- 其他操作
else
    -- 默认操作
end
```

例如：

```lua
if current == 1 then
    redis.call('EXPIRE', KEYS[1], ARGV[1])
end
```

Lua 使用：

- `==`：等于；
- `~=`：不等于；
- `<`、`>`、`<=`、`>=`：大小比较；
- `and`：并且；
- `or`：或者；
- `not`：取反。

例如：

```lua
if current == nil or delta == nil then
    return 'INVALID_ARGUMENT'
end

if delta > 0 and current >= 0 then
    return 'VALID'
end

if field ~= 'likeTimes' then
    return 'INVALID_FIELD'
end
```

### Lua 的真假规则

Lua 中只有下面两个值是假：

```lua
false
nil
```

以下值都是真：

```lua
0
''
{}
```

这和 Java、JavaScript 不一样。尤其是数字 `0` 在 Lua 中属于真值，不能写：

```lua
if current then
    -- current 为 0 时也会进入这里
end
```

应该明确判断：

```lua
if current == 0 then
end
```

---

## 3.7 `nil`

`nil` 表示值不存在：

```lua
if current == nil then
    return 'NOT_FOUND'
end
```

项目中的视频统计脚本使用了：

```lua
local current = tonumber(redis.call('HGET', KEYS[1], field))

if current == nil then
    return 'NEEDS_REBUILD'
end
```

当 Hash 字段不存在时，`HGET` 对脚本返回假值；传给 `tonumber` 后得到 `nil`，于是脚本要求 Java 从数据库重建缓存。

---

## 3.8 Table：既能当数组，也能当 Map

Lua 的核心复合结构是 `table`。

### 当数组使用

```lua
local fields = {'playTimes', 'likeTimes', 'coinTimes'}

local first = fields[1]
```

注意：**Lua 数组下标通常从 1 开始**，因此 Redis 使用 `KEYS[1]`、`ARGV[1]`，而不是 `[0]`。

遍历数组：

```lua
for index, value in ipairs(fields) do
    -- index 是下标，value 是元素
end
```

### 当 Map / Set 使用

项目的视频统计脚本通过 table 实现字段白名单：

```lua
local allowed = {
    playTimes = true,
    likeTimes = true,
    unlikeTimes = true
}

local field = ARGV[1]
if allowed[field] ~= true then
    return 'INVALID_FIELD'
end
```

它类似于 Java：

```java
Set.of("playTimes", "likeTimes", "unlikeTimes").contains(field)
```

也可以这样写：

```lua
local user = {
    id = 1001,
    name = 'Feriferi'
}

local id = user.id
local name = user['name']
```

---

## 3.9 循环

Redis Lua 脚本中不宜写耗时循环，但应了解基本语法。

数字循环：

```lua
for i = 1, 10 do
    -- i 从 1 到 10
end
```

遍历数组：

```lua
for index, value in ipairs(values) do
end
```

遍历 Map：

```lua
for key, value in pairs(mapping) do
end
```

`while`：

```lua
local i = 1
while i <= 10 do
    i = i + 1
end
```

不要在 Redis 脚本中写无法确定上限的循环。脚本执行时会占用 Redis 主执行线程，长时间运行会阻塞其他请求。

---

## 3.10 函数

```lua
local function isPositive(value)
    return value > 0
end

if isPositive(10) then
    return 'OK'
end
```

简单脚本通常不必拆很多函数；脚本变得很长时，应先思考是否把过多业务逻辑放进了 Redis。

---

## 4. `KEYS` 和 `ARGV`

这是 Redis Lua 脚本最重要的参数机制。

## 4.1 `KEYS`：Redis Key 参数

```lua
local counterKey = KEYS[1]
redis.call('INCR', counterKey)
```

`KEYS` 专门用于传递脚本会访问的 Redis Key。

例如调用：

```text
EVAL "return redis.call('GET', KEYS[1])" 1 user:1001:name
```

含义是：

- `1`：后面有 1 个 Key；
- `KEYS[1] = 'user:1001:name'`；
- 没有 `ARGV` 参数。

多个 Key：

```text
EVAL "return {KEYS[1], KEYS[2], ARGV[1]}" 2 key:a key:b hello
```

脚本中：

```lua
KEYS[1] -- key:a
KEYS[2] -- key:b
ARGV[1] -- hello
```

---

## 4.2 `ARGV`：普通业务参数

`ARGV` 用于传递不是 Redis Key 的普通数据，例如：

- 过期秒数；
- 用户 ID 作为普通值；
- 增量；
- Hash 字段名；
- ZSet 分数；
- 业务状态。

```lua
local field = ARGV[1]
local delta = tonumber(ARGV[2])
```

---

## 4.3 为什么 Key 不能全部放到 `ARGV`

以下写法虽然在单机 Redis 中有时能运行，但不规范：

```lua
redis.call('GET', ARGV[1])
```

正确写法：

```lua
redis.call('GET', KEYS[1])
```

原因包括：

1. Redis 需要提前知道脚本会访问哪些 Key；
2. Redis Cluster 要根据 Key 计算哈希槽；
3. 运维、分析和脚本管理工具更容易识别 Key；
4. 可以清楚区分“数据地址”和“普通值”。

工程规则可以记成：

> 脚本访问到的 Redis Key 放 `KEYS`；其余参数放 `ARGV`。

---

## 4.4 Redis Cluster 的同槽要求

Lua 脚本访问多个 Key 时，在 Redis Cluster 中这些 Key 通常必须位于同一个 hash slot，否则会出现 `CROSSSLOT` 错误。

可以使用 hash tag 控制槽位：

```text
video:status:{1001}
video:processed:{1001}:event-abc
```

Redis 只使用 `{1001}` 计算槽位，因此两个 Key 会进入同一槽。

不过项目当前视频脚本同时访问：

```text
video:status:v1:{vid}
feed:hot:videos:v2
video:status:processed:v1:{eventId}
```

如果未来从单机 Redis 迁移到 Redis Cluster，这几个 Key 不一定同槽，需要重新设计 Key 或拆分处理。单机 Redis 暂时不会出现这个问题。

---

## 5. 在 Lua 中执行 Redis 命令

## 5.1 `redis.call`

基本语法：

```lua
local result = redis.call('COMMAND', arg1, arg2, ...)
```

例如：

```lua
local value = redis.call('GET', KEYS[1])
redis.call('SET', KEYS[1], ARGV[1])
redis.call('EXPIRE', KEYS[1], ARGV[2])
```

命令名通常写成大写，便于阅读；Redis 命令本身不区分大小写。

如果命令执行错误，`redis.call` 会让整个脚本以错误结束：

```lua
redis.call('INCR', KEYS[1])
```

如果 Key 中存的是不能转换成整数的字符串，`INCR` 会报错，脚本终止。

---

## 5.2 `redis.pcall`

`pcall` 表示 protected call：

```lua
local result = redis.pcall('INCR', KEYS[1])
```

命令失败时，它不会立刻终止脚本，而是返回一个错误对象，让脚本自行处理：

```lua
local result = redis.pcall('INCR', KEYS[1])

if type(result) == 'table' and result.err then
    return 'INCREMENT_FAILED'
end

return result
```

选择原则：

- 希望错误直接中止脚本：使用 `redis.call`；
- 确实有能力恢复或转换错误：使用 `redis.pcall`。

业务脚本通常优先使用 `redis.call`，避免把真正的数据错误静默吞掉。

---

## 5.3 常见 Redis 命令示例

### String

```lua
redis.call('SET', KEYS[1], ARGV[1])
local value = redis.call('GET', KEYS[1])
local current = redis.call('INCR', KEYS[1])
redis.call('EXPIRE', KEYS[1], ARGV[2])
```

原子设置值和过期时间：

```lua
redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
```

仅当 Key 不存在时设置：

```lua
local result = redis.call('SET', KEYS[1], ARGV[1], 'NX', 'EX', ARGV[2])
```

### Hash

```lua
local value = redis.call('HGET', KEYS[1], ARGV[1])
redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
redis.call('HINCRBY', KEYS[1], ARGV[1], ARGV[2])
```

### Set

```lua
redis.call('SADD', KEYS[1], ARGV[1])
local exists = redis.call('SISMEMBER', KEYS[1], ARGV[1])
redis.call('SREM', KEYS[1], ARGV[1])
```

### Sorted Set

```lua
redis.call('ZADD', KEYS[1], ARGV[1], ARGV[2])
redis.call('ZINCRBY', KEYS[1], ARGV[1], ARGV[2])
local score = redis.call('ZSCORE', KEYS[1], ARGV[2])
```

项目视频统计脚本使用：

```lua
redis.call('ZINCRBY', KEYS[2], ARGV[4], ARGV[3])
```

对应含义：

```text
ZINCRBY 热榜Key 热度变化量 视频ID
```

### Key 操作

```lua
local exists = redis.call('EXISTS', KEYS[1])
local deleted = redis.call('DEL', KEYS[1])
local ttl = redis.call('TTL', KEYS[1])
```

`TTL` 的常见返回值：

| 返回值 | 含义 |
|---:|---|
| `>= 0` | 剩余过期秒数 |
| `-1` | Key 存在，但没有过期时间 |
| `-2` | Key 不存在 |

---

## 6. Redis 命令返回值在 Lua 中的表现

Redis 命令经过 Lua 脚本环境时会进行类型转换。日常最需要记住：

- 整数回复通常变成 Lua number；
- Bulk String 通常变成 Lua string；
- 数组回复通常变成 Lua table；
- Redis 的 nil 回复在 Lua 中通常表现为 false；
- 状态回复和错误回复有特殊 table 表示。

例如：

```lua
local value = redis.call('GET', KEYS[1])
if not value then
    return 'NOT_FOUND'
end
```

注意前面提到的规则：Lua 中 `0` 是真。因此 `EXISTS` 应明确比较：

```lua
if redis.call('EXISTS', KEYS[1]) == 1 then
    return 'EXISTS'
end
```

项目脚本正是这样做的：

```lua
if redis.call('EXISTS', KEYS[3]) == 1 then
    return 'DUPLICATE'
end
```

---

## 7. 脚本如何返回结果

使用 `return`：

```lua
return 1
return 'APPLIED'
return {1, 'hello'}
```

Spring Data Redis 需要根据脚本返回类型配置 Java 类型。

常见对应关系：

| Lua 返回值 | Spring 常用类型 |
|---|---|
| 整数 | `Long.class` |
| 字符串 | `String.class` |
| 多值数组 | `List.class` |
| 不关心结果 | `null` / 对应无返回配置 |

不要期望 Lua 的小数直接按照 Java `Double` 稳定返回。Redis Lua 的数字回复协议存在整数化等规则，涉及小数时通常更稳妥的做法是返回字符串：

```lua
return tostring(score)
```

Java 再使用 `Double.parseDouble` 转换。

---

## 8. `EVAL`、`EVALSHA` 与脚本缓存

## 8.1 `EVAL`

直接把完整脚本发给 Redis：

```text
EVAL "return redis.call('INCR', KEYS[1])" 1 counter:test
```

格式：

```text
EVAL script numkeys key [key ...] arg [arg ...]
```

例如：

```text
EVAL "return {KEYS[1], ARGV[1]}" 1 user:1 hello
```

其中：

- `script`：脚本正文；
- `numkeys = 1`：后面第一个参数是 Key；
- `KEYS[1] = user:1`；
- `ARGV[1] = hello`。

---

## 8.2 `SCRIPT LOAD` 和 `EVALSHA`

将脚本加载进 Redis：

```text
SCRIPT LOAD "return redis.call('INCR', KEYS[1])"
```

Redis 返回脚本的 SHA1 摘要，随后可以只发送摘要：

```text
EVALSHA <sha1> 1 counter:test
```

好处是无需每次传输完整脚本。

Spring Data Redis 的 `DefaultRedisScript` 会帮助处理脚本摘要和执行细节；开发者通常不需要手写 `EVALSHA`。当 Redis 重启或脚本缓存被清理后，客户端也可以在 `NOSCRIPT` 时重新发送脚本。

---

## 8.3 Redis 7 的 Functions

较新的 Redis 还提供 Redis Functions，可以把函数库持久注册到 Redis，然后通过 `FCALL` 调用。它适合集中管理服务端逻辑。

但对于当前菲比啾比项目，使用 `.lua` 文件配合 Spring Data Redis 已足够清晰，不必为了“更新”而过早引入 Functions。

---

## 9. 原子性到底是什么意思

Redis 在执行 Lua 脚本时，会把整个脚本当作一个原子操作。其他客户端看不到脚本执行到一半的状态，也不会有普通命令插入脚本中间。

例如：

```lua
local current = redis.call('GET', KEYS[1])

if current == false then
    redis.call('SET', KEYS[1], 1)
else
    redis.call('INCR', KEYS[1])
end
```

从读取、判断到修改是连续执行的，不会发生两个客户端都读到“不存在”，然后分别初始化的竞态条件。

## 9.1 原子性不等于事务回滚

这是最重要的区别之一。

假设：

```lua
redis.call('SET', KEYS[1], 'changed')
redis.call('INCR', KEYS[2]) -- 如果 KEYS[2] 不是合法整数，这里报错
```

第二条命令出错时，第一条 `SET` **不一定会像数据库事务一样自动回滚**。

因此脚本应遵循：

> 先读取和校验，确认所有条件都成立，最后再集中执行写操作。

项目的视频统计脚本就是很好的结构：

```text
1. 检查事件是否重复
2. 检查缓存是否存在
3. 校验字段白名单
4. 读取并转换数据
5. 校验序列号
6. 校验结果不能为负数
7. 最后才执行 HINCRBY、HSET、ZINCRBY、SET
```

写操作集中在脚本末尾：

```lua
redis.call('HINCRBY', KEYS[1], field, delta)
redis.call('HSET', KEYS[1], 'lastSequence', incomingSequence)
redis.call('ZINCRBY', KEYS[2], ARGV[4], ARGV[3])
redis.call('SET', KEYS[3], '1', 'EX', ARGV[5])
return 'APPLIED'
```

这能显著降低部分写入后再发生业务校验错误的可能性。

## 9.2 原子性不等于跨系统一致性

Lua 脚本只能保证脚本中的 Redis 操作原子执行，不能同时覆盖：

- MySQL；
- RabbitMQ；
- 对象存储；
- HTTP 请求；
- 其他独立系统。

Redis Lua 与 MySQL 操作不属于同一个事务。跨 Redis、MySQL、MQ 的一致性仍需通过 Outbox、幂等消费、补偿和重试等模式解决。

---

## 10. `redis.call` 可以调用所有命令吗

不能简单理解为“什么都可以”。Redis 脚本通常只能执行允许在脚本环境中运行的 Redis 命令，并受到以下约束：

- 不能执行会破坏脚本执行模型的部分管理命令；
- 不应执行阻塞命令；
- 脚本不能直接访问网络、文件系统和操作系统；
- 不应依赖不确定行为；
- 只读脚本和只读副本环境还会限制写命令。

此外，不要在脚本中调用时间复杂度很高的命令处理海量数据，例如对超大 Key 执行全量扫描，再配合大循环。

---

## 11. 菲比啾比固定窗口限流脚本逐行讲解

项目文件：

```text
src/main/resources/lua/fixed-window-rate-limit.lua
```

脚本：

```lua
-- KEYS[1]: 限流计数器的Redis Key
-- ARGV[1]: 窗口长度，单位为秒

local current = redis.call('INCR', KEYS[1])
local ttl = redis.call('TTL', KEYS[1])

-- current == 1：正常情况下，这是该窗口内的第一次请求
-- ttl < 0：兼容并修复历史上可能遗留的“没有过期时间”的计数器
if current == 1 or ttl < 0 then
    redis.call('EXPIRE', KEYS[1], ARGV[1])
end

return current
```

## 11.1 `INCR`

```lua
local current = redis.call('INCR', KEYS[1])
```

行为：

- Key 不存在：Redis 视为 `0`，加一后得到 `1`；
- Key 已存在且是整数：加一；
- Key 不是整数：命令报错。

返回值 `current` 是增加后的次数。

## 11.2 `TTL`

```lua
local ttl = redis.call('TTL', KEYS[1])
```

检查计数器还有多久过期。

## 11.3 为什么是 `current == 1 or ttl < 0`

```lua
if current == 1 or ttl < 0 then
    redis.call('EXPIRE', KEYS[1], ARGV[1])
end
```

- `current == 1`：本窗口第一次请求，应设置窗口过期时间；
- `ttl < 0`：兼容没有过期时间的异常 Key，避免它永久限流。

这里 `ttl < 0` 同时覆盖：

- `TTL = -1`：Key 没有过期时间；
- 理论上的异常/边界情况。

由于前面已经执行了 `INCR`，Key 正常情况下必然存在，因此此时一般不会得到 `-2`。

## 11.4 Java 如何传参

Java 代码：

```java
Long current = redisUtils.executeScript(
        redisScript,
        Collections.singletonList(key),
        String.valueOf(RedisConstants.RATE_UPLOAD_WINDOW_SECONDS)
);
```

对应关系：

```text
Collections.singletonList(key)                       -> KEYS[1]
String.valueOf(RATE_UPLOAD_WINDOW_SECONDS)           -> ARGV[1]
```

脚本返回 `current`，配置类声明为：

```java
redisScript.setResultType(Long.class);
```

因此 Java 得到 `Long`。

## 11.5 该限流算法的特点

这是固定窗口限流，不是滑动窗口：

```text
12:00:00 ~ 12:00:59：一个窗口
12:01:00 ~ 12:01:59：下一个窗口
```

不过当前脚本的窗口是从 Key 第一次创建时开始，而不是严格对齐自然分钟。

固定窗口可能存在边界突刺：如果每分钟允许 10 次请求，用户可以在前一个窗口末尾请求 10 次，再在下一个窗口开头请求 10 次，短时间内形成 20 次请求。

对于登录失败、获取上传凭证等基础保护场景，固定窗口通常足够；若以后要更平滑，可以考虑滑动窗口、令牌桶或漏桶。

---

## 12. 菲比啾比视频统计脚本逐段讲解

项目文件：

```text
src/main/resources/lua/video-status-increment.lua
```

它一次原子完成：

1. 事件幂等检查；
2. Redis 视频统计缓存存在性检查；
3. 可修改字段白名单校验；
4. 事件序号校验；
5. 统计值非负校验；
6. Hash 统计增加；
7. 热榜 ZSet 分数增加；
8. 写入已处理事件标记。

## 12.1 参数协议

```lua
-- KEYS[1] 视频统计 Hash
-- KEYS[2] 热点榜 ZSet
-- KEYS[3] 事件幂等 Key

-- ARGV[1] 要更新的字段
-- ARGV[2] 字段变化量
-- ARGV[3] 视频 ID，即 ZSet member
-- ARGV[4] 热点分变化量
-- ARGV[5] 幂等 Key TTL 秒数
-- ARGV[6] 聚合序列号
```

Java 传参顺序必须与这份协议完全一致：

```java
redisUtils.executeScript(
        redisScript,
        List.of(statusKey, hotVideosKey, processedEventKey),
        field,
        delta,
        vid,
        hotScoreDelta,
        ttlSeconds,
        aggregateSequence
);
```

Lua 没有参数名绑定，参数位置一旦错位，脚本可能得到完全错误的数据。

---

## 12.2 幂等检查

```lua
if redis.call('EXISTS', KEYS[3]) == 1 then
    return 'DUPLICATE'
end
```

如果事件 ID 对应的幂等 Key 已存在，说明事件处理过，不再重复增加点赞数或热度。

“先检查幂等 Key，最后再设置幂等 Key”必须在同一个原子脚本里，否则两个并发消费者都可能在检查时看到 Key 不存在。

---

## 12.3 缓存存在性检查

```lua
if redis.call('EXISTS', KEYS[1]) == 0 then
    return 'NEEDS_REBUILD'
end
```

如果视频统计 Hash 不存在，脚本不直接从零开始增加，而是让 Java 从 MySQL 重建缓存。

这是因为 Redis 是缓存，MySQL 中可能已经存在真实统计值。直接从零开始会覆盖业务语义。

---

## 12.4 字段白名单

```lua
local allowed = {
    playTimes = true,
    likeTimes = true,
    unlikeTimes = true,
    commentTimes = true,
    coinTimes = true,
    shareTimes = true,
    collectTimes = true,
    danmuTimes = true
}

local field = ARGV[1]
if allowed[field] ~= true then
    return 'INVALID_FIELD'
end
```

`field` 是动态参数，最终会传给 `HGET` 和 `HINCRBY`。白名单可以防止调用方任意修改 Hash 中的其他内部字段，例如 `lastSequence`。

这体现了一个重要原则：

> 即使调用者是自己的 Java 服务，Lua 脚本也应校验关键业务边界。

---

## 12.5 类型转换与缺失检查

```lua
local current = tonumber(redis.call('HGET', KEYS[1], field))
local delta = tonumber(ARGV[2])
local incomingSequence = tonumber(ARGV[6])
local lastSequence = tonumber(redis.call('HGET', KEYS[1], 'lastSequence'))

if current == nil or delta == nil
        or incomingSequence == nil or lastSequence == nil then
    return 'NEEDS_REBUILD'
end
```

Redis 和 `ARGV` 传来的数据大多是字符串，数值运算前统一转为 number。

如果字段缺失或数据格式错误，`tonumber` 返回 `nil`。脚本选择要求重建缓存，而不是在错误数据上继续增加。

这里可以进一步区分“参数非法”和“缓存损坏”，但当前设计将它们统一归为 `NEEDS_REBUILD`。是否拆分要看项目对故障诊断精度的需求。

---

## 12.6 序列号校验

```lua
if incomingSequence <= lastSequence then
    return 'OLD_SEQUENCE'
end

if incomingSequence ~= lastSequence + 1 then
    return 'SEQUENCE_GAP'
end
```

它解决 MQ 可能重复投递和顺序异常的问题：

- `incomingSequence <= lastSequence`：旧事件或重复事件；
- `incomingSequence > lastSequence + 1`：中间缺少事件；
- 只有 `incomingSequence == lastSequence + 1` 才能应用。

示例：

```text
Redis lastSequence = 10
事件 sequence = 10 -> OLD_SEQUENCE
事件 sequence = 9  -> OLD_SEQUENCE
事件 sequence = 11 -> 可以应用
事件 sequence = 12 -> SEQUENCE_GAP
```

注意：事件 ID 幂等和序列号校验解决的问题不完全相同：

- 事件 ID 防止同一事件重复执行；
- 序列号防止旧事件、乱序事件和事件缺口破坏聚合状态。

---

## 12.7 非负校验

```lua
if current + delta < 0 then
    return 'NEGATIVE_RESULT'
end
```

例如当前点赞数为 `0`，却收到 `-1`，脚本拒绝执行，避免产生 `-1` 个点赞这种非法数据。

这个“检查 + 修改”必须原子执行，否则检查后、修改前可能被其他请求改变。

---

## 12.8 集中写入

```lua
redis.call('HINCRBY', KEYS[1], field, delta)
redis.call('HSET', KEYS[1], 'lastSequence', incomingSequence)
redis.call('ZINCRBY', KEYS[2], ARGV[4], ARGV[3])
redis.call('SET', KEYS[3], '1', 'EX', ARGV[5])
return 'APPLIED'
```

分别表示：

1. 修改视频统计字段；
2. 更新最后应用序号；
3. 修改热榜分数；
4. 设置事件幂等标记及 TTL；
5. 返回成功状态。

使用：

```lua
redis.call('SET', KEYS[3], '1', 'EX', ARGV[5])
```

而不是分成：

```lua
redis.call('SET', KEYS[3], '1')
redis.call('EXPIRE', KEYS[3], ARGV[5])
```

前者单条 Redis 命令就同时写值和 TTL，更简洁，也避免无过期时间的中间状态。

---

## 13. Spring Boot 中加载和执行 Lua 脚本

## 13.1 配置脚本 Bean

项目配置：

```java
@Bean
public DefaultRedisScript<Long> fixedWindowRateLimitScript() {
    DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
    redisScript.setLocation(
            new ClassPathResource("lua/fixed-window-rate-limit.lua")
    );
    redisScript.setResultType(Long.class);
    return redisScript;
}
```

核心步骤：

1. 创建 `DefaultRedisScript<T>`；
2. 指定 classpath 下的脚本路径；
3. 指定返回值类型；
4. 注册为 Spring Bean。

字符串结果：

```java
@Bean
DefaultRedisScript<String> videoStatusIncrementScript() {
    DefaultRedisScript<String> redisScript = new DefaultRedisScript<>();
    redisScript.setLocation(
            new ClassPathResource("lua/video-status-increment.lua")
    );
    redisScript.setResultType(String.class);
    return redisScript;
}
```

---

## 13.2 通过 `StringRedisTemplate` 执行

项目封装：

```java
public <T> T executeScript(
        RedisScript<T> script,
        List<String> keys,
        Object... args
) {
    return executeRedisOperation(() ->
            stringRedisTemplate.execute(script, keys, args)
    );
}
```

参数对应关系：

```java
execute(script, keys, args)
```

- `script`：要执行的 Lua 脚本；
- `keys`：按顺序映射为 `KEYS[1]`、`KEYS[2]`……；
- `args`：按顺序映射为 `ARGV[1]`、`ARGV[2]`……。

使用 `StringRedisTemplate` 时，Key 和参数通常按字符串序列化，这与 Lua 脚本的参数模型很契合。

---

## 13.3 Bean 注入时要注意同类型冲突

当前配置中有：

```java
DefaultRedisScript<Long>
DefaultRedisScript<String>
```

Spring 可以利用泛型信息和注入点类型进行区分。如果以后出现两个 `DefaultRedisScript<String>` Bean，仅靠类型将无法明确选择，此时应使用：

```java
@Qualifier("videoStatusIncrementScript")
```

例如：

```java
private final DefaultRedisScript<String> videoStatusIncrementScript;
```

或在构造器参数上显式标注 `@Qualifier`，避免脚本 Bean 注入歧义。

---

## 14. 常见错误和坑

## 14.1 把 Lua 数组当成从 0 开始

错误：

```lua
local key = KEYS[0]
```

正确：

```lua
local key = KEYS[1]
```

---

## 14.2 忘记 `ARGV` 通常是字符串

危险写法：

```lua
local result = current + ARGV[1]
```

规范写法：

```lua
local delta = tonumber(ARGV[1])
if delta == nil then
    return 'INVALID_ARGUMENT'
end
local result = current + delta
```

Lua 在某些场景可能进行隐式转换，但生产代码不要依赖隐式类型转换。

---

## 14.3 使用 `!=`

Lua 不等于运算符不是 `!=`，而是：

```lua
~=
```

例如：

```lua
if field ~= 'likeTimes' then
end
```

---

## 14.4 误以为 `0` 是 false

错误思维：

```lua
if redis.call('EXISTS', KEYS[1]) then
    -- EXISTS 返回 0 时仍可能被当作真
end
```

正确：

```lua
if redis.call('EXISTS', KEYS[1]) == 1 then
end
```

---

## 14.5 在脚本里动态生成隐藏 Key

不推荐：

```lua
local key = 'user:' .. ARGV[1]
redis.call('GET', key)
```

推荐由 Java 构造完整 Key，并作为 `KEYS[1]` 传入：

```lua
redis.call('GET', KEYS[1])
```

这样更适合 Redis Cluster，也让脚本依赖更清楚。

---

## 14.6 写操作之前没有完成全部校验

不推荐：

```lua
redis.call('HINCRBY', KEYS[1], ARGV[1], ARGV[2])

if tonumber(ARGV[3]) == nil then
    return 'INVALID'
end
```

即使后面返回错误，前面的修改也已经发生。

推荐：

```lua
local delta = tonumber(ARGV[2])
local sequence = tonumber(ARGV[3])

if delta == nil or sequence == nil then
    return 'INVALID'
end

redis.call('HINCRBY', KEYS[1], ARGV[1], delta)
return 'APPLIED'
```

---

## 14.7 脚本过长或执行太慢

Redis 执行 Lua 脚本时会阻塞其他命令。不要在脚本中：

- 遍历海量元素；
- 写无上限循环；
- 对超大 Key 做复杂计算；
- 塞入大量本应由 Java 处理的业务流程；
- 试图把 Redis 当通用应用服务器。

Lua 脚本最适合：

> 少量 Key、少量命令、需要原子判断和修改的短逻辑。

---

## 14.8 误以为脚本报错会自动回滚

Redis Lua 不是 MySQL 事务。写入后再报错，之前已执行的写命令不会按数据库事务语义自动撤销。

应通过“先校验，后写入”规避。

---

## 14.9 返回类型与 Java 配置不匹配

Lua：

```lua
return 'APPLIED'
```

Java 却配置：

```java
redisScript.setResultType(Long.class);
```

就会发生结果反序列化或类型转换问题。

返回协议必须保持一致：

```text
Lua return 类型 <-> DefaultRedisScript<T> <-> Java 接收变量类型
```

---

## 14.10 脚本状态码使用魔法数字

虽然可以这样返回：

```lua
return 0
return 1
return 2
```

但复杂业务中可读性较差。项目视频脚本返回：

```lua
return 'DUPLICATE'
return 'NEEDS_REBUILD'
return 'SEQUENCE_GAP'
return 'APPLIED'
```

Java 再映射为枚举：

```java
ApplyResult.valueOf(result)
```

这种方式更清楚，适合有多个业务结果的脚本。

如果是超高频、极度关注响应大小的场景，也可以使用数字状态码，但应在 Java 和 Lua 两端定义清晰注释与常量映射。

---

## 15. 调试 Lua 脚本

## 15.1 使用 redis-cli 执行简单脚本

```text
EVAL "return ARGV[1]" 0 hello
```

返回：

```text
hello
```

计数器示例：

```text
EVAL "return redis.call('INCR', KEYS[1])" 1 test:counter
```

---

## 15.2 执行本地脚本文件

在支持 shell 重定向的环境中可以使用 `redis-cli --eval`：

```text
redis-cli --eval src/main/resources/lua/fixed-window-rate-limit.lua rate:test , 60
```

逗号前是 Key，逗号后是 `ARGV`：

```text
KEYS[1] = rate:test
ARGV[1] = 60
```

注意逗号需要作为独立参数。

---

## 15.3 使用 Redis 日志

Lua 中可以临时记录日志：

```lua
redis.log(redis.LOG_NOTICE, 'current=' .. tostring(current))
```

常见级别：

```lua
redis.LOG_DEBUG
redis.LOG_VERBOSE
redis.LOG_NOTICE
redis.LOG_WARNING
```

日志会进入 Redis 服务端日志，不会直接作为脚本返回值。生产环境避免大量打印，尤其是高频脚本。

---

## 15.4 通过返回值逐步定位

调试时可以临时返回中间数据：

```lua
return {current, ttl, ARGV[1]}
```

不过修改返回结构后，Java 的结果类型也可能需要相应调整。正式代码应恢复稳定的返回协议。

---

## 16. 脚本设计规范

建议在菲比啾比项目中继续遵循以下规范。

## 16.1 文件开头声明参数协议

```lua
-- KEYS[1] ...
-- KEYS[2] ...
-- ARGV[1] ...
-- ARGV[2] ...
```

最好同时标注：

- 数据结构类型：String、Hash、Set、ZSet；
- 单位：秒、毫秒、分数；
- 允许值；
- 返回状态。

---

## 16.2 先取参数，再校验，再写入

推荐结构：

```lua
-- 1. 读取参数
local field = ARGV[1]
local delta = tonumber(ARGV[2])

-- 2. 参数校验
if delta == nil then
    return 'INVALID_ARGUMENT'
end

-- 3. Redis 状态读取
local current = tonumber(redis.call('HGET', KEYS[1], field))

-- 4. 业务校验
if current == nil then
    return 'NOT_FOUND'
end

if current + delta < 0 then
    return 'NEGATIVE_RESULT'
end

-- 5. 集中写入
redis.call('HINCRBY', KEYS[1], field, delta)

-- 6. 返回稳定结果
return 'APPLIED'
```

---

## 16.3 返回值应该是稳定协议

不要随意改变：

```text
APPLIED
DUPLICATE
NEEDS_REBUILD
```

Java 已经依赖这些字符串映射枚举。修改 Lua 返回值时，也必须检查 Java 端枚举和分支处理。

---

## 16.4 保持脚本职责单一

合理职责：

- 原子限流；
- 原子扣减库存；
- 原子检查并删除锁；
- 幂等检查并更新多个 Redis 结构；
- 原子更新计数和排行榜。

不合理职责：

- 复杂业务编排；
- 代替 Java Service；
- 处理跨 MySQL、Redis、MQ 的完整事务；
- 执行大量计算和数据遍历。

---

## 16.5 Key 命名由 Java 统一管理

项目已经通过 `RedisKeyUtils` 生成 Redis Key。Lua 脚本接收完整 Key，而不是自行拼接。这能保证：

- Key 版本统一；
- 前缀统一；
- 便于迁移；
- 易于检查 Cluster hash tag；
- 避免 Java 与 Lua 各维护一套命名规则。

---

## 17. 三个典型实战模板

## 17.1 只有锁持有者才能释放锁

错误做法是 Java 先 `GET`，确认 value 后再 `DEL`，因为两条命令之间锁可能过期并被别人重新获得。

Lua：

```lua
-- KEYS[1] 锁 Key
-- ARGV[1] 锁持有者 token

if redis.call('GET', KEYS[1]) == ARGV[1] then
    return redis.call('DEL', KEYS[1])
end

return 0
```

它原子完成“比较 token + 删除”。

---

## 17.2 原子扣减库存

```lua
-- KEYS[1] 库存 Key
-- ARGV[1] 扣减数量

local stock = tonumber(redis.call('GET', KEYS[1]))
local amount = tonumber(ARGV[1])

if stock == nil then
    return 'NOT_FOUND'
end

if amount == nil or amount <= 0 then
    return 'INVALID_AMOUNT'
end

if stock < amount then
    return 'INSUFFICIENT'
end

redis.call('DECRBY', KEYS[1], amount)
return 'SUCCESS'
```

重点仍是：先校验，再写入。

---

## 17.3 幂等执行一次

```lua
-- KEYS[1] 幂等 Key
-- KEYS[2] 业务计数 Key
-- ARGV[1] 幂等 TTL 秒数

if redis.call('EXISTS', KEYS[1]) == 1 then
    return 'DUPLICATE'
end

redis.call('INCR', KEYS[2])
redis.call('SET', KEYS[1], '1', 'EX', ARGV[1])
return 'APPLIED'
```

它和项目视频统计脚本的基本思想一致。

---

## 18. 学习路线建议

按以下顺序掌握即可：

1. 熟悉 Lua 的 `local`、`if`、`nil`、`table`、`tonumber`；
2. 熟悉 `KEYS` 和 `ARGV` 的位置参数机制；
3. 能使用 `redis.call` 调用 String、Hash、ZSet 命令；
4. 理解 Lua 中只有 `false` 和 `nil` 为假；
5. 理解 Lua 数组从 1 开始；
6. 理解脚本原子执行，但报错不等于事务回滚；
7. 养成“先校验，后写入”的脚本结构；
8. 能在 Spring 中配置 `DefaultRedisScript<T>`；
9. 能检查 Java 参数顺序与 Lua 参数协议；
10. 最后再学习 Redis Cluster 同槽、Functions 和更高级的限流算法。

---

## 19. 快速记忆表

| 目标 | Lua / Redis 写法 |
|---|---|
| 局部变量 | `local value = ...` |
| 等于 | `==` |
| 不等于 | `~=` |
| 逻辑与 | `and` |
| 逻辑或 | `or` |
| 空值 | `nil` |
| 字符串拼接 | `..` |
| 转数字 | `tonumber(value)` |
| 转字符串 | `tostring(value)` |
| 第一个 Redis Key | `KEYS[1]` |
| 第一个普通参数 | `ARGV[1]` |
| 调用 Redis，失败中止 | `redis.call(...)` |
| 调用 Redis，捕获错误 | `redis.pcall(...)` |
| 返回结果 | `return result` |
| 数组 / Map | `table` |
| Lua 数组起点 | `1` |
| Lua 假值 | 只有 `false` 和 `nil` |

---

## 20. 一句话总结

Redis Lua 脚本的核心不是学习一门复杂的新语言，而是掌握下面这个固定模式：

```text
Java 构造 KEYS 和 ARGV
        ↓
Lua 读取参数并完成全部校验
        ↓
通过 redis.call 原子执行少量 Redis 命令
        ↓
返回稳定状态码
        ↓
Java 根据返回值继续处理业务
```

对菲比啾比当前项目来说：

- 固定窗口脚本解决了“计数增加与设置 TTL”的原子性；
- 视频统计脚本解决了“幂等、顺序校验、非负校验、Hash 更新、热榜更新”的原子性；
- MySQL、RabbitMQ 和 Redis 之间的一致性，则仍由 Outbox、幂等、重试和重建机制共同保证。
