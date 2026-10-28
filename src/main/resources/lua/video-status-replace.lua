-- T12-C1 受控替换：只覆盖“已存在且完整”的统计 Hash（内部原语，当前无生产调用者）。
-- KEYS[1] current stats hash
-- KEYS[2] dirty set
-- KEYS[3] hot videos zset
-- KEYS[4] rebuild gate
-- ARGV[1] vid
-- ARGV[2] hot score
-- ARGV[3..10] current values
-- ARGV[11] has pending events
-- ARGV[12] unique rebuild owner token
--
-- keys/args 形状与 video-status-init.lua 完全一致，语义只有一处根本差别：
--   init   只在 current 不存在时写入（冷启动初始化）；
--   replace 只在 current 已存在且是完整 Hash 时覆盖（“已有但错误”的受控修复原语）。
-- 因此冷 miss 一律拒绝（CURRENT_MISSING），绝不把 replace 当成初始化使用。
--
-- 本脚本明确不做的事：
-- * 不 DEL current、不改写/删除 processed 幂等 key、不释放门闩（门闩仍由调用方用同一
--   owner token 通过 video-status-release-gate.lua 释放）。替换后 processed 证据与门闩
--   都保持原状，调用方必须先持有门闩并自行负责后续语义。
-- * 不承诺事务回滚。校验失败一定零写入；但校验全部通过后的三条写命令各自可能因
--   Redis 运行时错误（noeviction 下 OOM、ACL 拒绝、实例故障）失败，此时前序阶段可能
--   已经写入。脚本用 pcall 把每条写命令的失败收敛成明确的阶段码，让调用方知道
--   “哪些写已执行、哪一条结果未知”，而不是假设整体成功或整体失败。
-- * 不用“读回相等”证明执行成功：成功只表示三条写命令都正常返回。
--
-- 校验顺序（顺序本身是契约的一部分，测试按此断言拒绝码）：
--   1 gate token    -> STALE_REBUILD
--   2 current 类型  -> CURRENT_MISSING / INVALID_REDIS_TYPE
--   3 dirty/hot 类型-> INVALID_REDIS_TYPE
--   4 current TTL   -> CURRENT_HAS_TTL
--   5 ARGV[1] vid   -> INVALID_ARGUMENT
--   6 ARGV[2] hot   -> INVALID_ARGUMENT
--   7 ARGV[3..10]   -> INVALID_ARGUMENT / COUNTER_OUT_OF_RANGE
--   8 ARGV[11]      -> INVALID_ARGUMENT
--   9 已有字段集合  -> UNEXPECTED_FIELDS
--  10 已有字段取值  -> CURRENT_INVALID_VALUE
--  11 已有 vid 一致 -> VID_MISMATCH
--
-- T02 数值约定保持不变：计数是 0..Long.MAX_VALUE 的十进制字符串，绝不 tonumber/tostring
-- 往返（> 2^53 会被 double 悄悄改写），只接受 canonical 十进制。

local LONG_MAX = '9223372036854775807'
local EXPECTED_FIELD_COUNT = 9
local EXPECTED_FIELDS = {
    'vid',
    'playTimes',
    'likeTimes',
    'unlikeTimes',
    'commentTimes',
    'coinTimes',
    'shareTimes',
    'collectTimes',
    'danmuTimes'
}

local function redisType(key)
    local result = redis.call('TYPE', key)
    if type(result) == 'table' then
        return result['ok']
    end
    return result
end

-- canonical 非负十进制：'0' 或 [1-9][0-9]*；其余一律返回 nil。
local function canonicalUnsigned(text)
    if type(text) ~= 'string' then
        return nil
    end
    if text == '0' then
        return '0'
    end
    return text:match('^[1-9]%d*$')
end

local function compareDigits(left, right)
    if #left ~= #right then
        return #left < #right and -1 or 1
    end
    if left == right then
        return 0
    end
    return left < right and -1 or 1
end

local function isFinite(value)
    return value == value and value ~= math.huge and value ~= -math.huge
end

-- 1. 只有当前门闩持有者可以覆盖；旧 token（门闩已被新 owner 接管或已丢失）一律拒绝。
if redis.call('GET', KEYS[4]) ~= ARGV[12] then
    return 'STALE_REBUILD'
end

-- 2. current 必须已存在且是 Hash：冷 miss 不是 replace 的场景。
local currentType = redisType(KEYS[1])
if currentType == 'none' then
    return 'CURRENT_MISSING'
end
if currentType ~= 'hash' then
    return 'INVALID_REDIS_TYPE'
end

-- 3. dirty / hot 允许不存在（无待刷 vid、hot 已淘汰），但类型必须正确。
local dirtyType = redisType(KEYS[2])
local hotType = redisType(KEYS[3])
if dirtyType ~= 'none' and dirtyType ~= 'set' then
    return 'INVALID_REDIS_TYPE'
end
if hotType ~= 'none' and hotType ~= 'zset' then
    return 'INVALID_REDIS_TYPE'
end

-- 4. 带 TTL 的 current 是“半初始化/异常写入”的证据：覆盖它会让过期时间继续生效，
--    因此拒绝。TTL 只可能返回 -1（无过期）或 >= 0（有/即将过期）；-2 已在第 2 步排除。
if redis.call('TTL', KEYS[1]) ~= -1 then
    return 'CURRENT_HAS_TTL'
end

-- 5. vid 必须是 canonical 非零十进制。
local vid = canonicalUnsigned(ARGV[1])
if vid == nil or vid == '0' then
    return 'INVALID_ARGUMENT'
end

-- 6. hot score 允许浮点近似，但必须有限；NaN / ±inf 会让 ZADD 抛错或写出坏值。
local hotScore = tonumber(ARGV[2])
if hotScore == nil or not isFinite(hotScore) then
    return 'INVALID_ARGUMENT'
end

-- 7. 八个计数：canonical 十进制且 <= Long.MAX_VALUE，字符串比较，不经 double。
local values = {}
for i = 3, 10 do
    local digits = canonicalUnsigned(ARGV[i])
    if digits == nil then
        return 'INVALID_ARGUMENT'
    end
    if compareDigits(digits, LONG_MAX) == 1 then
        return 'COUNTER_OUT_OF_RANGE'
    end
    values[i] = digits
end

-- 8. hasPending 只接受 'true' / 'false'。
local hasPending = ARGV[11]
if hasPending ~= 'true' and hasPending ~= 'false' then
    return 'INVALID_ARGUMENT'
end

-- 9. 已有 Hash 的字段集合必须恰好是上面九个字段。
--    用 HLEN 做固定上限判断 + 逐字段 HGET 存在性，避免 HKEYS 在大 Hash 上无界返回：
--    HLEN == 9 且九个已知字段都存在，就等价于“没有未知字段”。
local storedLength = redis.call('HLEN', KEYS[1])
if storedLength ~= EXPECTED_FIELD_COUNT then
    return 'UNEXPECTED_FIELDS'
end
local stored = {}
for i = 1, EXPECTED_FIELD_COUNT do
    local value = redis.call('HGET', KEYS[1], EXPECTED_FIELDS[i])
    if value == false or value == nil then
        return 'UNEXPECTED_FIELDS'
    end
    stored[EXPECTED_FIELDS[i]] = value
end

-- 10. 已有字段取值必须是合法 current：vid canonical 非零，八计数 canonical 且在 Long 范围内。
--     不合法说明 current 已经损坏，覆盖它只会把损坏洗白成“看起来正常”的统计。
local storedVid = canonicalUnsigned(stored['vid'])
if storedVid == nil or storedVid == '0' then
    return 'CURRENT_INVALID_VALUE'
end
for i = 2, EXPECTED_FIELD_COUNT do
    local field = EXPECTED_FIELDS[i]
    local digits = canonicalUnsigned(stored[field])
    if digits == nil or compareDigits(digits, LONG_MAX) == 1 then
        return 'CURRENT_INVALID_VALUE'
    end
end

-- 11. 不允许换 vid：替换只更新同一个 vid 的计数，不改变 Hash 的身份。
if storedVid ~= vid then
    return 'VID_MISMATCH'
end

-- 所有校验完成，开始写入。三条写命令分别用 pcall 包裹：
--   REPLACE_HSET_FAILED  —— 第一条写就失败；后续写未尝试，Hash 是否已被部分改写未知；
--   REPLACE_DIRTY_FAILED —— Hash 写已返回成功，dirty 写结果未知；
--   REPLACE_HOT_FAILED   —— Hash 与 dirty 写都已返回成功，hot 写结果未知。
-- 三种情况都不代表“整体回滚”，调用方必须按阶段诊断/重建，不能盲目重试当作无副作用。
local hashWritten = pcall(redis.call, 'HSET', KEYS[1],
        'vid', vid,
        'playTimes', values[3],
        'likeTimes', values[4],
        'unlikeTimes', values[5],
        'commentTimes', values[6],
        'coinTimes', values[7],
        'shareTimes', values[8],
        'collectTimes', values[9],
        'danmuTimes', values[10])
if not hashWritten then
    return 'REPLACE_HSET_FAILED'
end

-- pending=false 时不 SREM：保守保留 dirty 标记，宁可多刷一次也不丢一次刷库机会。
if hasPending == 'true' then
    local dirtyWritten = pcall(redis.call, 'SADD', KEYS[2], vid)
    if not dirtyWritten then
        return 'REPLACE_DIRTY_FAILED'
    end
end

local hotWritten = pcall(redis.call, 'ZADD', KEYS[3], hotScore, vid)
if not hotWritten then
    return 'REPLACE_HOT_FAILED'
end

return 'REPLACED'
