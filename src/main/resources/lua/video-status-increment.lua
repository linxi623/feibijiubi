-- KEYS[1] current stats hash
-- KEYS[2] dirty set
-- KEYS[3] processed event key
-- KEYS[4] hot videos zset
-- KEYS[5] rebuild gate (unique owner token)
-- ARGV[1] current field
-- ARGV[2] event delta（canonical 十进制，允许一个 '-' 前缀）
-- ARGV[3] vid
-- ARGV[4] hot score delta（必须是有限 double）
-- ARGV[5] processed ttl 秒（canonical 正整数，上限 3650 天）
--
-- T02 约定：
-- * 计数范围 0..9223372036854775807（Long.MAX_VALUE）。Lua 的 tonumber 是
--   IEEE-754 double，> 2^53 会丢整数精度，因此计数比较/相减全部走十进制字符串，
--   HINCRBY 直接使用调用方传入的原始字符串（Redis 自身按有符号 64 位整数解析）。
-- * current / delta 必须是 canonical decimal：
--     current: '0' 或 [1-9][0-9]*
--     delta  : '0' 或 -?[1-9][0-9]*
--   不接受 '+'、前导 0、'-0'、空串、小数、科学计数法。
-- * hot score 是浮点排序权重，允许近似，但必须是有限 double；已有 member 的
--   score 以及两者之和也必须有限。否则 ZINCRBY 会在 HINCRBY 已经写入之后抛错，
--   造成"计数已加、事件未标记、hot 未更新"的部分写入。
-- * 所有校验都位于第一条写命令之前：校验失败返回错误码，Redis 数据完全不变。
-- * 本脚本只提供"校验失败不写入"，不是通用事务回滚。校验全部通过后若 Redis
--   自身失败（maxmemory-policy=noeviction 下 OOM、OOM command not allowed、
--   实例被 SHUTDOWN 等），HINCRBY 之后的命令可能没有执行，边界依赖：
--     noeviction + processed key 未写入 + 消费事件表 rebuild 恢复。
--   因此部署必须使用 noeviction，且恢复依赖 rebuild，不能承诺自动回滚。

local LONG_MAX = '9223372036854775807'
local MAX_TTL_SECONDS = '315360000' -- 3650 天

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

-- canonical 有符号十进制：'0' 或 -?[1-9][0-9]*。
-- 返回 (是否负数, 无符号数字串)；非法输入返回 nil, nil。
local function canonicalSigned(text)
    if type(text) ~= 'string' then
        return nil, nil
    end
    if text == '0' then
        return false, '0'
    end
    local sign, digits = text:match('^(%-?)([1-9]%d*)$')
    if digits == nil then
        return nil, nil
    end
    return sign == '-', digits
end

-- 两个 canonical 非负数字串比较（-1 / 0 / 1）。
local function compareDigits(left, right)
    if #left ~= #right then
        return #left < #right and -1 or 1
    end
    if left == right then
        return 0
    end
    return left < right and -1 or 1
end

-- 两个 canonical 非负数字串相减（调用方保证 left >= right），逐位借位，
-- 结果同样是 canonical 字符串（去掉前导 0，全 0 时返回 '0'）。
local function subtractDigits(left, right)
    local result = {}
    local borrow = 0
    local i = #left
    local j = #right
    while i >= 1 do
        local l = tonumber(left:sub(i, i))
        local r = j >= 1 and tonumber(right:sub(j, j)) or 0
        local digit = l - r - borrow
        if digit < 0 then
            digit = digit + 10
            borrow = 1
        else
            borrow = 0
        end
        table.insert(result, 1, tostring(digit))
        i = i - 1
        j = j - 1
    end
    local text = table.concat(result):gsub('^0+', '')
    if text == '' then
        return '0'
    end
    return text
end

-- NaN 与 ±inf 都不允许进入 ZINCRBY。
local function isFinite(value)
    return value == value and value ~= math.huge and value ~= -math.huge
end

-- 与增量写入原子互斥：门闩存在时禁止写入，调用方进入持锁慢路径。
if redis.call('EXISTS', KEYS[5]) == 1 then
    return 'NEEDS_REBUILD'
end

if redis.call('EXISTS', KEYS[1]) == 0 then
    return 'NEEDS_REBUILD'
end

local currentType = redisType(KEYS[1])
local dirtyType = redisType(KEYS[2])
local hotType = redisType(KEYS[4])

if currentType ~= 'hash' then
    return 'INVALID_REDIS_TYPE'
end
if dirtyType ~= 'none' and dirtyType ~= 'set' then
    return 'INVALID_REDIS_TYPE'
end
if hotType ~= 'none' and hotType ~= 'zset' then
    return 'INVALID_REDIS_TYPE'
end

local allowedCurrent = {
    playTimes = true,
    likeTimes = true,
    unlikeTimes = true,
    commentTimes = true,
    coinTimes = true,
    shareTimes = true,
    collectTimes = true,
    danmuTimes = true
}

local currentField = ARGV[1]
if allowedCurrent[currentField] ~= true then
    return 'INVALID_FIELD'
end

local rawCurrent = redis.call('HGET', KEYS[1], currentField)
-- 字段缺失或不是 canonical 十进制都不当作 0：Hash 不完整必须重建。
local current = canonicalUnsigned(rawCurrent)
if current == nil then
    return 'NEEDS_REBUILD'
end

local deltaNegative, deltaDigits = canonicalSigned(ARGV[2])
if deltaNegative == nil or deltaDigits == nil then
    return 'INVALID_ARGUMENT'
end

local vid = canonicalUnsigned(ARGV[3])
if vid == nil or vid == '0' then
    return 'INVALID_ARGUMENT'
end

local hotScoreDelta = tonumber(ARGV[4])
if hotScoreDelta == nil or not isFinite(hotScoreDelta) then
    return 'INVALID_ARGUMENT'
end

-- TTL 必须是 canonical 正整数且不超过 3650 天：SET ... EX 只接受整数，
-- 小数/科学计数法/0/负数会让脚本在 HINCRBY 之后才失败。
local ttlDigits = canonicalUnsigned(ARGV[5])
local processedTtl = nil
if ttlDigits ~= nil
        and compareDigits(ttlDigits, '0') == 1
        and compareDigits(ttlDigits, MAX_TTL_SECONDS) ~= 1 then
    -- <= 3.1536e8，double 可以精确表示。
    processedTtl = tonumber(ttlDigits)
end
if processedTtl == nil then
    return 'INVALID_ARGUMENT'
end

-- 已存储的 current 自身必须落在 Long 范围内，否则需要重建/盘点，不能继续加。
if compareDigits(current, LONG_MAX) == 1 then
    return 'COUNTER_OUT_OF_RANGE'
end

-- 先确认统计 Hash 完整，再判断事件幂等。
-- 否则 current 丢失但 processed Key 仍在时会错误返回 DUPLICATE。
if redis.call('EXISTS', KEYS[3]) == 1 then
    return 'DUPLICATE'
end

if deltaNegative then
    -- 负增量：结果 = current - |delta|，必须 >= 0；delta 的绝对值同样不能超过 Long.MAX。
    if compareDigits(deltaDigits, LONG_MAX) == 1 then
        return 'COUNTER_OUT_OF_RANGE'
    end
    if compareDigits(current, deltaDigits) == -1 then
        return 'NEGATIVE_RESULT'
    end
else
    -- 正增量：current + delta 必须 <= Long.MAX，用 Long.MAX - delta 做精确字符串比较。
    if compareDigits(deltaDigits, LONG_MAX) == 1 then
        return 'COUNTER_OUT_OF_RANGE'
    end
    local remaining = LONG_MAX
    if compareDigits(deltaDigits, '0') == 1 then
        remaining = subtractDigits(LONG_MAX, deltaDigits)
    end
    if compareDigits(current, remaining) == 1 then
        return 'COUNTER_OUT_OF_RANGE'
    end
end

-- hot score 预检：已有 member 的 score 与"score + delta"都必须是有限 double，
-- 否则 ZINCRBY 会抛错（NaN）或写出 inf，且发生在 HINCRBY 之后。
local existingHotScore = redis.call('ZSCORE', KEYS[4], ARGV[3])
if existingHotScore ~= nil and existingHotScore ~= false then
    local existing = tonumber(existingHotScore)
    if existing == nil or not isFinite(existing) then
        return 'HOT_SCORE_OUT_OF_RANGE'
    end
    if not isFinite(existing + hotScoreDelta) then
        return 'HOT_SCORE_OUT_OF_RANGE'
    end
end

-- 所有校验必须位于第一次写命令之前：任何失败都不会产生部分变更。
redis.call('HINCRBY', KEYS[1], currentField, ARGV[2])
redis.call('SADD', KEYS[2], ARGV[3])
redis.call('ZINCRBY', KEYS[4], hotScoreDelta, ARGV[3])
redis.call('SET', KEYS[3], '1', 'EX', processedTtl)
return 'APPLIED'
